# Form-by-form trace via re-frame-debux

Recipes for instrumenting a handler / sub / fx — or a single expression inside one — with [re-frame-debux](https://github.com/day8/re-frame-debux), driven via the REPL with no source edits and no recompile. The per-form trace records flow through `re-frame.trace/merge-trace!` into the same epoch buffer the skill already reads, surfaced as `:debux/code` on each coerced epoch.

These recipes are referenced from [`SKILL.md`](../../SKILL.md) but live here so SKILL.md stays focused on the day-to-day vocabulary.

## Prerequisite

`day8.re-frame/tracing` must be on the classpath. If it isn't, ask the user to add it to dev deps; you can't conjure macros that aren't loaded.

## "Trace a handler / sub / fx form-by-form"

When the user wants to see what each *expression inside* a handler evaluated to — not just the inputs and outputs of the handler as a whole.

### Pick the granularity

- **A single expression** — wrap a let-binding RHS, a `->` step, or a one-off call with `dbg`. Lighter than wrapping the whole handler; doesn't require restoring anything. See *Trace a single expression at the REPL* below.
- **A whole handler / sub / fx** — wrap once, dispatch, read every form's value. Use the `wrap-handler!` / `fn-traced` procedure on this page.

### Detect which API is available

```
scripts/eval-cljs.sh '(re-frame-pair.runtime/debux-runtime-api?)'
```

`true` → use the runtime-API path (preferred). `false` → fall back to the manual `fn-traced` path further down.

### Procedure (runtime API — preferred, debux ≥ 4ed07c9)

`day8.re-frame.tracing.runtime/wrap-handler!` saves the original handler verbatim into a side-table and re-registers a `fn-traced`-wrapped version under the same id. `unwrap-handler!` restores from the side-table — no source-eval round trip, interceptor chain comes back intact.

1. **Wrap.** The macro takes `[kind id (fn [args] body)]`. Read the handler's body from source (or `scripts/handler-source.sh :event :foo/bar`) and pass it as a literal `fn`:

   ```
   scripts/eval-cljs.sh '(day8.re-frame.tracing.runtime/wrap-handler!
                           :event :cart/apply-coupon
                           (fn [db [_ code]]
                             (-> db
                                 (assoc-in [:cart :coupon] code)
                                 (assoc-in [:cart :coupon-status] :applied))))'
   ```

   `kind` dispatches the matching `reg-event-db` / `reg-sub` / `reg-fx`, so you don't have to match the registration form yourself. Use `wrap-event-fx!` / `wrap-event-ctx!` for events that need the fx- or ctx-shaped interceptor chain. `wrap-sub!` and `wrap-fx!` are direct aliases for the `:sub` and `:fx` cases when that reads better.

   For a subscription, copy the computation fn's shape from source: first arg is the input signal value (or vector of input signal values), second arg is the query vector, and the body returns the derived value:

   ```
   scripts/eval-cljs.sh '(day8.re-frame.tracing.runtime/wrap-sub!
                           :cart/visible-items
                           (fn [items [_ filter-id]]
                             (->> items
                                  (filter #(= filter-id (:status %)))
                                  vec)))'
   ```

   For an fx handler, copy the original single-arg body. The arg is the effect value from the event's effects map:

   ```
   scripts/eval-cljs.sh '(day8.re-frame.tracing.runtime/wrap-fx!
                           :local-store/set!
                           (fn [{:keys [key value]}]
                             (.setItem js/localStorage (name key) (pr-str value))))'
   ```

2. **Dispatch with `--trace`:**

   Use the path that actually runs the wrapped code: dispatch the event itself for `:event`, dispatch the event that returns the effect for `:fx`, and dispatch or render the path that derefs the subscription for `:sub`.

   ```
   scripts/dispatch.sh --trace '[:cart/apply-coupon "SPRING25"]'
   ```

3. **Read `:debux/code`** off the returned epoch. Each entry has `{:form, :result, :indent-level, :syntax-order, :num-seen}` — the form text (post `tidy-macroexpanded-form`), the value it evaluated to, nesting depth, and evaluation order. Walk inner-to-outer to see what each sub-form produced.

   Note: `dispatch.sh --trace`'s settle response trims `:debux/code` from the awaitable epoch (fn refs in the trace records don't survive the cljs-eval edn boundary). Recover it via `(re-frame-pair.runtime/epoch-by-id <id>)` after the dispatch settles.

