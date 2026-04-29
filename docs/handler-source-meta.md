# Why source-meta needs the instrumented namespaces

Design rationale for why `:event/source`, `:subscribe/source`, and `handler/source` populate **only** when the host opts into `re-frame.core-instrumented` (or `re-frame.alpha-instrumented`). For the operator-facing how-to — the swap, when to offer it, caveats — see [`docs/skill/source-meta.md`](skill/source-meta.md).

## What `handler/source` reads

```clojure
(meta (re-frame.registrar/get-handler kind id))
;; => {:file "..." :line ... :column ...}     ;; if host opted in
;; => nil                                     ;; if host is on plain re-frame.core
```

The instrumented namespace's `reg-event-db` / `reg-event-fx` / `reg-event-ctx` / `reg-sub` / `reg-fx` are **macros** that capture call-site source meta at expansion and attach it to the registered value. `dispatch` / `dispatch-sync` / `subscribe` are macros that attach the same `:re-frame/source` meta to the event vector / query vector. Plain `re-frame.core` ships those names as ordinary functions, so the meta-attach step never runs.

## Why plain `re-frame.core` can't carry source-meta

CLJS does not support per-instance metadata on function values. Even if it did, re-frame's `reg-event-db` wraps the user's handler in a closure and stores the wrapper, not the user's fn:

```clojure
(reg-event-db :counter/inc (fn [db _] ...))
;; calls db-handler->interceptor — std_interceptors.cljc:73
;; which returns:
(->interceptor
  :id     :db-handler
  :before (fn db-handler-before [context] ...))   ;; user fn captured in closure
```

The registrar holds the interceptor chain. `(:before terminal-interceptor)` is `db-handler-before` — re-frame's wrapper, with file/line pointing at `std_interceptors.cljc`, not the user's call site. Same pattern for `reg-event-fx` (`fx-handler->interceptor`) and `reg-event-ctx` (`ctx-handler->interceptor`). For `reg-sub` and `reg-fx` the stored value *is* the user's fn — but those are typically anonymous `(fn [...] ...)` expressions which CLJS fns cannot carry meta on at all.

So: the metadata the operator wants — *where in their codebase did they call `reg-*`?* — is not in the registrar regardless of compile flags. A macro mirror is the only point in the pipeline where the call site can be seen and stamped.

## Paths that don't work

**shadow-cljs flags.** Surveyed for completeness — all confirmed irrelevant:

| Flag | Effect | Helps? |
|---|---|---|
| `:source-map true` (default in dev) | JS-to-CLJS source-map files emitted | No — source-maps describe vars; the registrar holds fn values |
| `:closure-defines goog.DEBUG true` | Already set in dev | No — surfaces nothing extra on registered fns |
| `:fn-invoke-direct`, `:elide-asserts`, etc. | Inlining / build tweaks | No — orthogonal |

There is no compiler option that reaches into a closure and exposes a captured value with its source meta. Such an option would require source-instrumenting every `(fn ...)` form in the build to attach a side-channel of metadata to its returned object — a non-trivial CLJS compiler change, not a knob.

**Source-map readback.** Theoretically possible, practically unreliable:

- The `.toString` surface IS re-frame's wrapper. The user's fn is a captured variable inside; it doesn't appear in the wrapper's JS output unless it has a top-level binding (it usually doesn't).
- Even with the user's fn body visible, matching pretty-printed JS against source-map entries is fragile — minification, identifier munging, multi-statement bodies on one line. CLJS source-maps are designed for stack-trace decoding (precise line/column query), not structural string-match.
- Anonymous `(fn [db _] ...)` provides no name to anchor on.

A spike on this would produce a heuristic that works on toy fixtures and breaks on real apps.

## Probe transcript — fixture without the swap

Run against the live fixture before swapping `:require [re-frame.core :as rf]` to `:require [re-frame.core-instrumented :as rf]`:

```
$ scripts/eval-cljs.sh '(meta (re-frame.registrar/get-handler :event :counter/inc))'
{:ok? true, :value nil}

$ scripts/eval-cljs.sh '(meta (last (re-frame.registrar/get-handler :event :counter/inc)))'
{:ok? true, :value nil}

$ scripts/eval-cljs.sh '(meta (:before (last (re-frame.registrar/get-handler :event :counter/inc))))'
{:ok? true, :value nil}

$ scripts/eval-cljs.sh '(.-name (:before (last (re-frame.registrar/get-handler :event :counter/inc))))'
{:ok? true, :value "re_frame$std_interceptors$db_handler__GT_interceptor_$_db_handler_before"}

$ scripts/handler-source.sh :event :counter/inc
{:ok? false, :reason :no-source-meta, :kind :event, :id :counter/inc}
```

For comparison, var-meta on a top-level `defn` *does* round-trip, confirming shadow-cljs preserves source-map metadata where it can — on **vars**, not on **fn values**:

```
$ scripts/eval-cljs.sh '(do (defn test-fn-3 [] :hi) (meta (var test-fn-3)))'
{:ok? true, :value {:ns cljs.user, :name test-fn-3, :file "...", :line 1, :column 5, ...}}
```
