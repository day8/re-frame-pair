# What's still to do

A roadmap from where we are (pre-alpha skeleton, not yet run against a live app) to *usable*. Companion to `STATUS.md` (which tracks implementation state per spec phase) and `docs/initial-spec.md` §8a (which lists the unknowns the spike must ground-truth).

---

## The critical path, ranked

1. **Fixture app** (`tests/fixture/`). A minimal shadow-cljs + re-frame + 10x + re-com app you can `shadow-cljs watch` and point the skill at. Roughly a day. Nothing downstream works without this.

2. **The 10x epoch-buffer accessor.** This is **the** blocker. Right now `runtime.cljs/read-10x-epochs` returns `[]` — so every trace op, every watch, every `dispatch --trace` succeeds *structurally* but returns empty. Finding where 10x actually stores its epoch ring (subscription? internal ratom? registered fx?) is the spike's biggest deliverable.

3. **Confirm the nREPL + CLJS-eval round-trip** against the fixture. Prove `discover-app.sh` connects, switches to `:cljs` mode, and that the `{:results [<printed-cljs-value>]}` unwrap in `ops.clj` is actually the response shape shadow returns.

4. **re-com `data-rc-src` format verification.** `parse-rc-src` assumes `"file:line"` or `"file:line:column"` — real re-com may produce something else. Ten minutes once there's a fixture with `:src (at)` somewhere.

5. **10x undo adapter** (time-travel). Without this the experiment loop is lame — you can patch-and-re-dispatch, but can't rewind `app-db` to an identical starting state. Requires understanding 10x's internal epoch-navigation events and dispatching into its internal bus.

6. **Live-watch transport decision.** Pull-mode works as designed. Streaming-via-`:out` is flagged unproven (spec §4.4); decide whether to investigate or lock in pull-mode as v1.

7. **Version-var discovery** — where each lib (re-frame, 10x, re-com, shadow-cljs) exposes its version in the browser. Twenty minutes of grepping each library's source. Fill `version-floors`, enforcement goes live.

8. **Wire `tests/runtime/` into a real shadow-cljs test build** so `runtime_test.cljs` runs in CI per-push.

Everything above is one focused week of work against a real fixture.

---

## This repo — beyond the spike

- **Publish `v0.1.0-alpha.1`** once items 1–4 pass end-to-end. Tag `v0.1.0-alpha.1`; CI does the npm publish (see `RELEASING.md`).
- **Iterate on SKILL.md recipes** with real usage feedback. Some recipes will surface edge cases the spec didn't predict; SKILL.md will grow.
- **Build `tests/shim/` and `tests/prompts/`** (the two test surfaces `docs/TESTING.md` flags as not-yet-written) once the fixture exists.
- **Efficiency win — socket reuse** in `ops.clj`'s nREPL client. Watch loop opens ~20 sockets/sec today. Safe to defer until after alpha.
- **Clean up `dispatch-and-collect`** in `runtime.cljs` — dead code now (ops.clj uses a bash-side sleep). Delete or mark deprecated.

---

## Work on other repos — companion changes

None of these are prerequisites for `v0.1.0-alpha.1`, but each would reduce re-frame-pair's coupling to library internals or unlock a real recipe. Spec Appendix A has the full list; these three are the load-bearing ones:

| Repo | Change | Why it matters |
|---|---|---|
| [`day8/re-frame-10x`](https://github.com/day8/re-frame-10x) | **A2** — documented public namespace exposing epoch structures (epoch id, event vector, interceptor chain, app-db before/after, sub-runs, renders, timings) | Highest-leverage companion change. Without it, re-frame-pair pins against 10x internals that can move. If 10x exposes even a minimal read-only API — `(epochs)`, `(epoch-by-id)`, `(step-back!)` — the adapter in `runtime.cljs` becomes trivial and stable. Also fixes task #5 above in one step. |
| [`day8/re-com`](https://github.com/day8/re-com) | **A3** — thread `:src` through to the Reagent render-trace event | Removes the DOM-recency join in §4.3b; makes the DOM bridge exact instead of approximate. |
| [`day8/re-frame`](https://github.com/day8/re-frame) | **A7** — retained handler source forms in dev builds | Makes `registrar/describe` return real source; makes the verify-patch-landed checkpoint in the experiment loop *actually* verify what the handler does, not just its fn reference. |

Lower-priority but worth filing so the agenda is public:

- **re-frame A1** — documented `re-frame.introspect` public namespace (currently reaching into `re-frame.registrar` / `re-frame.subs` / `re-frame.db` directly).
- **re-frame A4** — `register-epoch-cb` alongside `register-trace-cb`. Would promote *epoch* to a canonical re-frame concept rather than a 10x-only construct.
- **re-frame A5** — subscription graph as data. Unlocks "show me everything that would re-run if I changed this sub" recipes.
- **re-frame A6** — dispatch provenance. Fixes Claude-dispatch tagging for queued dispatches too (currently sync-only).

See spec §A for full descriptions.

---

## Suggested sequencing

- **This week** (you driving, the spike): fixture → items 2–5 → cut `v0.1.0-alpha.1`.
- **Then**: dogfood against a real day8 app; see what hurts; iterate on SKILL.md recipes.
- **Next month** (optional): file A2 / A3 / A7 issues in 10x / re-com / re-frame as "proposals originating from `day8/re-frame-pair`", linking to spec Appendix A. Continue using re-frame-pair against internals in the meantime.
- **`v0.1.0` proper**: when A2 lands (or we accept permanent coupling), the experiment loop works end-to-end with real undo, and the four test surfaces in `docs/TESTING.md` all go green.

---

## Post-mortem workflows (unlocked once item 2 lands)

One of the highest-value use cases re-frame-pair enables — walking back through recorded events to figure out how an app ended up in a broken state — depends entirely on the 10x epoch-buffer accessor being wired (item 2 of the critical path). Today, `read-10x-epochs` returns `[]` and therefore `trace/find-where`, `trace/recent`, `trace/last-epoch`, and the *"Post-mortem — how did we get here?"* recipe all succeed structurally but return no data. Once the accessor is live, this workflow works on day one — no further work needed beyond whatever polish SKILL.md's recipe language picks up from real usage.

Worth flagging as a load-bearing first-release demo: *"my app is stuck in a bad state and I don't know how I got here"* → Claude walks the epoch history → identifies the culprit event. No other ClojureScript tooling does this.

---

## Degrees of "usable"

| Milestone | What it requires | Release |
|---|---|---|
| **Demo-able** | Items 1–4 of the critical path. Trace ops return real epochs against the fixture. | `v0.1.0-alpha.1` |
| **Pleasantly usable** | All 8 critical-path items. Experiment loop works with real undo. DOM bridge exact against current re-com. | `v0.1.0-beta.1` |
| **Production-ready** | A2 landed in re-frame-10x (or permanent-coupling posture accepted). All four test surfaces green. Skill-prompt regression in CI. | `v0.1.0` |

---

*Last updated: 2026-04-21.*
