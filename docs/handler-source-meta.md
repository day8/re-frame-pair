# `handler/source` and source-meta in shadow-cljs apps

> Why `scripts/handler-source.sh` returns `{:ok? false :reason :no-source-meta}` against shadow-cljs builds, and what (if anything) the operator can do about it.

> **Status (rfp-hpu):** Superseded by upstream re-frame rf-ysy
> (commit `15dfc25` in re-frame). `reg-event-db` / `reg-event-fx` /
> `reg-event-ctx` / `reg-sub` / `reg-fx` are now defmacros that
> attach `{:file :line}` to the registered value via `with-meta`;
> `(meta (registrar/get-handler kind id))` returns the location
> directly. Path 3 (the rfp-rsg local registration-macro side-table
> in `scripts/re_frame_pair/runtime.clj` + `-record-source!` +
> `handler-source-table` atom) has been retired — `handler-source`
> now reads upstream meta straight through. The opt-in is no longer
> needed; every `(reg-event-db ...)` site captures meta automatically.
>
> **Earlier status (rfp-rsg, v0.2):** Path 3 had shipped as a
> re-frame-pair-side macro layer. Kept in this doc for historical
> context — see the Path 3 section below — but the implementation is gone.

The original `handler/source` op (rfp-r5s C, commit `5a4a447`) read the metadata that ClojureScript's source-map machinery would attach to a registered handler, and returned `{:file :line :column}` if it was there. Against the live fixture and most real apps at the time, it consistently returned the documented graceful-fail:

```
scripts/handler-source.sh :event :counter/inc
=> {:ok? false :reason :no-source-meta :kind :event :id :counter/inc}
```

This document is the rfp-bni follow-up. Historical bottom line: the symptom was **structural**, not a config oversight. No build flag could fix it. Path 3 (a registration macro that captures call-site meta) was the recommended local workaround for v0.2.

That recommendation is now superseded. Upstream re-frame rf-ysy implemented the essential call-site capture in the registration APIs themselves, so `handler/source` reads upstream metadata directly. Path 3 remains below only as historical design rationale for the retired rfp-rsg implementation.

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

## What actually happened — rf-ysy

Upstream re-frame changed `reg-event-db`, `reg-event-fx`, `reg-event-ctx`, `reg-sub`, and `reg-fx` into macros that attach `{:file :line}` metadata to the registered value. `handler/source` can now resolve the location with:

```clojure
(meta (re-frame.registrar/get-handler kind id))
```

That makes the local side-table unnecessary. There is no longer an opt-in migration to `re-frame-pair.runtime/reg-event-db`, no `handler-source-table` to consult, and no `scripts/re_frame_pair/runtime.clj` layer to maintain.

## Path 3 — registration macro that captures call-site meta (historical)

**Historical verdict (rfp-rsg):** this was the recommended v0.2 path and briefly shipped as a local macro layer. It is no longer the current direction because rf-ysy moved the needed metadata capture upstream.

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

The retired `handler-source` implementation checked the side-table first and fell back to `(meta f)` if the user was using the bare re-frame APIs.

**Historical tradeoffs:**

- Pros: 100% reliable across compile modes; no shadow-cljs internals dependency; identical mental model to re-com's `:src (at)` so users already familiar with the pattern adapt instantly.
- Cons: opt-in (users have to migrate their reg-event-db calls); duplicates re-frame's macros (we have to keep up with reg-event-* signature changes); requires a CLJS macro file (re-frame-pair currently ships only runtime forms — adds a build-time concern).

**Original effort estimate:** ~80 LOC (macro file + side-table + handler-source rewire + SKILL.md recipe + 4-5 deftests). Half a day.

## Cross-reference: Appendix A item A7 (superseded by rf-ysy)

`docs/companion-re-frame.md` § A7 proposes that re-frame retain handler source forms in dev builds, gated by `debug-enabled?`. rf-ysy did not land exactly as A7: it does not retain full source forms. It did land the part `handler/source` needs, though: call-site metadata on registered handlers. That makes Path 3 obsolete by mechanism even though A7's fuller `:source-form` idea remains a possible future enhancement.

## Recommendation (current)

1. **Current behavior:** keep `handler/source` on the upstream rf-ysy path: read `(meta (registrar/get-handler kind id))` and return the registered `{:file :line}` directly.
2. **Do not revive Path 3:** the rfp-rsg macro layer and side-table are historical. They add opt-in surface area that upstream re-frame no longer requires.
3. **Future enhancement:** if re-frame later ships fuller A7-style source-form retention, extend `handler/source` to expose that extra data without reintroducing the local registration wrapper.

## Operator-facing wording for SKILL.md

The `handler/source` row should describe the current rf-ysy behavior, not the retired v0.2 plan:

> `handler/source` reads `{:file :line}` metadata from the registered re-frame handler via `(meta (registrar/get-handler kind id))`. Upstream re-frame rf-ysy attaches that metadata automatically in the registration macros, so no re-frame-pair opt-in registration wrapper is needed.

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

Historical conclusion: the original op behaved as designed, the no-source-meta path was what users hit, and the only reliable local upgrade was Path 3. Current conclusion: rf-ysy provided the needed upstream metadata capture, so Path 3 is retired and `handler/source` should stay on the upstream-meta path.
