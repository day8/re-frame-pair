# re-frame-pair — Initial Specification

**Status:** Draft 1
**Date:** 2026-04-19
**Owner:** mike.thompson@day8.com.au

---

## 1. Purpose

`re-frame-pair` is a Claude Code Skill (and Plugin) that lets Claude act as a pair programmer for a **live, running re-frame application**. It attaches to the application's runtime via shadow-cljs nREPL and exposes a small set of operations that map directly onto re-frame's primitives: `app-db`, events, subscriptions, effects, interceptors. Source files can also be edited; changes are coordinated with shadow-cljs hot-reload (§4.5).

### Why this shape

re-frame is a reactive dataflow system — a DAG of derived values rooted in mutable state. `app-db` is the single source of truth; events are the only legal writes; subscriptions recompute as derived values; views re-render when their subs change. A coding agent that only edits `.cljs` files works against the static shape of that system and has no view of its dynamics at runtime.

re-frame-pair inverts this. It operates on the live browser runtime *and* on source files — but deliberately, with a protocol: REPL changes are ephemeral probes; source edits are committed changes coordinated with shadow-cljs hot-reload (§4.5). Every read and write runs through re-frame's own vocabulary, so the data loop, the trace buffer, and the user's own instincts about the app all see the same thing Claude sees.

### Non-goals

- Not a replacement for `re-frame-10x`. 10x is a human-facing devtool; re-frame-pair is an agent-facing back-channel that reads from 10x's already-collected state (see §3.2).
- Not a test runner, linter, or static analysis tool. Those operate on source; re-frame-pair operates on runtime.
- Not a production feature. Dev/debug only.

### Assumed stack

re-frame-pair targets projects **already configured for re-frame-10x with tracing enabled**. Concretely, the host project must have:

