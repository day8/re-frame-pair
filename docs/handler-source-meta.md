# `handler/source` and source-meta in shadow-cljs apps

> Why `scripts/handler-source.sh` returns `{:ok? false :reason :no-source-meta}` against shadow-cljs builds, and what (if anything) the operator can do about it.

> **Status (rfp-rsg, v0.2):** Path 3 has shipped — see
> `re-frame-pair.runtime/reg-event-db` etc. (macros in
> `scripts/re_frame_pair/runtime.clj`). `handler-source` now consults
> the registration-macro side-table first, and the response carries
> `:source :registration-macro` when the opt-in is in use.

The `handler/source` op (rfp-r5s C, commit `5a4a447`) reads the metadata that ClojureScript's source-map machinery would attach to a registered handler, and returns `{:file :line :column}` if it's there. Against the live fixture and most real apps it consistently returns the documented graceful-fail:

```
scripts/handler-source.sh :event :counter/inc
=> {:ok? false :reason :no-source-meta :kind :event :id :counter/inc}
```

This document is the rfp-bni follow-up. Bottom line: the symptom is **structural**, not a config oversight. No build flag can fix it. Path 3 (a registration macro that captures call-site meta) is the only reliable solution and is recommended for v0.2.

---

## What the live fixture actually looks like

Probed from the running shadow-cljs build via `scripts/eval-cljs.sh`:

```
(meta (re-frame.registrar/get-handler :event :counter/inc))
=> nil

(meta (last (re-frame.registrar/get-handler :event :counter/inc)))
=> nil   ;; the terminal interceptor map carries no meta

(meta (:before (last (re-frame.registrar/get-handler :event :counter/inc))))
=> nil   ;; re-frame's wrapper fn has no meta either

(meta (re-frame.registrar/get-handler :sub :counter))
=> nil

(meta (re-frame.registrar/get-handler :fx :db))
=> nil
```

For comparison, var-meta on a `defn`'d top-level *does* work:

```
(do (defn test-fn-3 [] :hi) (meta (var test-fn-3)))
=> {:ns cljs.user :name test-fn-3 :file "..." :line 1 :column 5
    :end-line 1 :end-column 20 :arglists ([]) :doc nil :test nil ...}
```

So shadow-cljs is preserving source-map metadata — but on **vars**, not on **fn values**.

## The structural reason

re-frame's `reg-event-db` (`re_frame/core.cljc:99–109`) wraps the user's handler:

```clojure
(reg-event-db :counter/inc (fn [db _] ...))
;; calls (db-handler->interceptor handler)
```

`db-handler->interceptor` (`re_frame/std_interceptors.cljc:73`) is the wrap site. It takes `handler-fn` and returns:

```clojure
(->interceptor
  :id     :db-handler
  :before (fn db-handler-before [context]   ;; <-- a fresh fn
            (let [new-context ...]
              ;; calls handler-fn here, closed over
              ...)))
```

The user's handler is **captured in the closure** of `db-handler-before`. The registrar stores the interceptor chain. Reading `(:before terminal-interceptor)` returns `db-handler-before` — re-frame's wrapper, not the user's fn. Even if shadow-cljs preserved every conceivable bit of metadata on every fn, `db-handler-before`'s file/line points at `std_interceptors.cljc`, not at the user's call site.

The same pattern applies to `reg-event-fx` (`fx-handler->interceptor`) and `reg-event-ctx` (`ctx-handler->interceptor`). For `reg-sub` and `reg-fx` the stored value *is* the user's fn — but those are typically anonymous `(fn [...] ...)` expressions, which carry no metadata at all in CLJS (CLJS fns don't support per-instance metadata; only vars do).

## Path 1 — shadow-cljs config tweaks

**Verdict: nothing helps.** The data the op needs (the file:line of where the user wrote `reg-event-db`) is fundamentally not in the registrar, regardless of compile flags.

Flags surveyed for completeness, all of which I expected to fail and confirmed do:

| Flag | Effect | Helpful? |
|---|---|---|
| `:source-map true` (default in dev) | JS-to-CLJS source-map files emitted | No — the data is on vars, not fn values, and the registrar holds fn values |
| `:preserve-protocol-meta true` | (CLJS reader option for protocols) | No — handlers aren't protocols |
| `:closure-defines goog.DEBUG true` | Already on in the fixture | No — surfaces nothing extra on registered fns |
| `:fn-invoke-direct true/false` | Inlining strategy | No — orthogonal |
| `:elide-asserts false` | Keep `assert` forms | No — orthogonal |

There is no shadow-cljs option that reaches into a closure and exposes a captured value with its source meta. Such an option would essentially require source-instrumenting every `(fn ...)` form in the build to attach a side-channel of `^{:file ... :line ...}` metadata to its returned object — a non-trivial CLJS compiler change, not a knob.

## Path 2 — source-map readback

**Verdict: theoretically possible, practically unreliable.**

CLJS source maps map JS-output offsets back to CLJS source positions. Given a fn value, we could:

1. Call `.-toString` on it to get the JS-compiled source.
2. Match characteristic substrings (handler ID, body) against the loaded source-map.
3. Resolve to a CLJS file:line.

Why this fails in practice for handler-source's use case:

