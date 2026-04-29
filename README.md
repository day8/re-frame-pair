# re-frame-pair

Lets Claude (in Claude Code, or via the Claude API) debug and develop against your **running re-frame application**. Claude can inspect `app-db`, dispatch events, trace the dominoes, hot-swap handlers, time-travel, and map visible UI back to source — all live against the runtime, with no source edits required for probes.

A coding agent working with just static code is working with a limited perspective. It can read handlers, subscriptions, and views, but it has to guess what happened in the browser. With re-frame-pair, Claude can ask the running app: which event fired, what changed in `app-db`, which effects fired, which subscriptions re-ran or cache-hit, which views rendered, and where the relevant source lives.

The intended loop is empirical:

1. Observe the current runtime state.
2. Inspect the relevant epoch.
3. Form a hypothesis.
4. Probe with a dispatch, app-db read, hot-swap, side-effect stub, or REPL eval.
5. Compare the new epoch with the baseline.
6. Only then edit source.

REPL changes are **ephemeral** (try-and-discard); source edits **stick**.

Use it when a UI did not update, an event fired but the visible result is wrong, `app-db` ended up in a bad state, the user cannot remember the exact path that caused a bug, you need to find the source behind a visible control, or you want to test a handler/sub/fx change safely before committing it.

Post-mortems are a core use case. Instead of asking the developer to describe every click and state transition that led to a bug, Claude can inspect the recent epoch trail and identify the event that introduced the bad value, including its parent dispatch, effects, `app-db` diff, subscriptions, renders, and source call sites.

## Examples

> **You**: What changes did the last event make to `app-db` under `:shopping-cart`?
>
> **Claude**: 2 items, subtotal 47.50, no coupon.
> `{:items [{:sku "BK-42" :qty 2} {:sku "TS-9" :qty 1}] :total 47.50 :coupon nil}`

---

> **You**: I dispatched `[:profile/save ...]` but the name in the header isn't changing.
>
> **Claude**: The header reads `[:user/display-name]`, derived from `[:user/profile]`. Your handler wrote to `[:user :pending-profile]`, not `[:user :profile]`. Layer 2 returned `=`, the Layer 3 short-circuited, the header didn't re-render. Want me to hot-swap the handler to write the intended path?

---

> **You**: I just clicked the "Save" button and nothing happened. Where is that button and why doesn't it work?
>
> **Claude**: `src/app/profile/view.cljs:84`. It's a `re-com/button` with `:on-click #(rf/dispatch [:profile/save])`. The click *did* dispatch — trace shows `{:db ...}` returned — but no `:profile/form-dirty?` flip happened, so nothing visible changed. What feedback were you expecting: toast, spinner, redirect?

---

> **You**: My `[:cart/apply-coupon]` handler fired but I have three buttons that could have triggered it. Which one?
>
> **Claude**: That dispatch came from `app/cart/header.cljs:42` — the rightmost "Apply" button in the header, not either of the panel buttons.

---

> **You**: What happens when I dispatch `[:cart/apply-coupon "SPRING25"]`?
>
> **Claude**: Interceptors: `inject-cofx :now`, `path :shopping-cart`, handler. Effects: `{:db ...}` only — no HTTP, no further dispatches. app-db diff: `:coupon` `nil` → `"SPRING25"`, `:total` `47.50` → `35.62`. Subs re-ran: `[:cart/total]`, `[:cart/coupon-status]`; `[:cart/items]` cache-hit. One render: the total badge at `cart_view.cljs:23`. Pure handler, nothing outside the cart subtree touched.

---

