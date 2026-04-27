# Companion change for re-com

> Audience: a re-com maintainer. This document proposes one change to re-com that re-frame-pair (a Claude Code skill for introspecting re-frame apps) would benefit from. Read [`docs/initial-spec.md`](./initial-spec.md) — particularly Appendix A and §4.3b — for context.

> **Status (2026-04-27).** **Shipped upstream.** re-com commits `b3912727`
> (rc-aeh — emit `:re-com/render` trace carrying `:src` from `->attr`)
> and `961b9215` (resolve `:re-com/render` trace via exported fns, not
> the `finish-trace` macro) ship the `:src`-carrying mechanism this
> document proposed. The "Open questions" section's load-bearing Q1
> (does Reagent's render trace surface hiccup metadata?) was answered
> NEGATIVE, so re-com emits its own `:re-com/render` trace alongside
> Reagent's `:render`, with `:src` in the tags. Re-frame-pair consumer
> is in flight on its own bead (`rc-u1z` per the audit dispatch); the
> SKILL.md *re-com-aware* note will expand to mention the new trace
> tag once that consumer lands. **The proposal text below is retained
> as historical record.**

re-com components are mostly form-2 Reagent functions (outer fn returning inner render fn). `:src (at)` lands on the DOM as `data-rc-src` via `re-com.debug/->attr` (`debug.cljs:83`) but is never threaded into component metadata that Reagent's render trace picks up. re-frame-pair's v1 adapter therefore joins render trace entries to source via DOM name-and-recency matching — approximate. A small change would carry `:src` through Reagent's render trace itself, making the join exact and removing the DOM detour.

---

## A3. Carry `:src` in the render trace

**Spec proposal (verbatim from `docs/initial-spec.md` Appendix A):**

> Today re-com's `:src (at)` metadata lands on the DOM as `data-rc-src` but is not surfaced in the Reagent `:render` trace event. re-frame-pair's v1 adapter therefore joins render entries to `:src` via DOM name-and-recency matching (§4.3b) — approximate. A small re-com change — thread `:src` through to component metadata that the render trace picks up — makes the join exact and removes the DOM detour. Every render entry in an epoch would then carry `{:file :line :column}` end-to-end.

### Why this matters

- **For re-frame-pair.** Every render entry in an epoch (per `coerce-epoch` in `runtime.cljs`) already gets a `:component` from Reagent's `:component-name` tag, but `:src` is left `nil` because the Reagent trace doesn't carry it. We currently fill it in — when the user asks for it — by querying the live DOM via `data-rc-src` and matching back by component name and recency. With this change `:src` is exact and per-render, and the §4.3b DOM detour disappears.
- **For re-com.** The render trace is the canonical observation surface used by `re-frame-10x`, and is open to any other tool that registers a trace callback (error reporters, performance dashboards, custom inspectors). Today, a tool that wants to attribute renders back to source has to depend on debug-mode-only DOM markup. Carrying `:src` in the trace makes source attribution a first-class capability of re-com's debug story instead of a downstream join.

### Current state in re-com

**The `(at)` macro** (`/home/mike/code/re-com/src/re_com/core.clj:36–41`):

```clojure
(defmacro at
  []
  `(if-not ~(vary-meta 'js/goog.DEBUG assoc :tag 'boolean)
     nil
     ~(select-keys (meta &form) [:file :line])))
```

Zero-arity macro. Returns `{:file :line}` only when `goog.DEBUG` is true; nil otherwise. Note: `:column` is intentionally dropped via `select-keys` — only `:file` and `:line` survive, which is the source of re-frame-pair's `parse-rc-src` shape (file:line, no column).

**`data-rc-src` attribute emission** (`/home/mike/code/re-com/src/re_com/debug.cljs:78–83`):

```clojure
{:keys [file line]} src]
(cond->
 {:ref     ref-fn
  :data-rc rc-component}
  src
  (assoc :data-rc-src (str file ":" line))))))
```

`->attr` extracts `:src` from the args, and when debug mode is on attaches it as a single `"file:line"` string under `:data-rc-src`. There is no parallel attempt to surface `:src` through any non-DOM channel (Reagent metadata, component-name suffix, etc.).

**`:src` flow into a typical component.** Take `re-com.box/box`:

1. User writes `[box :src (at) ...]`.
2. `box` (`box.cljs:383`) accepts `:src` as a kwarg and forwards it to `box-base` (`box.cljs:391`).
3. `box-base` (`box.cljs:119`) destructures `:src` from `args` and calls `(->attr args)` (`box.cljs:153`):

   ```clojure
   [:div
    (merge
     (->attr args)
     {:class ... :style s}
     attr)
    child]
   ```
4. `->attr` consumes `:src`, attaches it to `:data-rc-src`, returns the attr map.
5. The hiccup vector `[:div ...]` is built without ever attaching `:src` (or anything else identifying it) as Reagent-visible metadata.

`:src` is documented as a public arg on every re-com component (e.g. `gap-args-desc` in `box.cljs:162`), but it is consumed only by the debug-attr machinery; the component-rendering pipeline never sees it.

**Component shape.** Most re-com components are **form-2 Reagent function components** (outer fn returning inner render fn). A small number of components use **form-3** (`reagent/create-class`) — e.g. `single-dropdown` uses `with-meta` on the component fn for lifecycle hooks (`single_dropdown/parts.cljs:135`), but not for `:src`. None of the component constructors set Reagent display-name from `:src`.

The Reagent render trace event reports `:component-name` (the munged Reagent name, e.g. `re_com.box.h_box`) and `:duration`. Whether the render trace can be made to surface additional fields — the open question that drives the design choice below — depends on Reagent internals that re-com doesn't own.

### v1 workaround in re-frame-pair

The DOM detour lives in `scripts/re_frame_pair/runtime.cljs`:

- **`parse-rc-src`** (line 920) — string parse of `data-rc-src` into `{:file :line}`.
- **`dom-source-at`** (line ~987) — given a CSS selector or `:last-clicked`, fetches the live element and parses its `data-rc-src`.
- **last-clicked listener** (~line 956) — passive document-wide click capture, records the most recently clicked element.

Render-trace entries themselves are coerced in `coerce-epoch` (`runtime.cljs:510`) and the per-render shape comes out of `renders-from-traces` (~line 433):

```clojure
(mapv (fn [t]
        (let [tags (:tags t)
              comp (demunge-component-name
                     (or (:component-name tags) (str (:operation t))))]
          {:component comp
           :time-ms   (:duration t)
           :reaction  (:reaction tags)})))
