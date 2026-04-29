# Implementation status

A forward-looking snapshot of where the project stands now and what's next. Past changes live in git history and the GitHub Releases. Per-bead context lives in beads (`bd show <id>`); insights live in bead memories (`bd memories`).

For the design this is measured against, see [`docs/initial-spec.md`](docs/initial-spec.md).

---

## TL;DR

| Area | State |
|---|---|
| `SKILL.md` (~100 lines) + `docs/skill/*.md` recipes (including `debux.md`) | Operator + AI-facing narrative; reviewed for efficiency / completeness / correctness / clarity / structure / duplication |
| `scripts/re_frame_pair/runtime/*.cljs` + facade `runtime.cljs` | Native epoch + trace ring buffers (`re-frame.core/register-epoch-cb`, rf-ybv) with 10x fallback; `dispatch-and-settle` / `dispatch-with` / source-meta consumers; auto-reinject after browser refresh |
| `scripts/ops.clj` + shell shims | Babashka dispatches every op; `dispatch.sh --trace` routes through `dispatch-and-settle`; `--stub` flag for record-only fx overrides |
| `.claude-plugin/plugin.json` | Written |
| `package.json` + GitHub Actions (CI + release) | CI runs all three test suites per push; release workflow tags GitHub Releases on `v*` tag push |
| `tests/runtime/` unit tests | **121 deftests / 609 assertions / 0 failures** (CLJS via shadow-cljs `:node-test`) |
| `tests/ops_smoke.bb` | **57 deftests / 154 assertions / 0 failures** (babashka, ops.clj load-path + pure helpers) |
| `tests/skill_recipe_smoke.bb` | **10 deftests / 36 assertions / 0 failures** (SKILL.md / recipe contract pins) |
| `tests/fixture/` sample app | Minimal re-frame + 10x + re-com app on shadow-cljs `watch app`; bundled bootstrap + re-com CSS for self-contained rendering; `:counter/inc` wrapped with `fn-traced` for the form-by-form trace recipe |
| End-to-end against the live fixture | Verified — full §4.3a epoch shape (event, diff, effects, coeffects, interceptor-chain, subs/ran, subs/cache-hit, renders, timing) produced for UI clicks; all 5 watch predicate filters validated; time-travel rolls userland app-db correctly |
| Releases | `v0.1.0-beta.4` is the current GitHub Release (prerelease, tag pushed 2026-04-30); npm publish is gated on the `NPM_TOKEN` secret being configured |

---

## Per-phase status (against [`docs/initial-spec.md`](docs/initial-spec.md) §6)

| Phase | Deliverable | State | Notes |
|---|---|---|---|
| 0 | `eval-cljs.sh` round-trips a form | **Verified** | `(re-frame-pair.runtime/snapshot)` returns the full app-db. |
| 1 | Read surface (§4.1) | **Verified** | `snapshot`, `app-db-at`, `schema`, `registrar-list`, `registrar-describe`, `subs-live`, `subs-sample` callable against the fixture. |
| 2 | Dispatch + trace (§4.2–§4.3) | **Verified** | `dispatch.sh --trace` returns the user-fired event's epoch with all §4.3a fields populated; `--trace` routes through `dispatch-and-settle` (rf-4mr); `--stub` for per-dispatch fx-handler substitution (rf-ge8). |
| 3 | Live watch (§4.4) | **Verified** | All 5 predicate filters (`--event-id`, `--event-id-prefix`, `--effects`, `--touches-path`, `--timing-ms`) match expected counts. Pull-mode at 100ms cadence. Verified holds when buffered epochs include `:debux/code` from `fn-traced` handlers — fn `:result` values are stringified during coercion so the EDN-via-nREPL round-trip survives. |
| 4 | Hot-swap (REPL) | **Verified** | `reg-event` / `reg-sub` / `reg-fx` via `eval-cljs.sh` work; experiment-loop recipe end-to-end relies on this. |
| 5 | Hot-reload coordination (§4.5) | **Coded; live verification pending** | `tail-build.sh` probe-based protocol implemented and unit-tested; no real edit→reload cycle has been run yet. |
| 6 | Time-travel (§4.6) | **Verified** | `undo-step-back` / `undo-most-recent` against the fixture; gated correctly on `:app-db-follows-events?`. |
| 7 | Diagnostics recipes (§4.7) | **Verified** | Recipes exercised end-to-end; SKILL.md narrative reviewed against six independent angles. |
| 8 | Packaging | **Verified for git-clone install; npm pending** | CI green; GitHub Release published per tag; `prepublishOnly` gate present. npm publish step is commented-out pending operator-side `NPM_TOKEN` configuration. |

---

## Verified surfaces