> **You**: Tell me about every `:checkout/*` event as I click through.
>
> **Claude**: *(watches in the background; narrates each matching event as it fires, one short paragraph per match, with effect cascade — idle timeout after 30s of silence, so you don't need to remember to stop it)*

---

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

---

> **You**: Are there any items in the cart with `:qty <= 0`?
>
> **Claude**: *[at the REPL]* `(->> @re-frame.db/app-db :shopping-cart :items (filter #(<= (:qty %) 0)))` — returns `()`. None right now, but the invariant isn't enforced by the `:cart/add-item` handler. Want me to propose a guard?

---

> **You**: I notice that sometime in the last few events the "Done" button got disabled. I don't remember which action did it. Can you figure out when that happened?
>
> **Claude**: *[walks back through the last ~20 epochs]* The event `[:cart/apply-coupon "EXPIRED"]` four epochs ago turned `:session-state` from `:authenticated` to `:unauthenticated` — that's what greyed the button. Looks like a bug in the coupon handler, not an intended side-effect. Want me to fix it?

---

> **You**: When I first enter the Dashboard panel, the global reset button doesn't work. Fix it, then return to the previous state and fire the same event again, iterating until it works.
>
> **Claude**: I stepped re-frame-10x's state back to the pre-click epoch (noting: any HTTP effects or navigation that fired earlier are *not* reversed — undo only rewinds `app-db`). The panel-level subscription was wrong; I patched it. Re-ran the event, `app-db` now updates as expected. Want me to put the patch into the source code?

---

> **You**: I want to iterate on `[:user/login {...}]` but it fires `:http-xhrio` and I don't want to hit the real auth endpoint each time.
>
> **Claude**: Substituting `:http-xhrio` and `:navigate` with record-only stubs for this dispatch. The handler still receives its coeffects, the cascade still runs, but the captured effect values land in `stubbed-effects-since` instead of being executed. Each iteration's stubs expire when the cascade settles — no global state to restore.

---

> **You**: Transfer the patch to the source code.
>
> **Claude**: That change has been made to the source code.

## Requirements

| Dep | Floor | Notes |
|---|---|---|
| [re-frame](https://github.com/day8/re-frame) | 1.4+ | |
| [re-frame-10x](https://github.com/day8/re-frame-10x) | 1.10+ | dev preload, with `re-frame.trace.trace-enabled?` set true via `:closure-defines` (10x's own requirement) |
| [re-com](https://github.com/day8/re-com) | 2.20+ | for the DOM ↔ source bridge, debug instrumentation must be on AND call sites must pass `:src (at)` — without both, `dom/*` ops degrade gracefully (return `nil`) |
| [shadow-cljs](https://shadow-cljs.github.io/) | 2.20+ | nREPL enabled on the dev build |
| [babashka](https://babashka.org) | 1.0+ | the skill's shell shims use it — see [babashka install](https://github.com/babashka/babashka#installation) |

You don't need to make any changes to your code/project to use it.

Starting fresh? [`re-frame-template`](https://github.com/day8/re-frame-template) scaffolds a project that satisfies this stack out of the box: `lein new re-frame your-app +10x +re-com` produces an app re-frame-pair can attach to without further changes.

### Optional: per-form trace via re-frame-debux

Add [`day8.re-frame/tracing`](https://github.com/day8/re-frame-debux) to your dev classpath and the skill can drive it on demand: `wrap-handler!` / `unwrap-handler!` to instrument a whole handler (no source edit, hot-swapped at the REPL), or `dbg` / `dbgn` to instrument a single expression. Per-form trace records flow through `re-frame.trace/merge-trace!` into the same epoch buffer the skill already reads, surfaced as `:debux/code` on each coerced epoch. **Not** transitive via `re-frame-10x` — add the dep explicitly. The REPL-driven recipes live in [`docs/recipes/debux.md`](docs/recipes/debux.md).

## Two modes

A normal coding agent can only modify source files. re-frame-pair gives Claude a second mode: **ephemeral changes via the REPL**. Claude can hot-swap an event handler, redefine a subscription, swap an effect handler, or `reset!` `app-db` directly — the change takes effect immediately in the running app, with no source edit, no recompile, no commit.

That makes probing and iteration cheap: try a fix, dispatch the event, watch the resulting epoch, throw it away and try something else. When a REPL-only patch turns out to be the right shape, transfer it to source.

REPL changes survive hot-reloads of unaffected namespaces, but are lost on full page reload. Source edits stick.

## Install

**Right now:** clone + symlink the repo into `~/.claude/skills/re-frame-pair/`. See [`docs/LOCAL_DEV.md`](docs/LOCAL_DEV.md) for the full walk-through (install babashka, point Claude at the skill directory, done).

**After the first tagged release** (`v0.1.0-alpha.1`+, planned npm `@day8/re-frame-pair`), one of:

```bash
# Agent Skill — portable across Claude clients (recommended)
npx skills add day8/re-frame-pair

# Or as a Claude Code Plugin
/plugin install re-frame-pair@day8
```

re-frame-pair adds nothing to the host project beyond what 10x and re-com already require. On first connect it injects helpers into your app over the REPL — no extra deps, no extra preloads, no extra closure-defines attributable to re-frame-pair.

### Scope: global vs project-local

- **Global** (recommended for individuals) — installs into your user Claude config (`~/.claude/`). Best when you work on several re-frame apps:
  ```bash
  npx skills add day8/re-frame-pair
  ```
- **Project-local** — install into the project's own `.claude/skills/re-frame-pair/` and commit it. Teammates who clone the repo get the skill on first use, pinned to the committed version:
  ```bash
  cd your-re-frame-project
  npx skills add --scope project day8/re-frame-pair    # planned syntax
  git add .claude/skills/re-frame-pair
  ```
  For the plugin variant, reference `re-frame-pair@day8` in a checked-in `.claude/plugin.json`.
- **Both** is fine — the project-local install takes precedence when both are present.

## How to invoke it

**Implicit** — once installed, the skill auto-matches when you talk about the running re-frame app. Ask in natural language:

> What's in `app-db` under `:shopping-cart`?
>
> Why didn't the header update after `[:profile/save ...]`?
>
> Fire the delete button on the first row of the table.

Claude connects on first use of the session and stays connected until you exit.

**Explicit** — `/re-frame-pair` slash command, or name it in a prompt:

> Using re-frame-pair, trace `[:cart/apply-coupon "SPRING25"]` and show me the cascade.

Useful when you want to force the tool, or when the question doesn't obviously lean on the running app.

## How it connects

On first use the skill runs `discover-app.sh`:

1. Locates the running shadow-cljs nREPL (`target/shadow-cljs/nrepl.port`, falling back to `.shadow-cljs/nrepl.port` or the `SHADOW_CLJS_NREPL_PORT` env var).
2. Verifies a browser runtime is attached, that re-frame-10x and re-com are loaded, and that `re-frame.trace/trace-enabled?` is true.
3. Injects the `re-frame-pair.runtime` namespace (helpers + epoch-buffer readers) into your app over nREPL.
4. Returns a startup payload including health, app-db keys/shape, and a compact tail of recent events so the agent can orient itself before asking for more detail.

All subsequent ops reuse the connection. On a full page refresh the skill detects its session sentinel is gone and re-injects automatically.

Live-watch ops hold a long-running eval open and poll the epoch buffer at animation-frame cadence. Hot-reload confirmation is probe-based: after a source edit, `tail-build.sh` polls a short CLJS form that flips when the new code lands in the browser (see [`docs/initial-spec.md`](docs/initial-spec.md) §4.5). The script is named `tail-build.sh` for historical reasons — it does not actually tail the shadow-cljs server log.

Epoch reads come from re-frame's own `register-epoch-cb` callback when available — once a `:event` trace completes, re-frame delivers an assembled epoch record (sub-runs, renders, effects, `app-db` before/after) that the skill drains into a native ring buffer. On older re-frame builds, the skill falls back to reading 10x's epoch buffer via the public `day8.re-frame-10x.public` ns, with the legacy inlined-rf walk as a third fallback for 10x JARs predating the public surface. Render entries tagged with `:re-com?` (and a layout/input/content category where possible) let Claude apply component-aware diagnostics.

See [`docs/initial-spec.md`](docs/initial-spec.md) for the full operation catalogue, architecture, error surfaces, versioning, and phased delivery plan.

## How this differs from re-frame-10x alone

re-frame-10x is the developer-facing devtool — a UI panel that shows epochs, app-db, the subscription cache. **A human reads the panel.**

re-frame-pair gives that same epoch stream to **Claude** as programmatic data, plus the agency to dispatch events, hot-swap handlers, time-travel, and probe via the REPL. The skill *consumes* re-frame-10x's buffer (and re-frame's native epoch callback when present); 10x is the source of truth, re-frame-pair is the agent's interface to it.

You can run both at once. They don't conflict — 10x renders its panel, re-frame-pair reads the same epoch records.

## Status

**Beta.** Validated end-to-end against the live fixture (re-frame + re-frame-10x + re-com under `shadow-cljs watch`). CI green; ~120 deftests across runtime + ops_smoke + skill_recipe_smoke. Next tag is operator-pending.

See [`STATUS.md`](STATUS.md) for the per-phase implementation matrix and post-spike additions log; [`docs/initial-spec.md`](docs/initial-spec.md) for the design; [`docs/TESTING.md`](docs/TESTING.md) for the four-surface test plan; [`docs/LOCAL_DEV.md`](docs/LOCAL_DEV.md) for running from a clone; [`RELEASING.md`](RELEASING.md) for the release flow.

## License

MIT.

---

**Try it:** clone, symlink per [`docs/LOCAL_DEV.md`](docs/LOCAL_DEV.md), then read [`SKILL.md`](SKILL.md) to see the full vocabulary Claude learns.