- **re-frame** as a dep — the subject.
- **re-frame-10x** as a dev dep, added to shadow-cljs `:preloads`, with `re-frame.trace.trace-enabled?` set to `true` via `:closure-defines`. (These are 10x's own requirements, not re-frame-pair's.) 10x provides the trace/epoch substrate re-frame-pair reads from.
- **re-com** as a dep, **with debug instrumentation enabled in dev** (re-com's config flag). When components are called with `:src (at)`, re-com's debug path renders `data-rc-src` onto DOM elements. Render traces name `re-com.*` components so re-frame-pair can apply component-aware recipes (categorise layout vs input vs content renders, reason about `:on-change` / `:parts` idioms). Without debug-enabled re-com and `:src (at)` at call sites, the DOM bridge (§4.3b) degrades gracefully but loses its code-to-runtime link.

Re-frame-pair itself contributes **zero** additional host-project configuration — no extra deps, no extra preloads, no extra closure-defines. The bar is that 10x and re-com are already wired the way their own docs require. Supporting non-10x or non-re-com projects is out of scope for v1.

### Terminology

- **Trace** refers to several related things — `re-frame.trace` (the library), 10x's collected *trace buffer*, individual *trace events* emitted via `register-trace-cb`, and the skill's `trace/*` ops which read from 10x's buffer. Where ambiguous, the spec qualifies.
- **Epoch** = everything that happened in response to one `dispatch` — the event vector, interceptor chain, coeffects, effects map, `app-db` diff, subs that ran, views that re-rendered. The unit of trace Claude reasons about.
- **Session sentinel** = a UUID the skill interns on injection; its absence after a REPL lookup means the browser has refreshed and re-injection is needed.

---

## 2. Key concepts at a glance

- **Live runtime.** The browser JS runtime behind `shadow-cljs watch`.
- **Reactive graph.** re-frame's subscription signal graph (Layer 2 / Layer 3).
- **Root state.** `re-frame.db/app-db` — a single Reagent ratom.
- **Writes.** `dispatch`, `reg-event-*`, `reset!` of `app-db`.
- **Runtime introspection API.** `re-frame.trace/register-trace-cb`, `re-frame.registrar`, `re-frame.subs/query->reaction`, `re-frame.db/app-db`.
- **Connection mechanism.** nREPL → shadow-cljs → browser runtime.
- **Packaging.** `SKILL.md` + bash shim scripts.
- **Cardinal rule.** Two modes — REPL (ephemeral) vs source edit (permanent via hot-reload). See §3.

---

## 3. Architecture

**Cardinal rule.** Two modes of changing the app, one protocol:

- **REPL changes** (hot-swap a handler, evaluate a form) are *ephemeral* — lost on full page reload. Preferred for probes and experiments.
- **Source edits** are *permanent* and pass through shadow-cljs hot-reload. After any source edit, the skill must `hot-reload/wait` before dispatching, or it risks interacting with the pre-reload code.

Source edits are not forbidden — they're the right tool for committed changes. See §4.5 for the reload-coordination protocol.

### 3.1 Connection path

**Mechanism:** shadow-cljs nREPL into the connected browser runtime. Assumed as the dev-time build tool — no fallback for non-shadow projects in v1.

Rationale:
- Every day8 dev workflow already has it, so zero new infrastructure.
- Full ClojureScript evaluation in the browser subsumes every inspection and mutation operation re-frame-pair needs.
- Textual edn in/out — easy to shell out to from bash scripts.

**CLJS REPL mode switch.** shadow-cljs nREPL connects clients to a JVM (Clojure) REPL by default; to evaluate ClojureScript in the browser, the session must switch modes via `(shadow.cljs.devtools.api/repl <build-id>)` or equivalent. `eval-cljs.sh` issues the switch on first connect and keeps the session in `:cljs` mode thereafter. Every op in §4 assumes a CLJS-mode session.

Extension path for later: a dev-time websocket sidecar preload for targets without a shadow-cljs REPL (React Native, headless, production-like staging). Out of scope for v1.

### 3.2 re-frame-10x as the trace substrate

re-frame-pair **reads through re-frame-10x** rather than running its own trace callback. 10x is a required runtime dependency (see *Assumed stack*): it already registers a trace callback, groups raw trace events into *epochs* (one event and everything it caused), computes `app-db` diffs via `clojure.data/diff`, tracks which subs ran vs cache-hit, times each interceptor, and maintains a ring buffer of recent epochs. Adding a second trace callback in parallel would be wasteful and risk two disagreeing sources of truth.

Consequences:

- `trace/last-epoch`, `trace/epoch`, `trace/dispatch-and-collect`, and `trace/window` all read from 10x's epoch buffer.
- `app-db` diffs, sub-run records, interceptor timings, and render lists come from the structures 10x already built.

(`discover-app.sh` verifying 10x is loaded is handled as an error surface in §3.6.)

**Coupling.** 10x's internal data structures are not a public API. For v1 re-frame-pair absorbs that coupling directly — the ops layer translates 10x's epoch structs into the epoch record documented in §4.3a. If 10x internals churn, the fix lands in re-frame-pair, not in the skill vocabulary. Companion proposal A2 (see Appendix A) promotes a stable public namespace in 10x as a later hardening step.

### 3.3 Component layout (target)

*The layout below describes the target repository structure once implementation begins. At the time of this draft, the repo contains only the README and this spec — no scripts, no `SKILL.md`, no tests. Nothing here has been implemented.*


```
re-frame-pair/
├── .claude-plugin/
│   └── plugin.json                 # Claude Code Plugin manifest
├── SKILL.md                        # Skill body: name, description, allowed-tools, instructions
├── scripts/
│   ├── discover-app.sh             # locate running shadow-cljs build + nREPL port
│   ├── eval-cljs.sh                # send a ClojureScript form to the browser runtime, return edn
│   ├── inject-runtime.sh           # on connect: inject helpers + session sentinel over REPL
│   ├── runtime.cljs                # source-of-truth ClojureScript that inject-runtime.sh evaluates
│   ├── dispatch.sh                 # fire a re-frame event and return the resulting trace
│   ├── trace-recent.sh             # capture the next N trace events
│   ├── watch-epochs.sh             # long-running tail; reads epochs from nREPL :out stream (§4.4)
│   └── tail-build.sh               # tail `shadow-cljs watch` server output for "Build complete" (§4.5)
├── README.md
└── docs/
    └── initial-spec.md
```

`scripts/runtime.cljs` is a source file — not a library published to users. `inject-runtime.sh` reads it and sends its forms over nREPL. Nothing lands on the user's project disk.

### 3.4 Connection protocol

Installing re-frame-pair adds **no new host-project configuration beyond what 10x already requires** (see *Assumed stack*): re-frame-pair itself needs no additional deps, preloads, or closure-defines. On first connect (§3.1), the skill bootstraps the runtime via REPL forms:

1. `discover-app.sh` locates nREPL and attaches.
2. `inject-runtime.sh` creates the `re-frame-pair.runtime` namespace at runtime and interns helpers (`snapshot`, `trace-dispatch`, etc.) from `scripts/runtime.cljs`. Among those helpers is the **session sentinel** — `re-frame-pair.runtime/session-id`, a random UUID. On every subsequent op, the skill reads the session sentinel; if absent, a full page refresh has occurred (the new runtime doesn't have our injection) and the skill re-injects before proceeding. The nREPL session itself persists through refreshes — only the browser-side runtime object is renewed.

That's the full bootstrap. Per-op concerns — epoch streaming (§4.4), hot-reload confirmation (§4.5) — do their own work on demand. No trace callback is installed at connect or anywhere else; all epoch data comes from 10x's existing trace-cb and epoch buffer.

This is the only install path: one command on the Claude side, and whatever 10x + re-com already required on the app side.

### 3.5 Invariants

- **Two modes, one protocol.** See §3 *Cardinal rule* above.
- **Operations are scoped to dev builds.** On connect, `discover-app.sh` evaluates `re-frame.trace/trace-enabled?` over nREPL. If the value is false (production build), the skill refuses to connect with a diagnostic message naming the missing condition.
- **No silent state replacement.** `reset!` of `app-db` is allowed, but the skill surfaces it as an explicit, logged operation (so the developer sees what Claude changed).
- **Watches auto-terminate.** Background processes started by `watch/*` ops (§4.4) are session-scoped and stop on skill disconnect, session end, or the stopping condition defined at start time. They never persist across sessions, and there is no way to leave a silent tail running.

### 3.6 Error surfaces

Typed failures the skill returns rather than raises:

| Failure | Detection | Skill response |
|---|---|---|
| nREPL not running | `discover-app.sh` exits non-zero on socket connect | Report port absent; suggest starting the dev build |
| re-frame / 10x / re-com not loaded | `discover-app.sh` ns-resolve checks | One-line per missing ns; no connection until resolved |
| `trace-enabled?` is false | `discover-app.sh` eval check (§3.5) | Refuse connect, name the condition |
| Minimum version unmet | `discover-app.sh` version read (§3.7) | Refuse connect, name the dep + both versions |
| Handler throws during `dispatch-sync` | Captured via 10x's trace event | Epoch returned with `:handler/error {:message :stack}`; `app-db` is whatever the handler left it as before the throw |
| `repl/eval` raises | nREPL `:err` channel | Op returns `{:exception <class> :message :stack}` (not thrown client-side) |
| `trace/dispatch-and-collect` frame timeout | 1.5s default after `dispatch-sync` | Returns partial epoch with `:timed-out? true`; Claude flags that the animation frame never fired (browser may be paused, in another tab, etc.) |
| `watch-epochs.sh` nREPL disconnect | Socket EOF | Auto-reconnect; sentinel check triggers re-injection if needed; watch resumes |
| Browser tab closed / full refresh | Next op's sentinel check fails | Re-inject transparently, proceed |
| Connection permanently lost | Reconnect backoff exhausted | Ops return `{:connection :lost}` rather than hanging |

### 3.7 Versioning and compatibility

Minimum version *enforcement* is plumbed end-to-end (`runtime.cljs/version-report` + `ops.clj/version-failure`), but **floors are currently `nil` across the board** — enforcement is a no-op until the spike (§8a) confirms where each library exposes its version at runtime. `health` exposes `:versions.enforcement-live?` so callers can tell the difference between "no floor violation" and "not actually checking". Exact floors TBD on first release:

| Dep | Min (placeholder) | Why |
|---|---|---|
| re-frame | 1.4 | `register-trace-cb` semantics re-frame-pair relies on. Also: now exposes `re-frame.core/version` (commit `8cc973c`); floor enforcement plumbing-ready for re-frame specifically. |
| re-frame-10x | 1.9 | Epoch-buffer shape re-frame-pair reads. Public surface `day8.re-frame-10x.public` shipped at commit `4107f8f`; once a re-frame-10x runtime version constant lands, this row's floor can move to the public-ns version. |
| re-com | 2.20 | `:src (at)` / `data-rc-src` contract. `:re-com/render` trace tag ships in commits `b3912727` + `961b9215` — once the rfp consumer lands the floor here may bump. |
| shadow-cljs | 2.28 | nREPL + reload stability |

Versions are read via namespace-var lookup at connect. If any is below floor, the skill refuses with a specific message naming the dep and both versions (observed vs required).

### 3.8 Multi-runtime handling

shadow-cljs can have several connected browser runtimes on one build (multiple tabs, iframes, a mobile runtime). "Picks one" is fine for *reads*, but not fine when the op mutates — Claude dispatching `[:user/delete 42]` against a random tab is not what the user meant.

v0 behaviour:

- `discover-app.sh` enumerates connected runtimes via shadow's runtime registry. If exactly one is attached, it's used. If more than one is attached, `discover` reports the count and the runtime ids as a structured warning.
- **Read-only ops** (§4.1, `trace/*` reads, `dom/describe`, etc.) proceed against the first registered runtime. Safe because they can't change state.
- **Mutating ops** (`dispatch`, `reg-event` / `reg-sub` / `reg-fx` hot-swap, `app-db/reset`, `dom/fire-click-at-src`) **refuse** with `{:ok? false :reason :ambiguous-runtime :runtimes […]}` when multiple runtimes are attached. Claude reports this to the user and asks them to either close unneeded tabs or wait for `runtime/select`.
- `runtime/list` and `runtime/select <id>` are planned for Phase 1 (§6); until they land, the user's recourse for mutations on multi-tab builds is to close the extras.

This is a deliberately conservative default. Better to refuse than to mutate the wrong runtime silently.

---

## 4. Operations (skill verbs)

These are the ops `SKILL.md` teaches Claude. Each maps to one or two lines of evaluated ClojureScript. They fall into seven families:

| Family | Subsection | Purpose |
|---|---|---|
| Read | §4.1 | Inspect `app-db`, registrars, live subs, schema |
| Write | §4.2 | Dispatch events, hot-swap handlers, `app-db/reset` |
| Raw REPL | §4.2a | Arbitrary ClojureScript escape hatch |
| Trace | §4.3 | Read epochs from 10x's buffer; fire-and-capture |
| DOM bridge | §4.3b | Round-trip between `data-rc-src` on the DOM and source file:line |
| Live watch | §4.4 | Push-mode: stream matching epochs as they fire |
| Hot-reload | §4.5 | Coordinate source edits with shadow-cljs reload |
| Time-travel | §4.6 | 10x undo for experiment loops |
| Diagnostics | §4.7 | Named recipes composed from the above |

### 4.1 Read

| Op | Mapped form | Purpose |
|---|---|---|
| `app-db/snapshot` | `(deref re-frame.db/app-db)` | Full current state |
| `app-db/get` | `(get-in @re-frame.db/app-db path)` | Path-scoped read |
| `app-db/schema` | Convention: `(get @app-db :re-frame-pair/schema)` if the app registers a spec/malli schema via an `after` interceptor | Return the schema for `app-db`, or `nil` if the app hasn't opted in. Optional but lets diagnostics validate before hot-swap |
| `registrar/list` | `(keys (get-in @re-frame.registrar/kind->id->handler [kind]))` | Enumerate registered ids for `:event`, `:sub`, `:fx`, `:cofx` |
| `registrar/describe` | `(get-in @re-frame.registrar/kind->id->handler [kind id])` plus an interceptor-chain walk for `:event` kind | Return handler metadata. For `:event` kind: `{:kind :reg-event-db/:reg-event-fx, :interceptor-ids [...]}` — kind is inferred from the terminal interceptor's id (`:re-frame/db-handler` vs `:re-frame/fx-handler`). For `:sub` / `:fx` / `:cofx`: the handler is a plain function, so `:interceptor-ids` is absent. Source form included if Appendix A-A7 has landed (otherwise `:source :not-retained`). |
| `subs/live` | `(keys @re-frame.subs/query->reaction)` | Currently-subscribed query vectors |
| `subs/sample` | `@(rf/subscribe query-vec)` | Deref a subscription once, return value. `rf/subscribe` consults re-frame's `query->reaction` cache; eviction is tied to Reaction disposal, which is Reagent/re-frame internal and not a contract the spec should rely on timing-wise. Practically: a repeat call with the same `query-vec` may cache-hit while the Reaction is still live; outside a reactive context — where no component holds a subscription reference — behaviour is best-effort. For long-running observation prefer the watch mechanism (§4.4). |

### 4.1a re-com-aware view inspection

Because re-com is an assumed dep, render entries are classified by a `:re-com?` flag (component namespace starts with `re-com.`) and, where possible, a category (`:layout`, `:input`, `:content`). This lets recipes answer "which *inputs* re-rendered after this dispatch?" or "did a layout component above this input re-render unnecessarily?"

### 4.2 Write

| Op | Mapped form | Purpose |
|---|---|---|
| `dispatch` | `(rf/dispatch ev)` by default (queued, same path as app-native dispatches); `--sync` forces `(rf/dispatch-sync ev)` | Fire event |
| `reg-event` / `reg-sub` / `reg-fx` | Evaluate the `reg-*` form | Hot-swap handler |
| `app-db/reset` | `(reset! re-frame.db/app-db v)` | Direct state replacement (logged) |

**Dispatch mode default.** Plain `dispatch` is queued so Claude's dispatches land on the same event queue as the app's — no behavioural surprises. `trace/dispatch-and-collect` (§4.3) always uses `dispatch-sync` internally so before/after `app-db` diffs are deterministic.

**Claude-dispatch tagging.** Event vectors are positional and drop metadata, so tagging happens *out-of-band*. The skill's runtime maintains a set of epoch ids it dispatched during the current session — but only for `dispatch-sync`:

- For `dispatch-sync`, the epoch is appended to 10x's buffer *during* the synchronous call. Reading `latest-epoch-id` immediately after the call reliably identifies our epoch.
- For queued `dispatch`, the epoch appears asynchronously on a later tick. Anything reading the buffer between our enqueue and the handler running could see a user-originated event as "latest" and tag it incorrectly. So v1 does **not** tag queued dispatches — `tagged-dispatch!` returns `{:queued? true :epoch-id nil}` and the event simply doesn't enter the claude-dispatched set.

Consequence: `trace/last-claude-epoch` only sees epochs from `dispatch-sync` calls (and from `trace/dispatch-and-collect`, which uses sync internally). Queued dispatches are invisible to it. Callers who need to follow a queued dispatch should use `trace/last-epoch` (origin-agnostic) and correlate by event vector themselves.

Companion proposal A6 (dispatch provenance in re-frame itself) is the eventual clean path for both sync and queued.

### 4.2a Raw REPL — `repl/eval`

Every op above is ultimately a short form passed to `eval-cljs.sh`. The `repl/eval` op exposes that primitive to Claude directly: send an arbitrary ClojureScript form to the connected browser runtime and return its edn result. This is the escape hatch for when a recipe doesn't fit.

```
repl/eval "(->> @re-frame.db/app-db :user/sessions vals (filter :active?) count)"
```

Guardrails:

- Same cardinal rule applies: no source-file writes, even if the form could conceptually `spit` something. The skill's instructions and the bash shim both refuse filesystem effects.
- Evaluated forms run with full app authority — they can mutate `app-db`, dispatch events, trigger effects. Claude is instructed to prefer the structured ops (§4.1–§4.3b) when one fits, and to reach for `repl/eval` only when the structured vocabulary is insufficient.
- Every `repl/eval` call is logged so the developer sees what Claude ran, matching the §3.5 invariant for `app-db/reset` and hot-swap.

### 4.3 Trace

All trace ops read from re-frame-10x's epoch buffer (see §3.2).

| Op | Source | Purpose |
|---|---|---|
| `trace/last-epoch` | 10x epoch buffer, head | Most recent epoch in the buffer regardless of origin — includes user clicks and Claude dispatches alike |
| `trace/last-claude-epoch` | 10x epoch buffer, filtered by session tag | Most recent epoch from a `dispatch-sync` (or `trace/dispatch-and-collect`) issued by this skill session. Does **not** include queued dispatches — see §4.2 *Claude-dispatch tagging* for why. |
| `trace/epoch` | 10x epoch buffer, by id | Same shape, for a named epoch |
| `trace/dispatch-and-collect` | Capture 10x's newest-epoch-id before dispatch; run `dispatch-sync` (its epoch appears immediately, synchronously); record the new head-id via the §4.2 tagging mechanism — that id is *ours* by construction, regardless of what other activity happens in the same frame; wait one animation frame so renders land; read the epoch by id | Fire event, return the epoch record |
| `trace/recent` | Tail N ms of 10x's buffer | Snapshot the epochs added in the last N ms (pull — contrast with `watch/*` push ops in §4.4) |

#### 4.3a Epoch record — the `trace/dispatch-and-collect` contract

Every trace op returns an *epoch* with the same shape. `trace/dispatch-and-collect` guarantees the following five questions are answerable from that record:

1. **How did `app-db` change?** (`:app-db/diff`)
2. **Which subs re-ran (vs cache-hit)?** (`:subs/ran` + `:subs/cache-hit`)
3. **Which views re-rendered, and where in the source?** (`:renders`)
4. **Which source line produced each rendered component?** (`:renders[].src` via re-com's `:src (at)`)
5. **What effects fired, including cascades?** (`:effects/fired`)

The full record shape:

```clojure
{:event            [...]                    ; the original dispatched event vector
 :coeffects        {...}                    ; inputs pulled in by interceptors
 :effects          {...}                    ; raw map returned by the handler
 :effects/fired    [{:fx-id :db                               ; (4) fx-handler invocations,
                     :value <new-db>                          ;     :fx flattened, in order
                     :time-ms ...}
                    {:fx-id :dispatch
                     :value [:cart/recompute-tax]
                     :epoch-id "epoch-42"                     ; link to cascaded epoch
                     :time-ms ...}
                    {:fx-id :dispatch-later                   ; from :fx vector — flattened
                     :value {:ms 500 :dispatch [:cart/autosave]}
                     :epoch-id nil                            ; future epoch, not yet in buffer
                     :time-ms ...}
                    {:fx-id :http-xhrio
                     :value {:uri "/coupon/validate" ...}
                     :time-ms ...} ...]
 :interceptor-chain [...]                   ; ordered, with before/after timings
 :app-db/diff      {:before ... :after ...  ; clojure.data/diff — (1) how app-db changed
                    :only-before ...
                    :only-after ...}
 :subs/ran         [{:query-v [...] :time-ms ...} ...]  ; (2) subs that recomputed
 :subs/cache-hit   [{:query-v [...]                    ; subs dereffed but cached
                     :subscribe/source {:file ... :line ...}} ...]
 :renders          [{:component "my.ns/foo"              ; (3) views that re-rendered
                     :time-ms   ...
                     :re-com?   true                      ; re-com component, if namespace matches
                     :src       {:file   "app/user_panel.cljs"
                                 :line   42
                                 :column 8}} ...]}        ; from re-com :src (at) — nil if absent
```

Fidelity notes:

1. **`app-db/diff`** — exact. Pre- and post-snapshots of `@re-frame.db/app-db` diffed with `clojure.data/diff`.
2. **`subs/ran` and `subs/cache-hit`** — exact when `re-frame.trace.trace-enabled?` is `true` in the build (the skill refuses to connect otherwise). Distinguishes recomputes (`:sub/run` trace events) from cache hits.
3. **`renders`** — component-granularity, captured from Reagent's `:render` trace events. Anonymous components appear with opaque names; library components that don't opt in to render tracing don't appear at all. Because renders happen on the animation frame **after** `dispatch-sync` returns, `trace/dispatch-and-collect` waits one frame before sampling — the caller sees a complete epoch, not a partial one.
4. **`:src`** — populated for re-com components that were called with `:src (at)`. v1 joins render entries to DOM by component name + recency (see §4.3b); when the re-com companion change lands (Appendix A, A3), the `:src` will be carried in the render trace itself and the DOM join becomes unnecessary.
5. **`:effects/fired`** — a derived view of the raw `:effects` map. `:fx` tuples are flattened into individual entries so Claude sees a flat list of fx-handler invocations in dispatch order. For each `:dispatch` / `:dispatch-later` / `:dispatch-n` entry, `:epoch-id` links to the cascaded child epoch when it exists in the buffer (immediately for synchronous dispatches; absent for not-yet-fired `:dispatch-later` and for effects whose handler is async, like `:http-xhrio`). This is the field to walk to answer "what cascaded from this event?". The raw `:effects` map is retained for round-trip fidelity.

#### 4.3b DOM ↔ source bridge (re-com `:src`)

**Prerequisites, both required.** (i) re-com's debug instrumentation must be enabled in the dev build (a config flag in `re-com.config`) — only the debug path attaches `data-rc-src` to DOM. (ii) Call sites must pass `:src (at)`; components without `:src` produce no attribute. If either prerequisite is missing, the DOM bridge degrades: `dom/source-at` returns `{:src nil :reason :missing-debug-or-src}`, and recipes flag the gap so Claude can suggest enabling debug or adding `:src (at)`.

When both are in place, re-com's debug path renders `:src` metadata onto DOM elements as `data-rc-src`, and re-frame-pair exposes a small op family that treats the DOM as a second, navigable surface.

| Op | Mapped form | Purpose |
|---|---|---|
| `dom/source-at` | `(.-dataset (js/document.querySelector sel))` → parse `rcSrc`; or `:last-clicked` resolves via the passive capture listener installed at connect (§3.4) | Given a CSS selector *or* the literal `:last-clicked`, return the `{:file :line :column}` that produced the element. The last-clicked path resolves via a document-level `click` listener installed on first connect. Returns `{:ok? false :reason :no-element}` with a hint if nothing has been clicked yet this session. |
| `dom/find-by-src` | `js/document.querySelectorAll "[data-rc-src*='file:line']"` | Given a source location, return live DOM elements produced there |
| `dom/fire-click-at-src` | Resolve via `dom/find-by-src`, synthesise click | Interact with a component by source location rather than by CSS path |
| `dom/describe` | Read `data-rc-src` + component props off the element | Inspect the live state of a specific rendered element |

**Joining renders to `:src` in v1.** re-com's `:src` is not yet carried in the Reagent render-trace event, so re-frame-pair's v1 adapter joins by component name and DOM recency: for each render trace entry with a re-com component, it queries live DOM matching the component name and picks the most recently mutated node's `data-rc-src`. Approximate but useful. The cleaner path is A3 (Appendix A).

**Graceful degradation.** When a specific call site omits `:src (at)` (debug is enabled but that particular component wasn't annotated), only that element's `:src` field is `nil` — the rest of the bridge keeps working. When re-com debug instrumentation is off entirely, `dom/*` ops uniformly return `{:src nil :reason :re-com-debug-disabled}` and Claude advises enabling it.

### 4.4 Live watch (push-mode)

Everything in §4.1–§4.3 is pull: Claude asks, re-frame-pair answers. `watch/*` is push: Claude asks re-frame-pair to report epochs **as they happen**, filtered by a predicate, either as a bounded batch or as a live stream.

**Mechanism (unproven — see §8a).** Push-mode streaming is the hardest piece of plumbing in this spec. The live-watch transport has two hard constraints to satisfy *together*:

1. **10x is the only trace substrate** (§3.2). No second `register-trace-cb`.
2. **Epochs arrive at Claude without re-polling** from the shell side. Streaming, not pull.

Streaming rides nREPL's `:out`, which is populated only during an active eval — once an eval returns, `*out*` unbinds from the session and async browser output lands in `js/console.log`. So the eval must not return for the duration of the watch. CLJS has no `<!!` (no blocking), so the eval cannot park-and-wait in the usual core.async way; it has to schedule with the JS event loop and keep returning control. The sketch below uses `js/setInterval` for cadence and reads 10x's buffer directly — no second callback:

```clojure
;; design sketch — transport not yet validated; see §8a spike item 3
(let [last-id (atom (rfp-runtime/latest-epoch-id))
      stop?   (atom false)
      tick    (fn []
                (when-not @stop?
                  (let [new (rfp-runtime/epochs-since @last-id)]
                    (when (seq new)
                      (reset! last-id (:id (last new)))
                      (doseq [e new :when (predicate? e)]
                        (prn e))))))   ; does this actually reach nREPL :out? — to be proven
      iv      (js/setInterval tick 16)]
  ;; how the outer eval stays alive long enough for `prn`s to stream
  ;; back is the open question — one candidate: return a Promise that
  ;; resolves on watch/stop, if shadow's nREPL awaits the Promise before
  ;; unbinding *out*. Alternative transports are listed below.
  iv)
```

The fallback if streaming-via-`:out` can't be made to work reliably is **pull-mode**: `watch-epochs.sh` repeatedly invokes a short eval (every ~100ms) that reads `rfp-runtime/epochs-since last-id` and prints matches. Each eval returns promptly; no long-lived `*out*` binding needed. Higher latency, more REPL chatter, but it's built on the same primitive every other op uses and removes the unproven piece. If the spike shows streaming-via-`:out` works, we keep it; if not, pull-mode is the fallback.

Either way, `scripts/watch-epochs.sh` reads the line stream and forwards to Claude via the Monitor tool. Each line becomes a notification turn where Claude can narrate, summarise, or stay silent.

| Op | Mode | Stopping condition |
|---|---|---|
| `watch/window` | Bounded window | After N seconds, return a batch report |
| `watch/count` | Bounded count | After M matching epochs, return a batch report |
| `watch/stream` | Live streaming | Runs until disconnect, idle-timeout, or explicit `watch/stop` |
| `watch/stop` | — | Terminates any active watch for this session |

**Predicates** (any combination, AND-ed):

- `--event-id <kw>` / `--event-id-prefix <kw>`
- `--effects <fx-id>` (epoch's `:effects/fired` contains this fx)
- `--timing-ms '>100'` / `'<5'` etc.
- `--touches-path [:a :b]` (epoch's `:app-db/diff` touched this path)
- `--sub-ran <query-id>` / `--render <component-name>`

(An arbitrary `--custom <clj-form>` predicate is on the v0.2 backlog; v0.1 ships only the discrete keys above.)

**Notification content.** Each streamed epoch arrives as a one-line edn map with the epoch id and key fields. Claude can fetch the full epoch via `trace/epoch <id>` when it wants detail — the stream is a pointer, not a dump, so context stays bounded.

**Stopping conditions defaults:** idle-for-30s, hard-cap-5-minutes, max-50-epochs-streamed. Overridable per call. This is the invariant that keeps watches from silently running forever.

### 4.5 Hot-reload coordination

Source edits are permanent and pass through shadow-cljs's compile + push pipeline before they take effect in the browser. If Claude dispatches between edit and push-landing, it interacts with the old code. These ops close that gap.

**Mechanism.** Despite the name, `tail-build.sh` does **not** watch shadow-cljs's server stdout — the implementation is entirely probe-based over nREPL. After a source edit, the skill polls a short CLJS form (the **probe**) whose return value changes once the browser has fetched and re-evaluated the updated module. No actual log tailing happens. The script's name is historical; its job is *wait-for-reload*, not *tail*.

Probe-selection heuristics live in `SKILL.md`:

- If the edit touched a `reg-*` handler: probe the registrar, compare the new handler's function reference against the one captured before the edit. When the reference changes, reload has landed.
- If the edit touched non-registered code (views, helpers): probe the affected ns by reading a top-level var whose value depends on the new code (the skill picks something unambiguous from the edit).
- If no reliable probe is available, `hot-reload/wait` falls back to a fixed 300ms timer (no probe) and returns `{:ok? true :soft? true :t <ms>}` — the caller sees the reload as confirmed, but `:soft? true` marks it as a timer-based assumption rather than a verified landing. Claude surfaces the distinction to the user.

There is no pre-installed "build sentinel" — re-frame-pair has no preload, so nothing in the app re-runs on reload to update a canonical version var. The probe-based confirmation is the substitute. (Companion proposal: expose shadow's runtime build-id as a public API so a generic sentinel becomes available — see Appendix A roadmap.)

| Op | Purpose |
|---|---|
| `hot-reload/wait --timeout <ms>` | Block until the next reload event arrives or timeout. Returns `{:ok? true :t <ms>}` on reload, `{:ok? false :timed-out? true}` on timeout. |
| `hot-reload/last-event` | Read the most recent reload event (timestamp, build-id) without blocking. |
| `hot-reload/subscribe` | Stream reload events through the §4.4 watch channel (same notification plumbing). Useful for long sessions where Claude wants to know *whenever* code changes. |

**Protocol Claude learns:** any time a source edit is made via `Edit`, `Write`, or external tooling, the next op in that direction must be `hot-reload/wait` (default 5s timeout). Only after a successful reload event does Claude proceed to `dispatch` or `trace/*`. If timeout hits, Claude reports the stall — almost always a compile error the user needs to see.

### 4.6 Time-travel (adapters over 10x internals)

re-frame-10x has internal epoch navigation and replay — it stores pre-event `app-db` snapshots and can step between them in its own UI. **There is no stable public "undo API" that matches the ops below**; `undo/*` is a planned **adapter** that re-frame-pair would build over 10x's internals (in the same coupling-absorbing spirit as §3.2's trace-buffer access). The value is high enough to justify the coupling because the experiment loop (§4.7 recipe) depends on it.

| Op (planned adapter) | Purpose |
|---|---|
| `undo/step-back` | Rewind one epoch |
| `undo/step-forward` | Re-apply one epoch from the redo stack |
| `undo/to-epoch <id>` | Jump to a specific epoch in 10x's buffer |
| `undo/status` | Current position in the history, how many back/forward steps are available |

**Caveat — scope of undo.** 10x's epoch navigation rewinds `app-db` only. Side effects that already fired (`:http-xhrio`, navigation, `:dispatch-later` that landed elsewhere) are *not* undone. `undo/status` would need to expose a `:side-effects-since` list so Claude knows what it can't rewind and can warn the user before an experiment that depends on clean state.

**Dependency risk.** Because these ops reach into 10x internals that weren't designed as an external API, the adapter can break on 10x upgrades. Companion proposal A2 (§A / Appendix A) would put these behind a documented 10x surface; until then, the adapter lives in re-frame-pair.

### 4.7 Diagnostics (higher-level recipes composed from the above)

These live in `SKILL.md` as named procedures the agent is taught to run when the user asks a matching question:

- **"Why didn't my view update?"** — walk the Layer 2→3 chain for the named sub; report which input equality gate held the value constant.
- **"Dead code scan"** — diff `registrar/list` against a trace window to report events/subs that never fired or never subscribed.
- **"Explain this dispatch"** — present the six dominoes for the last event: interceptor chain, coeffects, effects map, `app-db` diff, subs re-run, components re-rendered. Render entries annotated with `:src` where available (§4.3a).
- **"What caused this re-render?"** — given a component, reverse from its deref'd subs back to the event that invalidated them.
- **"What effects fired?"** — read `:effects/fired` from the epoch; present the cascade as a tree, following `:epoch-id` links into child epochs for `:dispatch*` effects. Flags pending effects (`:dispatch-later` not yet fired, `:http-xhrio` awaiting response) so Claude can explain "this is what's queued, not what's landed".
- **"Narrate the next N events"** — `watch/count N` with no filter. Claude reports each as it fires, in one short paragraph per event.
- **"Alert me on slow events"** — `watch/stream --timing-ms '>100'`. Silent until an epoch exceeds the threshold; Claude reports with timing breakdown.
- **"Watch for this event while I interact"** — `watch/stream --event-id-prefix :checkout/`. Streams every `:checkout/*` dispatch as you click around; Claude narrates, then summarises when idle.
- **"Experiment loop"** — the workflow iterating on a handler against identical starting state:
  1. `trace/dispatch-and-collect [:foo ...]` — run it, observe
  2. `undo/step-back` — rewind `app-db`
  3. Modify the handler — either `reg-event-db` at the REPL (ephemeral) or `Edit` on the source file followed by `hot-reload/wait`
  4. `trace/dispatch-and-collect [:foo ...]` — re-run on identical state
  5. Compare. Repeat until satisfied; then commit via source edit if the change was only at the REPL.
  Claude surfaces `undo/status :side-effects-since` before each rewind so the user knows what the undo can't reverse.
- **"Where in the code does this come from?"** — point at a DOM element (via selector, or "the thing I last clicked"); `dom/source-at` returns the `:src` file:line. Inverse: given a file:line, `dom/find-by-src` locates the live elements.
- **"Fire the button at file:line"** — via `dom/fire-click-at-src`, without needing a CSS path, when Claude wants to exercise a specific call site.

---

## 5. SKILL.md shape (sketch)

```markdown
---
name: re-frame-pair
description: Pair-program with a live re-frame application. Attach to a running shadow-cljs build via nREPL to inspect app-db, dispatch events, trace the data loop, hot-swap handlers, and read re-frame-10x's epoch buffer. Keywords that should auto-match this skill include re-frame, app-db, dispatch, subscribe, reg-event, reg-sub, epoch, re-frame-10x, re-com.
allowed-tools:
  - Bash(scripts/discover-app.sh)
  - Bash(scripts/eval-cljs.sh *)
  - Bash(scripts/inject-runtime.sh)
  - Bash(scripts/dispatch.sh *)
  - Bash(scripts/trace-recent.sh *)
  - Bash(scripts/watch-epochs.sh *)
  - Bash(scripts/tail-build.sh *)
---

# re-frame-pair

You are pair-programming with a developer on a live re-frame app. ...

## Cardinal rule (two modes)
REPL hot-swap is ephemeral; source edits are permanent and must be followed by `hot-reload/wait` before the next dispatch. Never dispatch into code you just edited without confirming the reload landed.

## Operations
[the table from §4, written as instructions]

## Recipes
[the diagnostic recipes from §4.7]
```

### 5.1 Plugin manifest sketch

`.claude-plugin/plugin.json` — indicative; exact fields track the Claude Code Plugin schema at publication time.

```json
{
  "name": "re-frame-pair",
  "version": "0.1.0",
  "description": "Pair-program with a live re-frame application.",
  "author": "day8",
  "homepage": "https://github.com/day8/re-frame-pair",
  "skills": ["./SKILL.md"],
  "scripts": [
    "./scripts/discover-app.sh",
    "./scripts/eval-cljs.sh",
    "./scripts/inject-runtime.sh",
    "./scripts/dispatch.sh",
    "./scripts/trace-recent.sh",
    "./scripts/watch-epochs.sh",
    "./scripts/tail-build.sh"
  ]
}
```

---

## 6. Phased delivery

> **Status (2026-04-27).** Phases 0–8 all delivered as of v0.1.0-beta.2; per-phase implementation status tracked in [`STATUS.md`](../STATUS.md) *Per-phase status* table. Phase 5 (hot-reload coordination) is coded but lacks a live edit→reload verification cycle (STATUS.md *Near-term* item 2).

| Phase | Deliverable | Demo |
|---|---|---|
| **0. Spike** | `eval-cljs.sh` round-trips a form through shadow-cljs nREPL | `(deref re-frame.db/app-db)` returns edn to the terminal |
| **1. Read surface** | `SKILL.md` + read-only ops (§4.1) | Claude answers "what's in app-db", "what events are registered", "what subs are live" |
| **2. Dispatch + trace** | 10x epoch-buffer adapter + ops §4.2 (dispatch) + §4.3 | Claude fires an event and narrates the six dominoes back to the user |
| **3. Live watch** | `watch/*` ops (§4.4) + Monitor integration | "Tell me about every `:checkout/*` event as I click through checkout" works end-to-end |
| **4. Hot-swap (REPL)** | `reg-event` / `reg-sub` replacement flow | Claude proposes a handler change, evaluates it in, asks the developer to re-click, iterates |
| **5. Hot-reload coordination** | §4.5 ops + `tail-build.sh` + code-sensitive probe form | Claude edits a source file, waits for reload, then dispatches — round-trip works |
| **6. Time-travel** | §4.6 adapters over 10x's internal epoch-navigation state | Claude can run the experiment loop: dispatch, observe, step back, modify, re-dispatch |
| **7. Diagnostics** | Recipes from §4.7 baked into `SKILL.md` | "Why didn't my view update?" answers by walking the sub graph |
| **8. Packaging** | Dual-publish from `day8/re-frame-pair` as Agent Skill + Claude Code Plugin | `npx skills add day8/re-frame-pair` and `/plugin install re-frame-pair@day8` both work |

---

## 7. Open questions

> **Per-question status as of 2026-04-27 (post-beta.2 capability waves).** Each question
> annotated with current state. Questions are still active design
> choices, not stale verification items.

1. **Authorization surface for writes** — should `app-db/reset` and handler hot-swap require a second-level confirmation (an `AskUserQuestion` prompt) or is trusting the skill's in-prompt guardrails enough? Lean toward confirming for v1 and loosening later. *Status: still open. v0.1.0-beta.2 ships without an explicit confirmation gate; the SKILL.md cardinal rule (REPL changes ephemeral, lost on full reload) is the safety net. Revisit before v0.2.*
2. **10x coupling** — re-frame-pair reads 10x internals, which aren't a public API. For v1 re-frame-pair absorbs that coupling directly. If 10x internals churn, the fix lands in re-frame-pair's adapter, not the skill vocabulary. See Appendix A (specifically A2) for the proposal to promote a stable public namespace in 10x. *Status: 10x maintainer shipped the public surface in commit `4107f8f` (`rf1-jum`); re-frame-pair consumer landed in commit `4a575ac` — `runtime.cljs` prefers the public ns and falls back to inlined-rf walking only on older 10x JARs. Adjacent: re-frame `4a53afb` (`rf-ybv`) shipped `register-epoch-cb`, and re-frame-pair's `9d4e948` (`rfp-zl8`) made the native epoch path the primary source — so 10x is now a secondary substrate for new fixtures. The coupling-absorption claim is now scoped to the legacy fallback path.*
3. **Exact minimum versions** (§3.7 placeholders) — confirm floor versions on first release by checking which API surfaces each feature requires. *Status: re-frame now exposes a runtime version constant (commit `8cc973c`) so floor enforcement is plumbing-ready for re-frame; re-frame-10x and re-com still need their own constants. Floors remain nil through v0.1.0-beta.2; `health` exposes `:versions.enforcement-live? false` so callers can tell.*
4. **`app-db/schema` convention** — is `(get @app-db :re-frame-pair/schema)` the right convention to ask apps to opt into, or should re-frame-pair sniff a registry (malli / spec) directly? Revisit when `:schema` usage is observed in real apps. *Status: still open. No real-world `:schema` usage observed yet against the fixture; revisit after first day8 app exercise (STATUS.md *Near-term* item 3).*
5. **Hot-reload probe fallback delay** — v1 uses a fixed post-"Build complete" delay (default 300ms) when no code-sensitive probe is available. Confirm empirically that 300ms is enough on typical dev builds; consider a larger default on CI / slow machines. *Status: still open. Phase 5 live verification (STATUS.md *Near-term* item 2) is the natural venue — no automated edit→reload cycle has run end-to-end yet. Adjacent: `rfp-3es` (commit `d51cfa5`) confirmed the SKILL.md cardinal rule names the real op (`tail-build.sh --probe`) — fixes a doc bug that would have led an LLM to attempt a non-existent invocation on the first source edit.*

(Contracts that need *verification against current source* — specific atom names, shadow-cljs nREPL port paths, re-com's `data-rc-src` form, 10x's epoch-buffer shape and internal navigation events, and the live-watch transport mechanism — are consolidated in §8a as spike deliverables. **§8a is now resolved (2026-04-25); see banner there.** This section is reserved for design choices that are open even after plumbing is proven.)


---

## 8. Success criteria for v1

The skill is useful if, after installing and starting a shadow-cljs dev build:

1. Claude can answer "what's in `app-db` under `:user/profile`" without the developer opening devtools.
2. Claude can fire `[:user/save-profile {...}]` and report the resulting `app-db` diff, effects fired, and subs re-run.
3. Claude can hot-patch a failing event handler, the developer reloads nothing, and the next click works.
4. Adding the skill adds no host-project configuration beyond what re-frame-10x already required. No extra `deps.edn` entry, no extra `:preloads`, no extra `:closure-defines` attributable to re-frame-pair. The skill injects everything it needs over the REPL on connect.

---

## 8a. Status: pre-spike

> **Status update 2026-04-26: spike resolved.** All 6 unknowns in this
> section have been ground-truthed end-to-end against the live fixture
> (`tests/fixture/`). See `STATUS.md` *Spike findings (§8a, resolved
> 2026-04-25)* for the per-item resolution: the 10x epoch-buffer
> accessor, the time-travel adapter, the re-com `:src` parser, the
> registrar shape, and the subscription cache shape are all wired to
> real internals; v0.1.0-beta.1 + beta.2 squash-merged to `main` (PRs
> #1, #2). The §6 phase deliverables are also marked Verified per
> `STATUS.md`'s per-phase table. This section retained as historical
> record of what the spike was meant to prove.

This is a design document, not a running skill. The spec leans on internals of three libraries (re-frame, re-frame-10x, re-com) and of shadow-cljs itself. Two categories of claim need to be told apart:

### Confirmed (by inspection of current source)

- `re-frame.db/app-db` is a public Reagent ratom. Read/reset! are the normal API.
- `re-frame.registrar/kind->id->handler` is the registrar atom; reachable from outside even though not documented.
- `re-frame.trace/register-trace-cb` and `re-frame.trace/trace-enabled?` are the trace hook and the compile-time gate.
- re-frame-10x registers a trace-cb, groups trace events into epochs, maintains a buffer.
- shadow-cljs nREPL starts in JVM-Clojure mode and `(shadow.cljs.devtools.api/repl <build-id>)` switches to `:cljs` mode.

### To verify in the spike, before more spec writing

The exact names and shapes below are assumed from memory; they drive real ops and must be ground-truthed:

- **`re-frame.subs/query->reaction`** — is that still the name of the subscription cache atom? Shape: `{query-vec reaction}`?
- **re-com's `:src (at)` contract** — is `data-rc-src` the actual attribute, what form (single `file:line:col` string, or split attributes)? Which `re-com.config` flag gates attaching it?
- **re-frame-10x's epoch-buffer surface** — what's the actual shape and where in 10x's own `app-db` (inside the 10x iframe) does it live? What events does 10x's internal epoch-navigation use (for the `undo/*` adapter in §4.6)?
- **shadow-cljs nREPL port file** — `target/shadow-cljs/nrepl.port`, `.shadow-cljs/nrepl.port`, or configurable? Which does the current shadow-cljs default to?
- **Live-watch transport (§4.4)** — does `prn` from a `js/setInterval` callback reach nREPL's `:out` during the outer eval's lifetime, and does the outer eval stay active long enough? If not, pull-mode is the fallback (same section).

### Recommended next step: a narrow spike

End-to-end against a minimal fixture app (re-frame + re-frame-10x + re-com + shadow-cljs, nothing else):

1. **Runtime discovery.** `discover-app.sh` finds the nREPL port, connects, switches to `:cljs` mode for the build, and verifies re-frame, 10x, re-com, and `trace-enabled?` — reporting each check by name on failure.
2. **CLJS eval into the selected browser runtime.** `eval-cljs.sh` round-trips `(deref re-frame.db/app-db)` and returns edn to the shell. Also exercises multi-runtime selection (two browser tabs on the same build).
3. **Epoch extraction from 10x.** Read a recent epoch from 10x's buffer, translate it into the §4.3a record shape, and confirm the fields (`:app-db/diff`, `:subs/ran`, `:renders`, `:effects/fired`) are all recoverable from what 10x already stores. No second trace callback — proving §3.2's "one source of truth" claim is actually achievable.
4. **Live-watch transport.** Prove (or disprove) that async `prn` output from an in-progress eval reaches nREPL's `:out` reliably enough for streaming. If it doesn't, commit to the pull-mode fallback and update §4.4.

If the spike surfaces anything that contradicts this spec, the spec is wrong, not the spike. Fix the spec first, then continue with Phase 1.

---

## 9. Testing strategy

re-frame-pair has four surfaces that need testing, each with a different approach:

1. **`scripts/runtime.cljs` unit tests.** Standard `cljs.test` against the helper fns (`snapshot`, `trace-dispatch`, predicate matchers, the trace-cb body). Runs in node via shadow-cljs. Fast feedback on the forms Claude injects.
2. **Bash shim integration tests.** A fixture shadow-cljs build hosts a trivial re-frame + re-com + 10x app. The test harness runs each `scripts/*.sh` against it and asserts output shape (edn parseable, expected keys). Covers the transport plumbing end to end.
3. **End-to-end in-browser.** Headless Chrome via karma or playwright. Verifies the full path from bash → nREPL → browser runtime, with real reagent reconciliation, real 10x, real re-com. Critical for `watch/*` streaming (nREPL `:out` forwarding), hot-reload coordination (`tail-build.sh` + sentinel), and `dom/*` ops (re-com `:src` attribute round-trip).
4. **Skill prompt regression.** A set of representative conversations (*"what's in app-db"*, *"trace this dispatch"*, *"why didn't my view update"*, *"iterate against same starting state"*). Fed to Claude Code against the fixture app; the harness asserts the set of ops invoked and the shape of Claude's responses. Catches silent drift in how the skill's description and recipes translate to actual tool use.

CI runs (1) and (2) on every push; (3) and (4) on main + nightly. Release gates on all four.

---

## Appendix A — Proposed companion changes to re-frame, re-frame-10x, and re-com

re-frame-pair can ship against these libraries as they exist today. The following changes to re-frame, re-frame-10x, and re-com would reduce re-frame-pair's code size, stabilise its contract, or unlock new recipes. Ordered by leverage.

> **Implementation status (2026-04-27).** Six of the nine items have shipped upstream and are consumed in re-frame-pair: A2 (re-frame-10x `4107f8f` rf1-jum), A3 (re-com `b3912727`+`961b9215` rc-aeh), A4 (re-frame `4a53afb` rf-ybv), A8 (re-frame `f8f0f59` rf-4mr), A9 (re-frame `2651a30` rf-ge8), and A7 superseded by re-frame `15dfc25` rf-ysy (with-meta on registered handler value rather than retained source forms — same effect for the `handler/source` use case). A1, A5, and A6 remain open. See `STATUS.md` *Post-spike additions* for the per-item land-detail.

### Tier 1 — high leverage

**A1. Documented `re-frame.introspect` public namespace.** *(Open — no upstream movement; re-frame-pair continues to reach into the de-facto-public atoms.)*
Today re-frame-pair reaches into `re-frame.registrar/kind->id->handler`, `re-frame.subs/query->reaction`, and `re-frame.db/app-db` directly. These work but are not documented API. Promote a small surface — `(list-handlers kind)`, `(live-subs)`, `(current-app-db)`, `(registered? kind id)` — to a named public namespace so re-frame-pair (and future tooling) has a stable contract instead of a coupling to internal atoms.

**A2. Public namespace in re-frame-10x.** *(Shipped — re-frame-10x `4107f8f` rf1-jum; consumer rfp `4a575ac`.)*
10x's epoch structures are re-frame-pair's trace substrate (§3.2) but are not a public API. Promote the needed fields (epoch id, event vector, interceptor chain, app-db before/after, sub-runs, renders, timings) to a documented namespace — e.g. `day8.re-frame-10x.public` — so re-frame-pair's adapter has a stable contract.

**A3. Carry `:src` in the render trace.** *(Shipped — re-com `b3912727` rc-aeh + `961b9215` debug exported-fns fix; rfp consumer in flight as `rc-u1z`.)*
Today re-com's `:src (at)` metadata lands on the DOM as `data-rc-src` but is not surfaced in the Reagent `:render` trace event. re-frame-pair's v1 adapter therefore joins render entries to `:src` via DOM name-and-recency matching (§4.3b) — approximate. A small re-com change — thread `:src` through to component metadata that the render trace picks up — makes the join exact and removes the DOM detour. Every render entry in an epoch would then carry `{:file :line :column}` end-to-end.

**A4. `register-epoch-cb` alongside `register-trace-cb`.** *(Shipped — re-frame `4a53afb` rf-ybv; consumer rfp `9d4e948` rfp-zl8.)*
Today, 10x groups raw trace events into per-dispatch *epochs* inside itself. If re-frame emitted epoch-granular callbacks — one call per completed event carrying the assembled sub-runs, renders, effects map, and app-db before/after — 10x (and any future tooling) would simplify dramatically, and *epoch* would become a canonical re-frame concept rather than a 10x construct. Downstream benefit: re-frame-pair could read epochs from re-frame directly, shortening the dependency chain.

### Tier 2 — useful

**A5. Subscription graph as data.** *(Open.)*
`(subs/declared-graph)` returning the static `:<-` edges, with a dynamic overlay of currently-live nodes. Lets Claude reason about the reactive topology without tracing to discover it; enables recipes like "show me everything that would re-run if I changed this sub".

**A6. Dispatch provenance.** *(Open — adjacent: rf-3p7 item 2 `af024c3` ships auto-generated `:dispatch-id` (different concern from caller-supplied provenance).)*
`(dispatch ev {:from :re-frame-pair})` with the metadata threaded through to the epoch record, so agent-dispatched events are distinguishable from user-driven ones in traces. (v1 workaround: the skill tags its own dispatches; see §4.3.)

**A7. Retained handler source forms in dev builds.** *(Superseded for the `handler/source` use case — re-frame `15dfc25` rf-ysy attaches `{:file :line}` via `with-meta` instead of retaining source forms; consumer rfp `fd74b8f` rfp-hpu retired the local `rfp-rsg` side-table. The "show the handler form itself" use case remains open at lower priority.)*
The registrar currently stores only the compiled handler fn. In dev builds, keep the source form alongside. Lets Claude *read* current handler behaviour rather than only overwriting it — better "explain this handler" recipes, safer hot-swap. Required for `registrar/describe` (§4.1) to return a source form.

### Tier 3 — speculative

**A8. `dispatch-and-settle` returning a promise/channel.** *(Shipped — re-frame `f8f0f59` rf-4mr; consumer rfp `c87529c` rfp-4ew.)*
Fulfilled when the event *and* its cascade of `:dispatch` / `:dispatch-later` / `:http-xhrio` / etc. all complete. Collapses the one-animation-frame wait and the "did the async effect land?" question into one primitive. "Settle" is hard to define generally — probably scoped to a configurable set of fx handlers.

**A9. Effect substitution for probe dispatches.** *(Shipped — re-frame `2651a30` rf-ge8; consumer rfp `69c570d` rfp-zml.)*
`dispatch-with` that swaps selected `reg-fx` handlers for no-ops or doubles, so Claude can safely explore event behaviour without triggering real network calls or navigation. Overlaps in spirit with re-frame's existing test utilities; may be achievable by exposing the test-mode machinery at runtime.

### Notes

- A1, A2, A3, and A4 would meaningfully simplify re-frame-pair's own code (A3 specifically removes the v1 DOM-recency join in §4.3b). The rest are leverage multipliers for what Claude can do with it.
- None of these are prerequisites. re-frame-pair ships against unchanged re-frame and unchanged 10x first; each companion change can land independently and re-frame-pair will pick it up opportunistically.
