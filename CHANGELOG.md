# Changelog

All notable user-visible changes to **re-frame-pair** are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versioning aims at [SemVer](https://semver.org/) once the skill leaves beta.

> **Scope.** Operator/agent-facing changes only — SKILL.md vocabulary,
> shell ops, response shapes, and runtime invariants. Internal
> refactors and CI tweaks are kept out unless they change observable
> behaviour. For the full per-commit history, see `git log`; for
> design rationale see `STATUS.md` and `docs/initial-spec.md`.

## [Unreleased]

Nothing yet.

## [0.1.0-beta.7] - 2026-05-05

### Added

- **`:version` and `:version-check` on every `discover-app.sh` response.** `discover` reads the local skill version from `package.json` and surfaces it on every emit (success AND structured-failure paths) — operators reporting bugs always see what version they're on. When the local version doesn't match the latest GitHub release, `:version-check` carries `{:status :stale :latest "..." :released "..." :changelog "..."}`. Fail-soft (network down / rate-limited → `:status :unknown`, never blocks discover); 24h cache at `$XDG_CACHE_HOME/re-frame-pair/version-check.edn`; opt out via `RE_FRAME_PAIR_SKIP_VERSION_CHECK=1`.

### Changed

- **`discover-app.sh` no longer requires `re-frame-10x`** when the native epoch callback (`re-frame.core/register-epoch-cb`, rf-ybv, re-frame ≥ 1.4) is available. Useful for stacks using `re-frisk` instead of 10x, or no panel-based dev tooling at all. Refusal still fires when both 10x AND the native cb are absent; the `:hint` now names both alternatives. Closes [#15](https://github.com/day8/re-frame-pair/issues/15).

- **`tail-build.sh --probe` detects every error↔value transition as a flip**, not just value↔value. The previous comparator missed the canonical add-new-form pattern (probe form errors before the reload due to undeclared var; returns a value after) and timed out with a misleading "compile error in your dev build" hint. The timeout hint is also bifurcated: probe still erroring → original "compile error / broken probe" guidance; probe stable but never differed from baseline → new guidance pointing at probe-form choice. Closes [#18](https://github.com/day8/re-frame-pair/issues/18).

### Fixed

- **`eval-cljs.sh` no longer returns `{:ok? true :value :repl/exception!}`** for forms that touch `re-frame.core/dispatch` or `subscribe`. shadow-cljs's printer-failure sentinel (`:repl/exception!`) used to pass through as a value with `:ok? true` — same false-trust class as #6, one layer down. Now surfaces as `{:ok? false :reason :repl-exception :hint "..." :data {:raw-response ...}}` with a hint at workarounds (`scripts/dispatch.sh` for dispatch flows; deref the underlying ratom for subscribe reads). The deeper wire/return! fix that would pre-stringify re-frame internal types (so shadow's printer never sees them) is a tracked follow-up. Closes [#17](https://github.com/day8/re-frame-pair/issues/17).

- **`trace-recent.sh` surfaces non-integer args as structured `{:reason :bad-arg :got <input> :hint "..."}`** instead of leaking a bare Java `NumberFormatException` stack to stderr. The hint specifically names the milliseconds-vs-count gotcha (the natural mistake — most CLI tools take a count). Closes [#16](https://github.com/day8/re-frame-pair/issues/16).

Plus two new `:reason` values (`:bad-arg`, `:repl-exception`) added to `docs/skill/troubleshooting.md` so agents have known translations.

## [0.1.0-beta.6] - 2026-05-04

### Fixed

- **`eval-cljs.sh` no longer silently returns `{:ok? true :value nil}` for valid forms.** Shadow's nREPL response wraps its real result inside the top-level `:value` field as a serialized map (`{:results [...] :err "..." :ns ...}`); when `:results` was empty, the actual error sat in the inner `:err` and the parser dropped it. The caller saw `:ok? true :value nil` — a false trust signal — for forms like `(+ 1 2)` whenever the runtime wasn't actually attached. Two complementary fixes:
  - `parse-cljs-eval-response` now surfaces buried inner `:err`. `"No available JS runtime."` becomes `{:reason :browser-runtime-not-attached :hint "Open the app in a browser tab... If a tab is already open, hard-refresh it (Ctrl+Shift+R)..."}`. Compile warnings (`:undeclared-ns`, `:undeclared-var`) become `{:reason :cljs-eval-error :err <verbatim text>}`.
  - `cljs-eval-wire` defensive layer: top-level `:value` blank with no `:err` and no `:ex` becomes `{:reason :cljs-eval-empty :raw-response <res>}` for shadow shapes the parser doesn't classify yet.

  Closes [#6](https://github.com/day8/re-frame-pair/issues/6).

- **Inject failures no longer silently leave the runtime ns absent.** `chunked-inject!` reported success when shadow's recompile errors (e.g. `:shadow.build.resolve/missing-ns`) didn't match the legacy `Syntax error|CompilerException|FileNotFoundException` regex; downstream ops then returned bare `nil`. Two fixes:
  - `inject-failure` now also matches `:shadow.build.resolve/missing-ns` and `The required namespace ... is not available` patterns, surfacing as `{:reason :inject-failed :stage :compile :hint ...}` with a pointer at `npm test:ops`'s load-order topology check.
  - `ensure-injected!` re-probes the runtime sentinel after the chunked ship + health call. If the sentinel still says "not injected" despite no recognized error, dies `{:reason :inject-failed :stage :verify :hint "...check the watch terminal..."}`. Catch-all for any silent-failure shape the classifier hasn't seen.

  Closes [#7](https://github.com/day8/re-frame-pair/issues/7).

- **`discover-app.sh` no longer silently picks a default build that isn't active.** Pre-flight probe of `list-builds-on-port` — when no `--build=` is given AND no `SHADOW_CLJS_BUILD_ID` is set AND the default build (`:app`) isn't in the candidate list, returns `{:reason :ambiguous-build :candidates [...] :picked-default :app :hint "Pass --build=<id>..."}` instead of attempting an inject that would fail or return nil. Existing multi-build warning (when the default IS in the candidate list) is preserved.

  Closes [#9](https://github.com/day8/re-frame-pair/issues/9).

- **`discover-app.sh` no longer crashes mid-flight on non-shadow-cljs builds.** Pre-flight probe for `shadow.cljs.devtools.api`. On figwheel-main / lein-cljsbuild / vanilla cljs nREPLs, returns `{:reason :unsupported-build-tool :hint "...currently requires shadow-cljs..."}` instead of the previous misleading `:nrepl-port-not-found` or undefined-ns crash on the first shadow API call. SKILL.md gains a top-of-body callout naming the shadow-cljs requirement explicitly.

  Closes [#14](https://github.com/day8/re-frame-pair/issues/14).

### Added

- **`:ten-x-mounted?` flag** in the health response, distinct from `:ten-x-loaded?`. The latter means "the JS namespace is loaded into the bundle"; the former is a DOM probe for re-frame-10x's mount node `<div id="--re-frame-10x--">`. They're independent — `loaded? true` with `mounted? false` is a real failure mode (most commonly: dev `<script>` tag in `<head>` without `defer`, so `document.body` is null at preload time and the panel never renders). Diagnostic recipe in `docs/skill/troubleshooting.md`.

  Closes [#10](https://github.com/day8/re-frame-pair/issues/10).

### Changed

- **Recommended `re-frame-10x` version bumped to `1.12.1`.** That release ships an auto-detecting default preload — consumers on React 18 (the typical configuration today) no longer need `:preloads [day8.re-frame-10x.preload.react-18]` explicitly to silence the React-17/18 mismatch warning or React's own `ReactDOM.render is no longer supported` deprecation. The bare `day8.re-frame-10x.preload` does the right thing on React 17 and React 18. Both the `Install` table and the copy-paste deps block in README point at it.

## [0.1.0-beta.5] - 2026-04-30

### Fixed

- **Eval channel no longer silently truncates large CLJS responses
  past shadow-cljs's ~1 MB printer cap.** Previously, ops that
  produced a large value (a `:report/loaded` event payload, an
  `app-db/snapshot` of a real app's state, an epoch whose
  `:effects/db` snapshotted a sizable subscription tree) returned
  `{:ok? true :value nil}` to the caller while the browser console
  threw `The limit of 1048576 bytes was reached while printing`. The
  failure mode was asymmetric and silent — the runtime *thought* it
  had succeeded.

  Every cljs-eval response is now bounded by a wire-safe summary +
  cursor protocol. Trivial responses pass through bare; non-trivial
  responses come back as
  `{:rfp.wire/cursor "<id>" :rfp.wire/value <maybe-elided>
   :rfp.wire/elisions [{:path [...] :type :map :count 84 ...}]}`.
  Oversized branches are replaced *in place* with type-aware
  `{:rfp.wire/elided true ...}` markers carrying their own cursor.
  The full value stays available in a session-keyed bounded LRU on
  the runtime side; drill into a slice with
  `eval-cljs.sh '(re-frame-pair.runtime.wire/fetch-path "<cursor>" [<path>])'`.

  Closes [#4](https://github.com/day8/re-frame-pair/issues/4) (bead
  rfp-zw3w). Phases 1+2 of the design landed in 75d2297; SKILL.md
  documentation in afbff6a. Phases 3–5 (wire CLI sugar, epoch-aware
  projections, question-shaped vocabulary) deferred — the bug is
  fixed and remaining work is product polish.

## [0.1.0-beta.4] - 2026-04-30

### Fixed

- **`watch-epochs.sh` no longer silently emits `0` matches when a
  buffered epoch carries `:debux/code` from a `fn-traced` handler.**
  CLJS prints captured fn values as `#object[name]`; the JVM EDN
  reader couldn't parse those, `safe-edn` fell through to a raw
  string, and the watch loop's `(:matches result)` returned `nil` —
  the watch terminated reporting `:emitted 0` despite matching epochs
  in the buffer. `coerce-epoch` now stringifies fn values inside
  `:debux/code` so the coerced epoch survives the EDN-via-nREPL
  round-trip. Operators see the same `#object[name]` printed form
  they'd see in 10x's UI; the value is just a plain string.

### Changed

- **Source-meta opt-in namespace renamed.** Tracking the upstream
  re-frame 1.4.7 rename: the macro mirror that captures call-site
  source metadata is now `re-frame.core-instrumented` (and
  `re-frame.alpha-instrumented` for the alpha API) — re-frame 1.4.6's
  `re-frame.macros` no longer exists. Host apps that opted into
  source-meta on beta.3 should sweep their `:require` lines:
  `[re-frame.core :as rf]` →
  `[re-frame.core-instrumented :as rf]`. SKILL.md and
  `docs/skill/source-meta.md` cover the offer-the-swap heuristics.

## [0.1.0-beta.3] - 2026-04-29

### Added

- **Native epoch path.** Runtime now installs its own
  `re-frame.core/register-epoch-cb` (upstream `rf-ybv`) and consumes
  assembled epochs directly. 10x's epoch buffer is kept as a fallback
  for fixtures running re-frame predating `rf-ybv`.
- **`dispatch-and-settle` for `--trace`.** `scripts/dispatch.sh
  --trace` now routes through `re-frame.core/dispatch-and-settle`
  (upstream `rf-4mr`) — adaptive quiet-period replaces the prior
  fixed 80 ms sleep over `:fx [:dispatch ...]` cascades.
- **`dispatch-with --stub` for safe iteration.** New flag on
  `scripts/dispatch.sh`: per-dispatch fx-handler substitution via
  `:re-frame/fx-overrides` event-meta (upstream `rf-ge8`).
  Repeatable: `--stub :http-xhrio --stub :navigate`. SKILL.md's
  experiment-loop recipe gains a *Side-effecting handlers* subsection.
- **Source / call-site flattening on epochs.** Three upstream waves
  (`rf-ysy`, `rf-hsl`, `rf-cna`) attach `:re-frame/source`
  `{:file :line}` at handler registration, dispatch, and subscribe
  call sites. `coerce-epoch` and `coerce-native-epoch` flatten these
  onto coerced records as `:event/source` (dispatch site),
  `:subscribe/source` and `:input-query-sources` (per `:subs/ran`).
- **`re-frame-debux` Phase 1 + Phase 2 integration.**
  - Phase 1: `coerce-epoch` surfaces `:debux/code` from `:tags :code`;
    SKILL.md recipe *Trace a handler/sub/fx form-by-form* (5-step
    manual `fn-traced` wrap).
  - Phase 2: SKILL.md recipe gains a PREFERRED branch via
    `wrap-handler!` / `unwrap-handler!` (upstream debux runtime API);
    FALLBACK kept for older debux. `debux-runtime-api?` predicate
    selects the path. New recipe *Trace a single expression at the
    REPL* (debux `dbg` macro).
- **`re-frame-10x` public surface preferred.** `read-10x-all-traces`,
  `read-10x-epochs`, `latest-epoch-id`, and `epoch-count` now probe
  `day8.re-frame-10x.public` first (upstream `rf1-jum`). Legacy
  inlined-rf walking remains as fallback.
- **Auto-reinject re-installs runtime callbacks.** After a browser
  refresh, `ensure-injected!` follows the runtime re-ship with
  `(re-frame-pair.runtime/health)` so native epoch / trace / console
  / last-click capture are wired back up before the next op runs.
- **`re-frame.tooling` re-export namespace** consumed where it
  reduces import surface (in progress; see beads `rf-5rpc` upstream
  and the rfp consumption migration).
- **`SKILL.md` cardinal rule** now names a real shell op
  (`scripts/tail-build.sh --probe`) instead of a non-existent
  `hot-reload/wait` label.
- **CHANGELOG.md** (this file).

### Renamed

- `scripts/trace-window.sh` → `scripts/trace-recent.sh`. The shell
  shim now matches the SKILL.md `trace/recent` op name and the
  ops.clj `trace-recent` subcommand. Same behaviour. External
  callers should update their script paths.

### Changed

- **`handler/source` mechanism.** Upstream `rf-ysy` made
  `re-frame.core/reg-*` source-meta-capturing; the local opt-in
  side-table that previously seeded the response is retired. Same
  response shape; different invariants (works for any handler
  registered through `re-frame.core/reg-*`, no opt-in required).
- **Trace-table heading** in SKILL.md updated for the native epoch
  path replacing 10x as primary read source.
- **One-time deprecation warning on inlined-rf fallback.** When
  re-frame-10x is loaded but its `day8.re-frame-10x.public` ns
  (upstream `rf1-jum`) isn't, the runtime now emits a single
  `console.warn` the first time it falls back to the inlined-rf
  walker. The fallback still works; the warning is the signal that
  upgrading 10x to rf1-jum or newer is the supported path and that
  the legacy walker may be removed in a future release.

### Fixed

- **Legacy `--trace --stub` safety on pre-`rf-4mr` builds.** When
  re-frame predates `dispatch-and-settle`, the legacy fallback path
  now routes through `dispatch-sync-with-stubs!` so record-only fx
  overrides remain active. Previously `--stub` was silently dropped
  on old builds and the real handler fired. If `rf-ge8` is also
  absent, returns a structured failure rather than pretending the
  safety wrapper ran.
- **Direct-run docs** in `tests/ops_smoke.bb` now mention the
  required `OPS_NO_AUTO_RUN=1` env var.

## [0.1.0-beta.2] — 2026-04-26 (un-tagged on main)

Spike concluded — all `docs/initial-spec.md` §8a ground-truth
unknowns resolved. Runtime + fixture validated end-to-end against a
live re-frame app. CI green.

### Added

- Phases 1–8 of `docs/initial-spec.md` §6 verified.
- Read ops: `app-db/get`, `subs/sample`, `registrar/list`,
  `registrar/describe`, `subs/list`, `app/summary`, `handler/source`.
- Write ops: `dispatch.sh --sync`, `app-db/reset`, `eval-cljs.sh`.
- Trace ops: `dispatch.sh --trace`, `watch-epochs.sh`,
  `trace-recent.sh`, `find-where`.
- Hot-reload coordination: `tail-build.sh --probe`.
- Time-travel: 10x `undo` / `redo` integration.
- Diagnostics recipes assembled from primitive ops.

## [0.1.0-beta.1] — 2026-04-21

Initial publishable release. Plugin manifest + skill installable via
`/plugin install re-frame-pair@day8`. See `docs/initial-spec.md` for
the design baseline.