- **The toString surface IS re-frame's wrapper.** Calling `.toString` on `(:before terminal-interceptor)` returns `db-handler-before`'s JS body (string `"function re_frame$std_interceptors$db_handler__GT_interceptor_$_db_handler_before(context){ ... }"`). The user's fn is a captured variable inside; it doesn't show up in toString unless it's been declared at the top level (it usually isn't).
- **Even if we could see the user's fn body,** matching a pretty-printed JS function's body against source-map entries is fragile: minification, identifier munging, multi-statement bodies on one line. CLJS source-maps are generally consumed BY a stack-trace decoder, where you have a precise line/column to query — not by a structural string-match.
- **The closure variable is anonymous.** The user wrote `(fn [db _] ...)` — no name in the JS output to anchor on.

A spike on this would take days and produce a heuristic that works on toy fixtures and breaks on real apps.

## Path 3 — registration macro that captures call-site meta

**Verdict: this is the path. Recommended for v0.2.**

A re-com-style `(at)` macro at the call site captures `{:file :line}` at compile time. A wrapper for each `reg-event-*` / `reg-sub` / `reg-fx` accepts the captured location alongside the id and stores it in a side-table the runtime owns:

```clojure
(re-frame-pair.runtime/reg-event-db
  :counter/inc
  (re-frame-pair.runtime/at)             ;; {:file "app/events.cljs" :line 18}
  (fn [db _] ...))
```

Or, more invasively, a macro that wraps `re-frame.core/reg-event-db` and inserts the meta-capture for the user:

```clojure
(re-frame-pair.runtime/reg-event-db :counter/inc (fn [db _] ...))
;; expands to:
(do
  (re-frame.core/reg-event-db :counter/inc (fn [db _] ...))
  (swap! re-frame-pair.runtime/handler-source-table
         assoc-in [:event :counter/inc]
         {:file "app/events.cljs" :line 18}))
```

`handler-source` would then check the side-table first and fall back to `(meta f)` if the user is using the bare re-frame macros.

**Tradeoffs:**

- Pros: 100% reliable across compile modes; no shadow-cljs internals dependency; identical mental model to re-com's `:src (at)` so users already familiar with the pattern adapt instantly.
- Cons: opt-in (users have to migrate their reg-event-db calls); duplicates re-frame's macros (we have to keep up with reg-event-* signature changes); requires a CLJS macro file (re-frame-pair currently ships only runtime forms — adds a build-time concern).

**Effort:** ~80 LOC (macro file + side-table + handler-source rewire + SKILL.md recipe + 4-5 deftests). Half a day.

## Cross-reference: Appendix A item A7

`docs/companion-re-frame.md` § A7 proposes that re-frame retain handler source forms in dev builds, gated by `debug-enabled?`. If A7 lands upstream, handler-source becomes trivial — `(meta handler-fn)` returns `{:file :line :source-form}` end-to-end without needing Path 3's macro layer. **Path 3 is the workaround until A7 lands; the two are not exclusive.**

## Recommendation

1. **Now (v0.1):** keep handler-source as-is. It returns `:no-source-meta` cleanly with a hint; the agent can fall back to grep. Document the limitation in `SKILL.md` (already done in the `handler/source` row).
2. **v0.2:** ship Path 3 (registration macro + side-table). One commit, reasonable surface area, makes the op useful for any operator who opts in.
3. **Long term:** support A7 if/when re-frame ships it.

## Operator-facing wording for SKILL.md

A short note can land in the existing `handler/source` row to set expectations honestly:

> Source-map meta is on `defn` vars, not anonymous fn values. re-frame stores fn values in the registrar, and the user's handler is captured in re-frame's interceptor-wrapper closure — so even with full source-map preservation, `handler/source` cannot reach it. Returns `:no-source-meta` cleanly when this happens. v0.2 will add an opt-in registration macro (`re-frame-pair.runtime/reg-event-db` etc.) that captures the call site at compile time and surfaces it through `handler/source`.

---

## Appendix — fixture probe transcript

```
$ scripts/eval-cljs.sh '(meta (re-frame.registrar/get-handler :event :counter/inc))'
{:ok? true, :value nil}

$ scripts/eval-cljs.sh '(meta (last (re-frame.registrar/get-handler :event :counter/inc)))'
{:ok? true, :value nil}

$ scripts/eval-cljs.sh '(meta (:before (last (re-frame.registrar/get-handler :event :counter/inc))))'
{:ok? true, :value nil}

$ scripts/eval-cljs.sh '(.-name (:before (last (re-frame.registrar/get-handler :event :counter/inc))))'
{:ok? true, :value "re_frame$std_interceptors$db_handler__GT_interceptor_$_db_handler_before"}

$ scripts/eval-cljs.sh '(do (defn test-fn-3 [] :hi) (meta (var test-fn-3)))'
{:ok? true, :value {:ns cljs.user, :name test-fn-3, :file "...", :line 1, :column 5,
                    :end-line 1, :end-column 20, :arglists ([]), :source "test-fn-3", ...}}

$ scripts/handler-source.sh :event :counter/inc
{:ok? false, :reason :no-source-meta, :kind :event, :id :counter/inc}

$ scripts/handler-source.sh :sub :counter
{:ok? false, :reason :no-source-meta, :kind :sub, :id :counter}

$ scripts/handler-source.sh :fx :db
{:ok? false, :reason :no-source-meta, :kind :fx, :id :db}
```

Conclusion: the op behaves as designed, the no-source-meta path is what users will hit, and the only reliable upgrade is Path 3 (registration macro) for v0.2 — or A7 upstream.
