# tests/fixture — minimal re-frame-pair test subject

A tiny shadow-cljs + re-frame + re-frame-10x + re-com app used to exercise
the skill end-to-end. Pre-alpha: structure only, no build wiring yet.

## Intended layout

```
tests/fixture/
├── deps.edn                # re-frame, re-frame-10x, re-com, reagent, shadow-cljs
├── shadow-cljs.edn         # build :app with nREPL + :preloads + :closure-defines
├── package.json            # react, react-dom
├── src/app/
│   ├── events.cljs         # a handful of reg-event-db / reg-event-fx
│   ├── subs.cljs           # Layer 2 + Layer 3 subs
│   ├── views.cljs          # re-com components with :src (at)
│   └── core.cljs           # entry point
└── public/index.html
```

## What the spike needs to prove against this fixture

See `docs/initial-spec.md` §8a:

1. **Runtime discovery.** `scripts/discover-app.sh` finds the nREPL port,
   switches to `:cljs` mode, verifies all preconditions, and injects
   `re-frame-pair.runtime`. Health report reflects reality.
2. **CLJS eval round-trip.** `scripts/eval-cljs.sh
   '(re-frame-pair.runtime/snapshot)'` returns the fixture's `app-db` as edn.
3. **Epoch extraction.** After dispatching `[:fixture/inc]`, `scripts/dispatch.sh
   --trace '[:fixture/inc]'` returns an epoch whose `:app-db/diff`,
   `:subs/ran`, and `:renders` are all populated from 10x's buffer alone
   (no second trace-cb). This is where the spike will reveal the exact
   10x internals `runtime.cljs` needs to reach for.
4. **Live-watch transport.** `scripts/watch-epochs.sh --count 3` reports
   three matching epochs as they fire when the user (or a driver script)
   exercises the fixture. Confirms whether streaming-via-`:out` works or
   whether we settle on pull-mode as v1.

## Writing the fixture

Blocked on the spike kicking off. When it does, the fixture should:

- Have an initial `app-db` with a few interesting keys to diff.
- Register at least one Layer 3 subscription that depends on two Layer 2
  inputs, so the "why didn't my view update?" recipe has something to walk.
- Include both happy-path and deliberately-broken handlers for the
  experiment-loop recipe to iterate on.
- Use re-com components with `:src (at)` at every call site so the DOM
  bridge has something to resolve.
- Have a visible render trail (buttons with counters) so the operator
  can see the state change.
