# re-frame-pair

A `Skill` which makes `Claude Code` a better pair programmer by allowing it to **interact with your running [re-frame](https://github.com/day8/re-frame) application**.

A coding agent working with just the **static code** is working with a limited perspective. This Skill makes Claude Code more capable by giving it read/write access to:
  - the **internal state** of the application 
  - the **dynamics of your running application**

It can:

- use the REPL
- obtain functionality from `re-frame-10x`, including trace execution for every *epoch* (the entire 6-domino cascade that occurs in response to an event)

With these capabilities, Claude Code can even iteratively perform experiments by patching parts of the system, undoing state, retrying events and seeing the results. 

## Status

**Beta** — validated end-to-end against the live fixture (re-frame + re-frame-10x + re-com under shadow-cljs watch). v0.1.0-beta.1 + beta.2 squash-merged to `main`; subsequent capability waves landed on top: the native epoch path (`re-frame/register-epoch-cb`, replacing the 10x-buffer detour for new fixtures), `dispatch-and-settle` for adaptive cascade-aware tracing, `dispatch-with --stub` for record-only fx stubs, `:event/source` and `:subscribe/source` flattened onto coerced epochs, Phase 2 of the optional `re-frame-debux` integration (runtime `wrap-handler!` / `unwrap-handler!` API), post-refresh auto-reinject wires native epoch/trace/console cbs into the freshly-shipped runtime (closing a silent data-loss gap on full page reloads), and the legacy-re-frame `--trace`+`--stub` fallback routes through `dispatch-sync-with-stubs!` so fx substitution actually fires on builds predating rf-4mr / rf-ge8. All `§8a` ground-truth questions from the spike are resolved against current source; CI is green; the runtime is unit-tested across 70 deftests / 371 assertions plus a 20-deftest babashka suite. Next tag is operator-pending.

Read [`STATUS.md`](STATUS.md) for per-phase implementation state and the post-spike additions log; [`docs/initial-spec.md`](docs/initial-spec.md) for the full design; [`docs/TESTING.md`](docs/TESTING.md) for the four-surface test plan; [`docs/LOCAL_DEV.md`](docs/LOCAL_DEV.md) for running from a clone without waiting for an npm release; [`RELEASING.md`](RELEASING.md) for the release flow.

## Which technical stack?

Designed for web apps built from the following stack — see [`STATUS.md`](STATUS.md) for what's source-verified vs. operator-pending:

- A [re-frame](https://github.com/day8/re-frame) application
- [`re-frame-10x`](https://github.com/day8/re-frame-10x) loaded as a dev-time preload, with `re-frame.trace.trace-enabled?` set to `true` via `:closure-defines` (this is 10x's own requirement)
- [`re-com`](https://github.com/day8/re-com) — and for the DOM ↔ source bridge specifically, **re-com's debug instrumentation** must be enabled in dev *and* call sites must pass `:src (at)`. Without both, the `dom/*` ops degrade gracefully (return `nil`) and Claude will say so.
- [shadow-cljs](https://shadow-cljs.github.io/) as the build tool, with nREPL enabled on the dev build

You don't need to make any changes to your code/project to use it, but you will need [`babashka`](https://babashka.org) installed because the skill's shell shims use it. See the [babashka install guide](https://github.com/babashka/babashka#installation).

#### Optional: per-form trace via `re-frame-debux`

If you add [`day8.re-frame/tracing`](https://github.com/day8/re-frame-debux) (the artifact published by the `re-frame-debux` repo) to your dev classpath, the skill can drive it on demand: `wrap-handler!` / `unwrap-handler!` to instrument a whole handler (no source edit, hot-swapped at the REPL), or `dbg` / `dbgn` to instrument a single expression. Per-form trace records flow through `re-frame.trace/merge-trace!` into the same epoch buffer the skill already reads, surfaced as `:debux/code` on each coerced epoch. This is **not** transitive via `re-frame-10x` — you must add the dep explicitly. See [`docs/inspirations-debux.md`](docs/inspirations-debux.md) for the integration shape and the REPL-driven recipes.
  
## Two modes 

Without this `Skill`, `Claude Code` writes edits to source files and shadow-cljs hot-reloads them into the running program.

`re-frame-pair` adds a second mode: Claude can also make **ephemeral** changes to application code via the REPL — hot-swap an event handler or a subscription, try it, discard if it didn't work. 

The difference between the two modes is that REPL changes last until the next full page reload whereas source edits stick.


## Examples

Here's the kinds of conversations you can have with Claude. 

### Inspect the last change

> **You**: What changes did the last event make to `app-db` under `:shopping-cart`?
>
> **Claude**: 2 items, subtotal 47.50, no coupon.
> `{:items [{:sku "BK-42" :qty 2} {:sku "TS-9" :qty 1}] :total 47.50 :coupon nil}`

### Ask why a view didn't update

> **You**: I dispatched `[:profile/save ...]` but the name in the header isn't changing.
>
> **Claude**: The header reads `[:user/display-name]`, derived from `[:user/profile]`. Your handler wrote to `[:user :pending-profile]`, not `[:user :profile]`. Layer 2 returned `=`, the Layer 3 short-circuited, the header didn't re-render. Want me to hot-swap the handler to write the intended path?

### Jump from a click to the line of code

> **You**: I just clicked the "Save" button and nothing happened. Where is that button and why doesn't it work?
>
> **Claude**: `src/app/profile/view.cljs:84`. It's a `re-com/button` with `:on-click #(rf/dispatch [:profile/save])`. The click *did* dispatch — trace shows `{:db ...}` returned — but no `:profile/form-dirty?` flip happened, so nothing visible changed. What feedback were you expecting: toast, spinner, redirect?

### Find which call site fired an event

> **You**: My `[:cart/apply-coupon]` handler fired but I have three buttons that could have triggered it. Which one?
>
> **Claude**: That dispatch came from `app/cart/header.cljs:42` — the rightmost "Apply" button in the header, not either of the panel buttons. (Reading `:event/source` off the last epoch; works because the call site uses `re-frame.macros/dispatch`, which captures `*file*` + `(:line (meta &form))` at expansion time.)

### Trace the full cascade of an event

> **You**: What happens when I dispatch `[:cart/apply-coupon "SPRING25"]`?
>
> **Claude**: Interceptors: `inject-cofx :now`, `path :shopping-cart`, handler. Effects: `{:db ...}` only — no HTTP, no further dispatches. app-db diff: `:coupon` `nil` → `"SPRING25"`, `:total` `47.50` → `35.62`. Subs re-ran: `[:cart/total]`, `[:cart/coupon-status]`; `[:cart/items]` cache-hit. One render: the total badge at `cart_view.cljs:23`. Pure handler, nothing outside the cart subtree touched.

### Watch events as they fire

> **You**: Tell me about every `:checkout/*` event as I click through.
>
> **Claude**: *(watches in the background; narrates each matching event as it fires, one short paragraph, with effect cascade)* — idle timeout after 30s of silence, so you don't need to remember to stop it.

### Hot-swap a handler mid-session

> **You**: Make `:cart/apply-coupon` reject expired coupons instead of silently accepting them.
>
> **Claude**: Proposed change:
> ```clojure
> (rf/reg-event-db :cart/apply-coupon
>   (fn [db [_ code]]
>     (if (contains? (:expired-coupons db) code)
>       (assoc db :cart/coupon-error (str code " is expired"))
>       (assoc-in db [:shopping-cart :coupon] code))))
> ```
> Registered. Try clicking apply — I'll watch the next epoch.


### Ask a question that doesn't have a pre-baked op

> **You**: Are there any items in the cart with `:qty <= 0`?
>
> **Claude**: *[at the REPL]* `(->> @re-frame.db/app-db :shopping-cart :items (filter #(<= (:qty %) 0)))` — returns `()`. None right now, but the invariant isn't enforced by the `:cart/add-item` handler. Want me to propose a guard?

### Post-mortem — how did I get into this state?

> **You**: I notice that sometime in the last few events the "Done" button got disabled. I don't remember which action did it. Can you figure out when that happened?
>
> **Claude**: *[walks back through the last ~20 epochs in re-frame-10x's buffer, looking for the transition]* The event `[:cart/apply-coupon "EXPIRED"]` four epochs ago turned `:session-state` from `:authenticated` to `:unauthenticated` — that's what greyed the button. Looks like a bug in the coupon handler, not an intended side-effect. Want me to fix it?


### Iterate against the same starting state

> **You**: When I first enter the Dashboard panel, the global reset button doesn't work. Fix it, then return to the previous state and fire the same event again, iterating until it works.
>
> **Claude**: I stepped re-frame-10x's state back to the pre-click epoch (noting: any HTTP effects or navigation that fired earlier are *not* reversed — undo only rewinds `app-db`). The panel-level subscription was wrong; I patched it. Re-ran the event, `app-db` now updates as expected. Want me to put the patch into the source code?

### Probe a side-effecting handler safely

> **You**: I want to iterate on `[:user/login {...}]` but it fires `:http-xhrio` and I don't want to hit the real auth endpoint each time.
>
> **Claude**: Substituting `:http-xhrio` and `:navigate` with record-only stubs for this dispatch via `dispatch.sh --trace --stub :http-xhrio --stub :navigate '[:user/login {...}]'`. The handler still receives its coeffects, the cascade still runs, but the captured effect values land in `stubbed-effects-since` instead of being executed. Each iteration's stubs expire when the cascade settles — no global state to restore.

### Commit a change via source edit

> **You**: Transfer the patch to the source code.
>
> **Claude**: That change has been made to the source code.


## Install

> Not yet published. The first tagged release (`v0.1.0-alpha.1`+) will appear on npm as `@day8/re-frame-pair`.

To run it *before* then — straight from a clone, with edits live — see [`docs/LOCAL_DEV.md`](docs/LOCAL_DEV.md). Symlink the repo into `~/.claude/skills/re-frame-pair/`, install babashka, done.

`re-frame-pair` adds nothing to the host project beyond what 10x and re-com already require. On first connect, the skill injects its runtime helpers into your app over the REPL — no extra deps, no extra preloads, no extra closure-defines attributable to `re-frame-pair`.

### Install the skill in Claude Code

Both distributions (Agent Skill and Claude Code Plugin) ship the same `SKILL.md` and `scripts/*` shims. Choose a scope.

#### Global — for you, across any re-frame project

As an Agent Skill (recommended; portable across Claude clients):

```bash
npx skills add day8/re-frame-pair
```

As a Claude Code Plugin:

```bash
/plugin install re-frame-pair@day8
```

Lands in your user Claude config (`~/.claude/`). Best when you work on several re-frame apps, or you're the only Claude Code user on this project.

#### Project-local — for your whole team via the repo

Install into the project's own `.claude/skills/re-frame-pair/` directory and commit it. Teammates who clone the repo and open Claude Code there get the skill on first use, pinned to the committed version.

```bash
cd your-re-frame-project
npx skills add --scope project day8/re-frame-pair    # planned syntax
git add .claude/skills/re-frame-pair
```

For the plugin variant, reference `re-frame-pair@day8` in a checked-in `.claude/plugin.json`.

#### Which to choose

- **Global** if you're the only person using Claude Code here, or you hop between re-frame apps.
- **Project-local** if your team wants one pinned, shared version.
- **Both** is fine — the project-local install takes precedence when both are present.

### How the connection works

On first use in a session:

1. The skill locates your shadow-cljs nREPL port.
2. It sends a handful of ClojureScript forms over nREPL to create a `re-frame-pair.runtime` namespace in your app, populated with helpers and readers for re-frame-10x's epoch buffer.
3. Live-watch ops (`watch/*`) stream matching epochs back to the shell by holding a long-running eval open and polling 10x's buffer at animation-frame cadence. Hot-reload confirmation is probe-based: after an edit, the skill polls a short CLJS form that changes when the new code has landed in the browser (see [`docs/initial-spec.md`](docs/initial-spec.md) §4.5). The script is named `tail-build.sh` for historical reasons — it does not actually tail the shadow-cljs server log.

On full page refresh, the skill detects that its session sentinel is gone and re-injects automatically.

## Invoking it in Claude

Once the skill is installed, there are two ways to reach it from a Claude Code conversation.

### Implicit — just ask

The skill's description auto-matches when you talk about the running re-frame app. Ask in natural language:

> What's in `app-db` under `:shopping-cart`?
>
> Why didn't the header update after `[:profile/save ...]`?
>
> Fire the delete button on the first row of the table.

Claude connects on first use of the session and stays connected until you exit.

### Explicit — slash command

```
/re-frame-pair
```

…or name it in a prompt:

> Using re-frame-pair, trace `[:cart/apply-coupon "SPRING25"]` and show me the cascade.

Useful when you want to force the tool, or when the phrasing of your question doesn't obviously lean on the running app.

### What happens on first use

The skill's first op in a session is `discover-app.sh`, which:

1. Finds the running shadow-cljs nREPL (from `target/shadow-cljs/nrepl.port`, falling back to `.shadow-cljs/nrepl.port` or the `SHADOW_CLJS_NREPL_PORT` env var — the exact location depends on shadow-cljs version and config).
2. Verifies a browser runtime is attached to that build.
3. Checks that `re-frame-10x` and `re-com` are loaded, and that `re-frame.trace/trace-enabled?` is true.
4. Reports `connected` or names the single failing check with a one-line fix suggestion.

Once verified, the skill injects its runtime namespace (`re-frame-pair.runtime`) into the app over nREPL. All subsequent ops reuse that connection.

## How it works

The pieces (design; see *Status* above):

1. `discover-app.sh` finds the running shadow-cljs build and its nREPL port, switches the session into `:cljs` mode for that build, and verifies re-frame-10x, re-com, and `trace-enabled?` are in place.
2. `eval-cljs.sh` sends short ClojureScript forms over nREPL into the browser runtime and returns edn.
3. `inject-runtime.sh` creates the `re-frame-pair.runtime` namespace in the app on connect, populating it with helpers and epoch-buffer readers. The session sentinel (a UUID) is interned here so full-page-refresh detection is a simple lookup.
4. `SKILL.md` teaches Claude a verb vocabulary (read / write / trace / watch / hot-reload / undo) mapped onto those forms, plus diagnostic recipes composed from them.
5. Epoch reads come from re-frame's own `register-epoch-cb` callback (rf-ybv) when available — once a `:event` trace completes, re-frame delivers an assembled epoch record (sub-runs, renders, effects, `app-db` before/after) that the skill drains into a native ring buffer. On older re-frame builds, the skill falls back to reading re-frame-10x's epoch buffer via the public `day8.re-frame-10x.public` ns (rf1-jum), with the legacy inlined-rf walk as a third fallback for 10x JARs predating the public surface. Render entries tagged with `:re-com?` (and where possible a layout/input/content category) let Claude apply component-aware diagnostics.

See [`docs/initial-spec.md`](docs/initial-spec.md) for the full operation catalogue, architecture, error surfaces, versioning, and phased delivery plan.

## License

MIT