4. **Unwrap** to restore:

   ```
   scripts/eval-cljs.sh '(day8.re-frame.tracing.runtime/unwrap-handler! :event :cart/apply-coupon)'
   ```

   Returns `true` if a wrap was found and undone, `false` if `[kind id]` wasn't wrapped (no-op). `unwrap-sub!` and `unwrap-fx!` are direct aliases for `(unwrap-handler! :sub id)` and `(unwrap-handler! :fx id)`. Always pair wrap with unwrap in the same REPL turn.

### Procedure (manual fn-traced — fallback for debux < 4ed07c9)

The runtime API is a thin wrapper over the same `fn-traced` macro; you can still drive it by hand:

1. **Look up the handler** so you can restore it later. CLJS fn values don't pretty-print, but the registrar's stored value plus the original `reg-event-db` form in the user's source is enough to restore.

   ```
   scripts/eval-cljs.sh '(re-frame.registrar/get-handler :event :cart/apply-coupon)'
   ```

2. **Wrap and re-register** with `fn-traced`. Match the original arity AND registration kind (`reg-event-db` / `reg-event-fx` / `reg-event-ctx`):

   ```
   scripts/eval-cljs.sh '(re-frame.core/reg-event-db
                           :cart/apply-coupon
                           (day8.re-frame.tracing/fn-traced [db [_ code]]
                             (-> db
                                 (assoc-in [:cart :coupon] code)
                                 (assoc-in [:cart :coupon-status] :applied))))'
   ```

3. **Dispatch with `--trace`** and read `:debux/code` off the returned epoch (same as step 2-3 of the runtime-API path).

4. **Restore.** Re-eval the original `reg-event-db` form from the user's source. If their source isn't accessible from the REPL, ask the user to hot-reload (saving any source file in the same namespace re-evaluates the original `reg-event-db`).

### Limits to call out to the user

- **Classpath only.** This recipe needs `day8.re-frame/tracing` already loaded. If it isn't, fall back to `repl/eval` with manual `tap>` probes around the handler body.
- **`reg-*` / var-backed handlers only.** Handlers that were inlined into other fns at compile time can't be traced this way — wrapping operates on *registration*, not on previously compiled call sites.
- **Body has to be a literal `(fn ...)`.** `fn-traced` operates on the AST at compile time; you cannot pass an already-compiled fn value. Both paths require the body to be a literal `fn` form at REPL-eval time.
- **Same-shape arity.** The wrapped form has to match the original handler's argument shape (`[db ev]` for `reg-event-db`, `[ctx ev]` for `reg-event-fx`, etc.). Look up `registrar/describe :event :foo/bar` first to confirm — `:reg-event-db` vs `:reg-event-fx` lives in the response's `:kind`.
- **Restore is critical.** A wrapped handler stays wrapped for the rest of the REPL session (until full page reload). Always pair wrap with unwrap (or the manual restore) in the same turn.

This recipe is the on-demand half of the integration. The bridge half — surfacing `:code` as `:debux/code` on each coerced epoch — is automatic.

## "Trace a single expression at the REPL"

When the form you want instrumented is a single expression — a let-binding's RHS, a `->` thread step, an inner `(some-fn args)` — and wrapping the whole handler is overkill. `dbg` (`day8.re-frame.tracing/dbg`) emits one trace record per evaluation: the quoted form, its result, and any opt extras (`:name` / `:locals` / `:if` / `:tap?`). Inside a re-frame event handler the trace lands on `:tags :code` (same surface as `fn-traced` — surfaces as `:debux/code` on the coerced epoch); outside any trace context it falls back to `tap>`.

### Detect availability

```
scripts/eval-cljs.sh '(re-frame-pair.runtime/dbg-macro-available?)'
```

`true` → debux ships `dbg`, the recipe below works. `false` → the host's debux is older; use the handler-level `fn-traced` path above instead.

### Procedure

1. **Identify the form** to instrument. Read the handler's source (`scripts/handler-source.sh :event :foo/bar` to locate, then your editor or the user's source).
2. **Hot-swap the handler with `dbg` wrapping the form of interest.** Same shape as the manual `fn-traced` path — `reg-event-db` re-eval'd with the body editor:

   ```
   scripts/eval-cljs.sh '(re-frame.core/reg-event-db
                           :cart/apply-coupon
                           (fn [db [_ code]]
                             (-> db
                                 (assoc-in [:cart :coupon]
                                           (day8.re-frame.tracing/dbg
                                             (normalize-coupon code)
                                             {:name "normalize"}))
                                 (assoc-in [:cart :coupon-status] :applied))))'
   ```
3. **Dispatch with `--trace`** and read the resulting epoch's `:debux/code` — the entry whose `:name` is `"normalize"` is your one form's trace; surrounding entries (if the handler also includes other `dbg` calls) are the others.
4. **Restore.** Re-eval the original `reg-event-db` form (or ask the user to hot-reload the source file).