- All accessor namespaces and shapes `runtime.cljs` reaches into (re-frame, re-frame-10x, re-com).
- `re-frame.trace/trace-enabled?`, `re-frame.registrar/kind->id->handler`, `re-frame.subs/query->reaction` exist and are usable in current shape.
- `re-frame.core/register-epoch-cb` + `assemble-epochs` (rf-ybv) — runtime drains assembled epochs into a native ring buffer; `coerce-native-epoch` translates to §4.3a shape.
- `re-frame.core/dispatch-and-settle` (rf-4mr) — fire-and-await; runtime wraps it (Promise can't round-trip cljs-eval) and reconstitutes the settled epoch from the native buffer.
- `re-frame.core/dispatch-with` / `dispatch-sync-with` (rf-ge8) — per-dispatch fx-handler substitution via `:re-frame/fx-overrides` event-meta.
- `re-frame.core-instrumented` (re-frame 1.4.7) — source-meta-capturing macro mirror of `re-frame.core`. Renamed from `re-frame.macros` (1.4.6 alias). Source-meta lands in `:event/source`, `:subscribe/source`, `:input-query-sources`.
- `day8.re-frame-10x.public` (rf1-jum) — preferred over inlined-rf walking when loaded; legacy walker kept as a fallback for older 10x JARs.
- re-com's `:src` format and debug gate (`re-com.config/debug?` = `^boolean js/goog.DEBUG`).

---

## Distribution

- **Git clone + `docs/LOCAL_DEV.md`** is the supported install path today. README and LOCAL_DEV cover global / project-local / copy install patterns.
- **npm publish is gated** on `NPM_TOKEN` being configured at the rfp repo. The `Publish to npm` step is commented out in `release.yml`; the comment explains the one-line restore once the secret lands. Tracked: bead **rfp-sugy**.
- **Slack notifications** similarly gated on `SLACK_WEBHOOK`. Tracked: bead **rfp-sugy**.

---

## Open design questions

(Numbering matches [`docs/initial-spec.md`](docs/initial-spec.md) §8.)

1. **Authorization for writes** — should `app-db/reset` and handler hot-swap require an explicit user-confirmation prompt, or is the SKILL.md cardinal rule (REPL changes ephemeral, source edits stick) the right safety net? Lean confirm-for-v1.
2. **`app-db/schema` convention** — is `(get @app-db :re-frame-pair/schema)` the right opt-in, or should rfp sniff malli/spec registries directly? Revisit when first real-world `:schema` use is observed.
3. **Hot-reload probe fallback delay** — the post-"Build complete" 300ms default is a guess. Confirm empirically once the Phase 5 live edit→reload cycle has run on real machines.
4. **Re-com / 10x version-floor enforcement** — re-frame now exposes a runtime `version` constant (1.4.6+); re-com and re-frame-10x still need their own. Floors stay nil until they ship.

---

## Next actions

### Near-term

1. **Phase 5 live verification** — run an actual edit-then-reload cycle end-to-end (`Edit` a fixture handler, `tail-build.sh --probe '<form>'`, dispatch, observe epoch). Probe protocol is coded and unit-tested but no real cycle has run yet.
2. **Real-world day8 app exercise** — point re-frame-pair at a production day8 app (not just the fixture) and run the SKILL recipes. Catches anything the fixture's narrow surface doesn't cover.
3. **Bash-shim E2E in CI** — current smoke job only validates shebangs and `unknown-subcommand` parsing. Add a job that boots the fixture (or a mock nREPL listener) and asserts the EDN shape returned by `discover-app.sh` + `dispatch.sh --trace`.
4. **Babashka-side test coverage** — `tests/ops_smoke.bb` lands 57 deftests but is still missing: bencode encode/decode roundtrip (property-test via `test.check`), `parse-predicate-args` flag-combination matrix, `read-port`'s candidate-cascade.
5. **`docs/initial-spec.md` §4 ops** are now superseded by SKILL.md + the runtime source. Either trim §4 to a one-paragraph signpost or move the canonical ops definitions into the spec and have SKILL.md link there.

### Deferred (v0.2+)

- **Headless Playwright E2E rig** — ~6 weeks of work; replaces operator-driven fixture validation with automated browser-driven test. Right tool for full release-gate confidence.
- **npm OIDC trusted publisher + `--provenance`** — release.yml currently uses a bare `NODE_AUTH_TOKEN` (and is currently gated off entirely). Switch to OIDC + `--provenance` when re-enabling npm publish.
- **Watch streaming-via-`:out` transport** — currently pull-mode at 100ms. Streaming reduces round-trips for long watches; spec §4.4 sketches the mechanism but reachability questions remain.
- **Drop legacy 10x-buffer + sleep paths** — once every supported re-frame ships rf-ybv (`register-epoch-cb`) and rf-4mr (`dispatch-and-settle`), the legacy `read-10x-epochs` walker and the `tagged-dispatch-sync!` + sleep fallback can both go.
- **Inlined-rf version path** — `inlined-rf-version-paths` is a hard-coded enumeration; probe `js/goog.global.day8.re_frame_10x.inlined_deps.re_frame` keys at runtime as the canonical source. Less load-bearing now that `day8.re-frame-10x.public` is preferred where loaded.
- **`tests/fixture/public/css/`** — 8.4k lines of bootstrap.css inflate the repo. Move to CDN link or generate at build time. Tradeoff: loses offline / air-gapped dev story.

### Tracking

`bd list --status=open` is the canonical backlog. Categories are correctness / test-coverage / docs-polish; structural Gas City beads run alongside.