```

`:src` is set to `nil` at coerce time and a comment in the file calls this out: re-com's `:src` is not threaded into the render trace today, and the `dom/*` ops do that join via the live DOM (§4.3b).

**Why the join is approximate.** The recency match assumes that the most recently rendered (or clicked) element with a given component name produced the render entry being attributed. This breaks when:

1. The same component type renders multiple instances in quick succession — there is no per-render handle to disambiguate.
2. The DOM has unmounted between trace emission and lookup, removing the `data-rc-src` node entirely.
3. Two instances of the same component carry the same CSS-resolvable signature.

In practice this is "usually fine" for a Claude-driven recipe that focuses on the most recent click, but it is not a contract we can build stronger recipes on.

### Proposed change

**Where the change lands.** In `re-com.debug` and (potentially) the component-rendering machinery in each component file (e.g. `re-com.box`, `re-com.buttons`, …). Likely shape: `->attr` (or a sibling fn) returns not just an attr map but a `with-meta`-style wrapper that callers can apply to the outer hiccup vector — or `->attr`'s call sites attach `:src` to the hiccup directly.

**New behaviour.** When a component is invoked with `:src (at)`, the rendered hiccup carries `:src` as Reagent-visible component metadata (via `with-meta`, or via a Reagent class `:display-name` augmentation, or via a Reagent props key — whichever Reagent's render trace picks up). The render-trace event would then include `:src` in its `:tags`:

Today:
```clojure
{:op-type :render
 :tags    {:component-name "re_com.box.h_box"}
 :duration 2.5}
```

After:
```clojure
{:op-type :render
 :tags    {:component-name "re_com.box.h_box"
           :src            {:file "app/user_panel.cljs" :line 42}}
 :duration 2.5}
```

The exact mechanism — `with-meta` on the outer hiccup vector, an extra Reagent component prop, a class display-name, or something else — depends on what Reagent's render trace currently picks up (see Open questions). The proposal is the *outcome*: render-trace entries carrying `:src`. The implementation choice is for the maintainer.

**Public surface change: none.** `:src (at)` is already a documented argument on every re-com component. Call sites do not change. Debug-mode behaviour improves; non-debug-mode behaviour is unchanged (since `(at)` already returns `nil` when `goog.DEBUG` is false).

### Scope estimate

Small change concentrated in `re-com.debug` and the `->attr` call sites in `re-com.box` (and any other components that build hiccup with `->attr`). Likely ~30–50 LOC if the threading can be centralised; up to ~100 LOC if every component file needs a touch. A spike to confirm the Reagent-trace mechanism would probably take an hour.

### Compatibility / migration notes

- **Strictly additive.** Existing render-trace consumers (10x in particular) see `:src` appear only on re-com components when debug is on; everywhere else `:src` is absent and the consumer continues to operate as today.
- **No re-frame coordination required.** The change is purely on re-com's side. re-frame's trace machinery is untouched.
- **10x compatibility.** 10x reads `:component-name` and `:duration` from the render trace today; the addition of `:src` in `:tags` is transparent. (Worth confirming on a live 10x build.)
- **Reagent version dependency.** Whichever mechanism is chosen, it must work across the Reagent versions re-com supports. Hiccup-metadata propagation in particular has been a quietly-evolving area of Reagent.

### Open questions

1. **Does Reagent's render trace pick up hiccup metadata?** This is the load-bearing question. If `(with-meta [:div ...] {:src ...})` makes `:src` appear in the render trace's `:tags`, the implementation is small and centralised in `->attr`. If not, the change has to attach `:src` via a Reagent-aware mechanism (component class display-name, props key, …) at every component call site. **Recommended spike:** in a re-com fixture, attach `(with-meta [:div] {:probe :hello})`, render it, capture the 10x render trace, and confirm whether `:probe` appears in `:tags`.
2. **Form-3 components using `reagent/create-class`.** Can `:src` be threaded through the class spec (`:display-name`, an extra `:src` key, a wrapper fn) so it surfaces in the render trace alongside form-2 components?
3. **Dropping `:column`.** `(at)` currently strips column at macro expansion. Should the change preserve that limitation (keep `{:file :line}` only) or thread `:column` through? **Recommendation:** preserve current limitation; consumers can fill in `:column nil`. Adding column would be a separate small change.
4. **Function components** (newer Reagent: hiccup with a fn at head, no class). Anything different needed compared to form-2/3?