### Out-of-trace use

`dbg` works at the bare REPL too — call it on any expression and the result surfaces via `tap>`:

```
scripts/eval-cljs.sh '(do (add-tap (fn [v] (.log js/console (pr-str v))))
                          (day8.re-frame.tracing/dbg (some-pure-calculation 42))
                          :ok)'
```

The `tap>` payload carries `:debux/dbg true` so a custom tap fn can branch on it.

### Why this over fn-traced

`dbg` has no AST-walk — it instruments exactly the expression you point at, no more. When the user's question is "what did *this specific call* return?" it's faster, lower-noise, and doesn't require pairing with `unwrap-handler!` / re-eval. For "show me every form in this handler," prefer the handler-level recipe above; for "this one let-binding," prefer `dbg`.

### Limits

- **`day8.re-frame/tracing` ≥ rfd-btn.** Older debux releases don't ship `dbg`; check `dbg-macro-available?` first.
- **Hot-swap still required.** `dbg` instruments at macro-expansion time, so the form has to be re-eval'd through the REPL with the `dbg` wrap in place. You can't retroactively `dbg` a form that's already compiled.
- **`:locals` is caller-supplied.** `dbg` can't introspect `&env` portably across CLJ/CLJS the way `fn-traced` does at function-arg time. If you want locals captured, pass them explicitly: `(dbg form {:locals [['db db] ['x x]]})`.

## Tracing options — reducing noise

Both recipes above accept a family of options on `fn-traced` / `defn-traced` / `dbg` / `dbgn` for filtering what lands on `:debux/code`. Shared across the macros (some have alias short forms): pass as a map after the body for `dbg`, as a metadata-style map directly inside `fn-traced` per debux's docs.

| Option | Short | Purpose | When to reach for it |
|---|---|---|---|
| `:once` | `:o` | Suppress consecutive emissions whose `(form, result)` pair matches the previous one. Per call-site identity (gensym'd at expansion); state survives across handler invocations until the result actually changes. | High-frequency dispatches where the user only wants to see what's *new*. |
| `:final` | `:f` | Emit only the outermost (indent-level 0) `:code` entry per top-level wrapping form. Every nested per-form entry is suppressed. | "Show me what each handler-body expression evaluated to as a whole" — skip the per-step zipper trace. |
| `:msg` | `:m` | Attach a developer-supplied label to each emitted `:code` entry. Per-call dynamic — the value is evaluated at trace time, not macroexpansion. | Distinguish output from many parallel call sites. |
| `:verbose` | `:show-all` | Wrap leaf literals (numbers, strings, booleans, keywords, chars, nil) the default zipper walker skips. `:skip-form-itself-type` (`recur` / `throw` / `var` / `quote` / `catch` / `finally`) STAYS honoured because instrumenting them corrupts evaluation semantics. | When the handler's logic hinges on a literal that the default elision skips. |
| `:if` | — | Guard predicate. Emit only when the predicate is truthy at trace time. | Conditional tracing — e.g. only emit when an argument matches a shape the user is debugging. Composes with all the others. |

### Examples

```
;; dbg with :once and :msg — only emit when the result changes,
;; tagged with a label so multi-site output stays distinguishable.
scripts/eval-cljs.sh '(day8.re-frame.tracing/dbg
                         (normalize-coupon code)
                         {:once true :msg "in cart-handler"})'

;; fn-traced with :final — only the outer per-expression results,
;; not the inner zipper walk.
scripts/eval-cljs.sh '(re-frame.core/reg-event-db
                         :cart/apply-coupon
                         (day8.re-frame.tracing/fn-traced [db [_ code]]
                           {:final true}
                           (-> db
                               (assoc-in [:cart :coupon] code)
                               (assoc-in [:cart :coupon-status] :applied))))'

;; dbg with :if — only emit when the input is non-empty.
scripts/eval-cljs.sh '(day8.re-frame.tracing/dbg
                         (normalize-coupon code)
                         {:if (seq code)})'
```

### Resetting `:once` dedup state

`:once` keeps a per-call-site memory of the last `(form, result)` pair so identical re-runs are silenced. When iterating in a hot REPL session it can hide changes you actually want to see — the public reset is `(day8.re-frame.tracing/reset-once-state!)`. No args; clears every `:once` site at once. Re-introduced as a public re-export in re-frame-debux's rfd-0mj — older builds expose it under `day8.re-frame.debux.common.util/-reset-once-state!`.

```
scripts/eval-cljs.sh '(day8.re-frame.tracing/reset-once-state!)'
```
