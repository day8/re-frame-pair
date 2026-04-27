# Inspirations — debux / re-frame-debux

A survey of [philoskim/debux](https://github.com/philoskim/debux) and the
day8 fork [re-frame-debux](https://github.com/day8/re-frame-debux) (cloned
locally at `/home/mike/code/re-frame-debux`), and what — if anything —
re-frame-pair should take from them.

> **Implementation status (2026-04-27).** All actionable items in this
> survey have shipped. The doc is retained as the design rationale for
> the integration; treat the per-section sketches as historical record.
>
> **Phase 1 — bridge + recipe + dedupe** (`rfp-hjj`, commits `3c3c8cd`,
> `09e30ec`, `29cf2f6`, `3568c11`; README mention `09a9551`):
>
> - §3b bridge: `coerce-epoch` surfaces `:debux/code` from `:tags :code`
>   (conditional-free; absent → `nil`).
> - §3.0 recipe: SKILL.md *Trace a handler/sub/fx form-by-form* — REPL-driven
>   `fn-traced` wrap → dispatch → read `:debux/code` → restore.
> - §3c dedupe: `watch-epochs.sh --dedupe-by :event` ships debux's `:once`
>   semantics for the live-watch path.
>
> **Phase 2 — `wrap-handler!` / `unwrap-handler!` runtime API**
> (re-frame-debux `4ed07c9`, rfd-8g9; consumed in re-frame-pair as
> `rfp-6z2` commit `a285c1d`, then refined to probe upstream's dedicated
> `runtime-api?` var via `ci-hpg` Phase 2 commit `c498dc7`). The §3.0
> recipe in `SKILL.md` now branches on `runtime/debux-runtime-api?`,
> taking the runtime-API path when available and falling back to the
> manual `fn-traced` rewrite for older debux releases.
>
> **§3.0 single-expression variant — `dbg` / `dbgn`** (`rfp-12p`, commit
> `c81234a`; upstream-side `rfd-btn` provides the macro). SKILL.md gained
> a separate *Trace a single expression at the REPL* recipe; runtime
> probe `dbg-macro-available?` mirrors `debux-runtime-api?`'s shape so
> the detect-and-branch pattern is consistent between both granularities.
>
> **§3a fallback (`repl/spy-form` for when debux isn't on the classpath)
> — deliberately not pursued.** The runtime probes `debux-runtime-api?`
> / `dbg-macro-available?` plus the existing `repl/eval` escape hatch
> covers the "no debux loaded" case adequately; the SKILL.md recipes
> already prompt the user to add `day8.re-frame/tracing` to dev deps if
> not present. A from-scratch `spy-form` op would duplicate
> `tagged-dispatch-sync!` plumbing without earning its keep.

## 1. What debux / re-frame-debux are

**debux** is a *code-instrumentation* library. Sprinkle macros into
source — `dbg` / `dbgn` / `dbgt` (cljs `clog` / `clogn` / `clogt`) —
and at eval time each tagged form prints itself and its result. `dbg`
traces the wrapping form; `dbgn` recursively rewrites every nested
sub-form via a zipper-based AST walk that respects `let` / `loop` /
`for` / threading macros; `dbgt` specialises for transducers. Options
include `:locals` (capture bindings), `:if` (conditional emit),
`:once` (suppress duplicates), `:style`, `:final`, plus
`set-print-length!` and `tap>` integration.

**re-frame-debux** is a deep fork that reuses the zipper machinery but
replaces console output with a re-frame-specific sink. It exposes two
macros — `day8.re-frame.tracing/fn-traced` and `defn-traced` (swap-in
replacements for `fn` / `defn`) — and instead of `println`, its
`send-trace!` calls `re-frame.trace/merge-trace!`, attaching
form/result/indent/syntax-order to the `:code` tag of the currently
running re-frame trace event. That payload surfaces in **re-frame-10x**'s
"Code" panel. Production builds swap to `day8.re-frame/tracing-stubs`
(or the `:ns-aliases` shadow-cljs trick) so the macros expand to plain
`fn` / `defn` with zero cost.

## 2. The architectural divergence — and where it dissolves

Read naively: debux instruments *code* (edit source, recompile, trace
at evaluation time); re-frame-pair observes the *runtime* (read 10x's
epoch buffer, never edit source). Framed that way, both are needed.

**That framing assumes debux requires source edits.** It doesn't, for
re-frame-pair: SKILL.md's "Cardinal rule" makes REPL hot-swap a
first-class primitive (the *Write* table lists `reg-event` / `reg-sub`
/ `reg-fx` as ephemeral redefinitions via `eval-cljs.sh`). Given
`day8.re-frame/tracing` (or debux) on the classpath at REPL eval time,
the skill can wrap a handler in `fn-traced` *at the REPL*, re-register
it, dispatch, observe the trace flow through `merge-trace!` into
`:tags :code` (surfaced as `:debux/code` per §3b), and restore.

Two narrow constraints survive: the macro must already be on the
classpath (we can't conjure macros that aren't loaded), and we can't
retroactively trace bodies whose macroexpansion already happened at
compile time — wrapping operates on the *registration*, not on
previously compiled call sites. Within those limits, "neither can
replace the other" is too strong. The skill can drive debux *as a
runtime instrumentation engine* on demand — see §3.0.

## 3. Worth borrowing — concrete ideas

### 3.0. On-demand REPL-driven debux instrumentation (load-bearing)

The biggest unlock. Rather than asking the user to add `fn-traced` in
source and recompile, the skill performs the wrap *in the running
runtime* via the REPL channel it already uses for ephemeral hot-swap.

Procedure for a handler with id `:foo/bar`:

1. **Look up.** `(re-frame.registrar/get-handler :event :foo/bar)`
   returns the registered fn (also via `registrar/describe`). Capture
   for restore.
2. **Wrap and re-register** through `eval-cljs.sh`:
   ```clojure
   (re-frame.core/reg-event-db :foo/bar
     (day8.re-frame.tracing/fn-traced [db ev] <body>))
   ```
   `fn-traced` expands at REPL eval time into a `merge-trace!`-aware
   body.
3. **Dispatch** (`dispatch.sh --trace`); the wrapped body emits
   per-form trace into the epoch's `:tags :code`.
4. **Read the epoch.** With the §3b bridge in place, `:debux/code`
   surfaces directly — same payload re-frame-10x's "Code" panel shows.
5. **Diagnose, then restore** by re-registering the original fn (step
   1's capture) or re-eval'ing the namespace's `reg-event-db` form.

Same shape works for `reg-sub`, `reg-fx`, and plain `defn` view fns
(redefine the var). View fns lose Reagent component identity on var
redef so expect a remount; handlers and subs swap invisibly.

**Single-expression variant (rfd-btn).** When the form of interest
is one expression rather than a whole handler, `(day8.re-frame.tracing/dbg
form opts?)` instruments just that form. Same `:code` sink — `dbg`
uses `merge-trace!` exactly like `fn-traced` does, so the trace
surfaces as `:debux/code` on the epoch. The procedure compresses to:
re-eval `reg-event-db` with `dbg` wrapping the target expression,
dispatch, read `:debux/code`, restore the unwrapped registration.
Outside any active trace, `dbg` falls back to `tap>` (with
`:debux/dbg true` on the payload) so the same call works at the bare
REPL. See SKILL.md's "Trace a single expression at the REPL" for
the full recipe and `re-frame-pair.runtime/dbg-macro-available?` for
the runtime feature probe (older debux releases don't ship `dbg`).

**Limits:**

- **Classpath.** `day8.re-frame/tracing` (or debux) must be loaded.
  If not, ask the user to add it, or pull it in via a shadow-cljs
  runtime require — less clean, more powerful.
- **No retroactive macroexpansion.** Bodies already compiled as plain
  `fn` can't be `dbgn`'d after the fact. The wrap happens at
  *registration* time, which is why this works for `reg-*`/var-backed
  handlers but not for inlined call sites baked into other fns.
- **Restore is critical.** SKILL.md's Cardinal rule (REPL changes
  ephemeral, lost on full reload) is the safety net, but mid-session
  a wrapped handler stays wrapped. Always pair wrap with restore;
  prefer wrap-dispatch-restore in a single REPL turn.

Strictly more powerful than §3a — leverages debux's zipper machinery
via its public macro surface, no reimplementation.

### 3a. Per-form snapshots without debux on the classpath

When §3.0's prerequisite (tracing loaded) isn't met, a thin
REPL-driven analogue still gives partial coverage:

- New op `repl/spy-form` in `scripts/re_frame_pair/runtime.cljs`,
  taking a handler id and a quoted body fragment, re-running the
  handler against the most recent epoch's coeffects with `prn`-style
  taps inserted at the user-pointed position.
- Reuses `tagged-dispatch-sync!`, leans on `tap>`, no new dep. ~50 LOC.

Strictly weaker than §3.0's `fn-traced` wrap (user-pointed probes only,
no zipper-based AST walk), but a useful fallback.

### 3b. Bridge: surface the `:code` tag if re-frame-debux is loaded

Highest leverage by impact-to-LOC. `merge-trace!` writes
`{:tags {:code [...]}}` onto the trace event that becomes the epoch,
and `coerce-epoch` (`scripts/re_frame_pair/runtime.cljs` ~line 581-602)
already extracts `:event` / `:coeffects` / `:effects` /
`:interceptor-chain` from those tags. It just doesn't pull `:code`.
Two-line change:

```clojure
;; in the map returned by coerce-epoch
:debux/code (:code tags)
```

Any `fn-traced`-wrapped handler then surfaces its per-form trace via
`trace/last-epoch`, `trace/find-where`, `watch-epochs`, and the
post-mortem recipes — no new dispatch, no shim. SKILL.md note: *"if
the user has day8.re-frame/tracing on the classpath and used
`fn-traced`, `:debux/code` is the per-form trace."*

### 3c. `:once/:o` semantics for `watch/stream`

debux's `:once` suppresses duplicate consecutive emissions. Useful for
re-frame-pair's `watch-epochs.sh --stream` when the same event fires
many times — a `--dedupe-by :event` flag would silence noise and
match the cognitive model debux users already have.

## 4. Already covered

re-frame-pair has analogues, usually at runtime granularity:

- **Timing.** `:time-ms` per epoch (SKILL.md *Trace*;
  `watch-epochs.sh --timing-ms '>100'`).
- **Conditional / on-change tracing.** `watch/stream` predicates
  (`--event-id-prefix`, `--touches-path`, `--effects`) — event-level,
  coarser than debux's per-form `:if`, but composable.
- **Function-call timeline.** `:interceptor-chain` overlaps `dbgn`'s
  indent levels at per-event resolution — right for the six-dominoes
  story.
- **Source-location grounding.** `dom/source-at` (re-com `:src`) and
  `handler/source`.

## 5. Doesn't fit

- **`dbg` / `clog` / `dbgt` and styled console.** Pure source-level
  instrumentation — orthogonal to a runtime observation skill.
- **`:locals` capture.** REPL equivalent is "eval an expression in
  the handler's scope" — `repl/eval`.
- **`set-cljs-devtools!` formatters / `:js` / `:style`.** Console
  presentation; re-frame-pair returns edn to an LLM.
- **`break` (DevTools breakpoint).** Halts the runtime; pair
  programming via REPL doesn't want that.

## 6. Integration shape — recommendation

**Bridge + recipe (with a small borrow as fallback):**

1. **Bridge** — surface `:code` from `coerce-epoch` as `:debux/code`,
   *zero coupling* (absent → `nil`). ~5 LOC.
2. **Recipe** — SKILL.md procedure for on-demand REPL-driven
   `fn-traced` wrapping (§3.0). Drives debux as a runtime
   instrumentation engine without source edits. Paired with #1,
   the user gets `dbgn`-quality per-form trace on demand.
3. **Borrow (fallback)** — `repl/spy-form` (§3a) for when tracing
   isn't on the classpath.
4. **Recommend** — SKILL.md pointer for users who prefer source-edit
   workflow: add `day8.re-frame/tracing`, wrap with `fn-traced`,
   `:debux/code` shows up automatically.

`merge-trace!` was a prescient choice — debux already writes into the
channel re-frame-pair reads from. The recipe (#2) leverages debux's
zipper engine via its public macro surface; we get the capability
without owning ~600 LOC of zipper code. Without #2, #1 only surfaces
traces for handlers pre-wrapped in source — the recipe is what makes
the bridge load-bearing for ad-hoc debugging.

## 7. Top 3 actionable takeaways (ranked by leverage)

1. **Surface `:code` in `coerce-epoch`** as `:debux/code`. Two-line
   change in `scripts/re_frame_pair/runtime.cljs` ~line 581-602,
   conditional-free (`nil` when debux isn't installed). Document in
   SKILL.md *Trace* table. **Highest leverage by impact-to-LOC.**
2. **SKILL.md recipe for on-demand REPL-driven `fn-traced` wrapping**
   (§3.0). Look up the handler/sub/fx, re-register a
   `fn-traced`-wrapped version via `eval-cljs.sh`, dispatch, read
   `:debux/code`, restore. **The biggest qualitative gain in this
   doc** — dissolves what looked like an architectural gap.
3. **Fallback for when debux isn't loaded.** Either keep
   `repl/spy-form` (§3a) as the no-dependency path, or add a one-line
   REPL form to pull `day8.re-frame/tracing` into the running runtime
   via shadow-cljs's require — less clean, more powerful. Pick based
   on whether the skill should assume a fixed classpath or reach for
   tracing on demand.

## 8. See also

A complementary survey lives in the **re-frame-debux** repo at
`~/code/re-frame-debux/docs/improvement-plan.md` (committed locally
as `20ac8f4`). It triages re-frame-debux's current open issues,
identifies feature gaps vs philoskim/debux, and proposes a
prioritised roadmap for the upstream library. Three recommendations
in that doc directly amplify the §3.0 on-demand-REPL recipe:

- **Document the `:code` payload schema** — pins down the exact
  shape re-frame-pair surfaces as `:debux/code`, so consumers can
  rely on it.
- **Loud-fail when tracing is accidentally on in production** —
  defensive; protects the recipe from leaking expensive
  instrumentation past debug sessions.
- **Expose `wrap-handler!` / `unwrap-handler!` runtime APIs** —
  load-bearing for re-frame-pair: lets the on-demand recipe
  hot-swap a `fn-traced` wrap WITHOUT re-frame-pair synthesising
  macro forms at the REPL. Shifts the macro-expansion concern from
  the skill to the library, where it belongs. If/when re-frame-debux
  ships these, §3.0's recipe gets dramatically simpler.

Read the full plan there before opening upstream issues against
re-frame-debux — it has the maintainer-side context this doc
deliberately doesn't.
