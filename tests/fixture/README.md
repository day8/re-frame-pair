# tests/fixture — minimal re-frame-pair test subject

A small shadow-cljs + re-frame + re-frame-10x + re-com app used to exercise
the skill end-to-end. Built and validated against runtime.cljs as of v0.1.0-beta.2.

## Run it

```bash
cd tests/fixture
npm install                 # first run only — fetches react, react-dom, shadow-cljs
npx shadow-cljs watch app   # foreground; serves http://localhost:8280, nREPL on :8777
```

Then open http://localhost:8280. The page mounts re-frame-10x's devtools panel
(toggle with **Ctrl-H**) and renders the fixture views (counter, items, coupon,
cart-summary, broken-handlers, footer). The CLJS runtime starts with the first
browser tab; without a tab, nREPL is up but `app-db` isn't initialised.

## Layout

```
tests/fixture/
├── deps.edn                # :local/root re-frame, re-frame-10x, re-com (siblings under ~/code)
├── shadow-cljs.edn         # build :app — nREPL on 8777, dev-http on 8280, 10x preload, trace-enabled? + goog.DEBUG closure-defines
├── package.json            # react, react-dom, shadow-cljs (npm side)
├── public/
│   ├── index.html          # links bootstrap, re-com, mdi CSS bundles
│   └── css/                # bootstrap.css, re-com.css, material-design-iconic-font.min.css (committed for self-contained dev)
└── src/app/
    ├── db.cljs             # initial-db
    ├── events.cljs         # 9 handlers, mix of reg-event-db / reg-event-fx
    ├── subs.cljs           # Layer 2 (counter, items, coupon, events-fired) + Layer 3 (cart-summary)
    ├── views.cljs          # re-com components with `:src (at)` on every call site
    └── core.cljs           # entry point; `(rf/dispatch-sync [:initialize])` then mount
```

## What each piece exercises

The fixture is intentionally narrow — every panel and handler shape maps to a
specific runtime code path the spike validates against. See
`docs/initial-spec.md` §8a for the full spike charter; this README maps the
charter onto the fixture surface.

| Fixture piece | Exercises |
|---|---|
| `:counter/inc` / `:counter/dec` / `:counter/reset` | basic `reg-event-db` root-key mutation; the simplest dispatch → diff path |
| `:item/inc-qty` | path-based update inside a vector — interesting `:app-db/diff` shape via `clojure.data/diff` |
| `:coupon/apply` | `reg-event-fx` with chained `:dispatch` to `:analytics/track` — exercises `coerce-epoch`'s `:effects/fired` flattening AND the rfp-l7m C fix that the trace returns the user-fired event, not the chained one |
| `:broken/throw` | the experiment-loop recipe target — handler throws; `tagged-dispatch-sync!` catches and returns `{:ok? false :reason :handler-threw}` (rfp-l7m D) |
| `:broken/non-map` | handler returns a vector — app-db becomes corrupt; runtime stays alive and the diff captures the `[:not :a :map]` shape |
| `:cart-summary` (Layer 3 sub of `:items` + `:coupon`) | the "why didn't my view update?" recipe — non-trivial sub chain to walk |
| `:counter` view | re-com `h-box` with `+` / `−` / `reset` buttons + `:src (at)` — exercises DOM bridge + `:re-com? :re-com/category` annotation in `:renders` |

## What this fixture does NOT cover

- **Real network effects** (`:http-xhrio`, fetch) — `:coupon/apply`'s `:dispatch` is the only async-ish behaviour.
- **Multiple build IDs** — the fixture has a single `:app` build.
- **Production-mode build** — only `:dev` is exercised; `npx shadow-cljs release app` works but isn't part of the spike.
- **End-to-end edit-then-reload** — Phase 5 (hot-reload coordination) probe protocol is unit-tested but not exercised live yet.

## Validating against the fixture

From the project root, with the watch running and a browser tab open:

```bash
cd ~/code/re-frame-pair
scripts/discover-app.sh                                          # health report
scripts/eval-cljs.sh '(re-frame-pair.runtime/snapshot)'          # round-trip
scripts/dispatch.sh --trace '[:counter/inc]'                     # full §4.3a epoch
scripts/watch-epochs.sh --count 3                                # then click + 3 times in browser
```

See `STATUS.md` for the full per-phase verification record and `SKILL.md` for
the operations vocabulary the runtime exposes.
