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
    ├── events.cljs         # 12 event handlers plus a fixture-local reg-fx
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
| `:test/log` | fixture-local `reg-fx` target (`:test/log-message`) for the `dispatch.sh --stub` recipe — the real effect records to `app.events/test-fx-log`, while stubs record to `re-frame-pair.runtime/stub-effect-log` |
| `:test/log-then-dispatch` | emits `:test/log-message` and then dispatches `:test/log-child`, so `--trace --stub :test/log-message` exercises effect substitution across a dispatch cascade |
| `:broken/throw` | the experiment-loop recipe target — handler throws; `tagged-dispatch-sync!` catches and returns `{:ok? false :reason :handler-threw}` (rfp-l7m D) |
| `:broken/non-map` | handler returns a vector — app-db becomes corrupt; runtime stays alive and the diff captures the `[:not :a :map]` shape |
| `:cart-summary` (Layer 3 sub of `:items` + `:coupon`) | the "why didn't my view update?" recipe — non-trivial sub chain to walk |
| `:counter` view | re-com `h-box` with `+` / `−` / `reset` buttons + `:src (at)` — exercises DOM bridge + `:re-com? :re-com/category` annotation in `:renders` |

## What this fixture does NOT cover

- **Real network effects** (`:http-xhrio`, fetch) — `:test/log-message` is a safe fixture-local effect for `--stub` validation, not a network adapter.
- **Multiple build IDs** — the fixture has a single `:app` build.
- **Production-mode build** — only `:dev` is exercised; `npx shadow-cljs release app` works but isn't part of the spike.
- **End-to-end edit-then-reload** — Phase 5 (hot-reload coordination) probe protocol is unit-tested but not exercised live yet.

## Optional re-frame-debux integration (rfp-mkf)

The fixture's `deps.edn` declares `day8.re-frame/tracing` via `:local/root`
so the spike can validate the `:debux/code` bridge in `runtime.cljs` against
a real `fn-traced` handler — until rfp-mkf landed, that bridge was only
covered by synthetic-data unit tests.

`src/app/events.cljs` wraps `:counter/inc` with `day8.re-frame.tracing/fn-traced`
as the worked example. Dispatch produces an epoch whose `:debux/code` is a
non-nil vec of per-form trace records (see re-frame-debux's
`send-trace!` schema in `common/util.cljc`).

**Downstream apps templating off this fixture** would need their own
`day8.re-frame/tracing` dep; nothing in re-frame-pair brings it transitively
(re-frame-10x intentionally doesn't depend on tracing). Recommended coords:

```clojure
;; deps.edn (downstream app)
{:deps {day8.re-frame/tracing {:mvn/version "<latest>"}}}
;; or, to track current development:
{:deps {day8.re-frame/tracing {:local/root "/path/to/re-frame-debux"}}}
```

The `closure-defines` already in `shadow-cljs.edn`
(`day8.re-frame.tracing.trace-enabled? true`) gate the trace emit path; without
those, `fn-traced` expands to a plain `fn` and emits nothing.

> **Note for spike developers:** adding `day8.re-frame/tracing` to `deps.edn`
> requires restarting `npx shadow-cljs watch app` — shadow caches its
> classpath at startup and `reload-deps!` is `:standalone-only`. The
> `npm test` suite (synthetic data) verifies the bridge logic itself; the
> live-fixture validation needs a fresh watch.

### Operator-runnable smoke for the wrap-handler! recipe

`src/app/wrap_handler_smoke.cljs` exercises the wrap → dispatch-sync →
unwrap cycle against the live `day8.re-frame.tracing.runtime` API.
SKILL.md prescribes that recipe as the primary path for "trace a whole
handler / sub / fx form-by-form"; the smoke is the operator-runnable
counterpart to the textual contract test that runs in `npm test` (see
`tests/skill_recipe_smoke.bb`).

```bash
scripts/eval-cljs.sh '(do (require (quote app.wrap-handler-smoke))
                          (app.wrap-handler-smoke/smoke!))'
;; => {:ok? true :id [:event :app.wrap-handler-smoke/probe]
;;     :runtime-api? true :hint "wrap → dispatch-sync → unwrap round-tripped cleanly"}
```

Returns `{:ok? false :reason ...}` if any step in the cycle breaks
(wrap returns the wrong shape, registrar drops the handler, unwrap
returns false, etc.) — each `:reason` keyword is enumerated in the
source.

## Safe custom effect for `--stub`

`src/app/events.cljs` registers `:test/log-message` with `rf/reg-fx`.
The real effect records payloads in `app.events/test-fx-log`; a stubbed
dispatch records the same payloads in `re-frame-pair.runtime`'s stub log
and leaves the fixture log empty.

With the watch running and a browser tab open, this exercises the bash shim
and the `dispatch-with` bridge against a real `reg-fx`:

```bash
scripts/eval-cljs.sh '(app.events/clear-test-fx-log!)'
scripts/eval-cljs.sh '(re-frame-pair.runtime/clear-stubbed-effects!)'
scripts/dispatch.sh --trace --stub :test/log-message '[:test/log-then-dispatch "hello"]'
scripts/eval-cljs.sh '(re-frame-pair.runtime/stubbed-effects-since 0)'
scripts/eval-cljs.sh '(app.events/test-fx-log-snapshot)'
```

Expected shape: `stubbed-effects-since` contains `:root` and `:child`
payloads for `:test/log-message`, while `test-fx-log-snapshot` returns
no entries. Running the same dispatch without `--stub` should invert that:
the fixture log receives the payloads and the stub log stays empty.

## Validating against the fixture

From the project root, with the watch running and a browser tab open:

```bash
cd ~/code/re-frame-pair
scripts/discover-app.sh                                          # health report
scripts/eval-cljs.sh '(re-frame-pair.runtime/snapshot)'          # round-trip
scripts/dispatch.sh --trace '[:counter/inc]'                     # full §4.3a epoch
                                                                 # — :debux/code is non-nil for this event (rfp-mkf)
scripts/dispatch.sh --trace --stub :test/log-message \
  '[:test/log-then-dispatch "hello"]'                             # stubbed custom fx across cascade
scripts/watch-epochs.sh --count 3                                # then click + 3 times in browser
```

See `STATUS.md` for the full per-phase verification record and `SKILL.md` for
the operations vocabulary the runtime exposes.
