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

Working tree is ~30 commits past `v0.1.0-beta.2` and represents the
next tag's body of work.

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
