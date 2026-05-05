# Troubleshooting

Every script returns structured edn like `{:ok? false :reason ...}`. Translate the reason into plain English and report the suggested fix.

## Common Reasons

| Reason | What to tell the user |
|---|---|
| `:nrepl-port-not-found` | Start the dev build with `shadow-cljs watch <build>`. |
| `:unsupported-build-tool` | re-frame-pair currently only supports shadow-cljs builds. Figwheel-main / lein-cljsbuild / vanilla cljs aren't supported yet. Track at [#14](https://github.com/day8/re-frame-pair/issues/14). |
| `:browser-runtime-not-attached` | Open the app in a browser tab. If a tab is already open, hard-refresh it (Ctrl+Shift+R) — the bundle may be from a previous shadow-cljs process whose runtime IDs don't match the current one. |
| `:trace-enabled-false` | The build was not compiled with `re-frame.trace.trace-enabled?` true; this is a 10x requirement. |
| `:ns-not-loaded` | re-frame-10x, re-com, or another required namespace is not loaded. Check deps and preloads. |
| `:version-too-old` | Report the dependency, observed version, and required floor. |
| `:handler-error` | The user's handler threw. Point at `:handler/error` and stack details. |
| `:timed-out? true` | The dispatch cascade did not settle. The tab may be backgrounded, debugger paused, or async chain continuing. |
| `:connection :lost` | Reconnect with `scripts/discover-app.sh`. |
| `:bad-arg` | A positional or flag arg failed to parse. The response carries `:got` (the offending input) and `:hint` (correct usage). Common cause: passing a flag where a positional integer is expected — e.g. `trace-recent.sh --limit 25` (the script takes `<window-ms>` as its only positional). |
| `:repl-exception` | shadow-cljs's printer threw while serialising the eval result (sentinel `:repl/exception!`). Most common cause: `eval-cljs.sh` of a form that touches `re-frame.core/dispatch` or `subscribe` — re-frame internal types / reagent reactions throw inside `pr-str`. Workaround: route dispatches through `scripts/dispatch.sh` instead; for subscribe reads, deref the underlying ratom rather than the subscribe ratom directly. |

## Multi-Build Setups

If `discover` reports `:warning :multiple-builds`, surface it. The selected build may not be the one the user intended.

Use:

```bash
scripts/discover-app.sh --list
scripts/discover-app.sh --build=<id>
```

or set `SHADOW_CLJS_BUILD_ID`.

## Browser Refresh

Ops auto-reinject after browser refresh. Responses may carry `:reinjected? true`; that is informational, not an error.

## 10x Loaded but Panel Invisible

Symptom — `discover-app.sh` reports:

```clj
{:ten-x-loaded?  true
 :ten-x-mounted? false
 ...}
```

The 10x namespace compiled and loaded fine, but its DOM mount node `<div id="--re-frame-10x--">` is missing. Most common cause: the dev `<script>` tag is in `<head>` without `defer`, so when 10x's preload runs `(.appendChild js/document.body container)` the body element doesn't exist yet, the call throws silently, and the panel never becomes visible.

Fix in the host project: move the `<script src=".../app.js">` to the end of `<body>`, or add `defer` to the `<head>` script tag.

`:ten-x-mounted?` is independent of `:ten-x-loaded?` — never cross 10x off the hypothesis list when only `:ten-x-loaded?` is true.

## Chunked-Inject Failures

Symptom cluster — usually appearing together after a successful nREPL connect:

- `discover-app.sh` returns blank or `nil`.
- `eval-cljs.sh` returns `{:ok? true, :value nil}` for every form, including trivial ones like `(+ 1 2)`.
- shadow-cljs's watch process logs `:tag :shadow.build.resolve/missing-ns` for a `re-frame-pair.runtime.*` namespace.

This is **not a host classpath problem** — the source files exist on the skill side. The chunked inject ships submodules one form at a time over nREPL, and shadow-cljs's `:require` resolution at REPL time only sees namespaces that have already been shipped in this session. If submodule X requires submodule Y, X must appear *after* Y in `runtime-submodule-files` (`scripts/ops.clj`).

Diagnosis:

1. Read the failing namespace's `(:require ...)` form.
2. Check its position in `runtime-submodule-files` against every required `re-frame-pair.runtime.*` ns it lists.
3. Each required ns must appear at an earlier slot.

The `runtime-submodule-files-respects-require-graph` test (`tests/ops_smoke.bb`) catches this class of bug at `npm test:ops` time.

## Trace Shape Mismatches

On newer re-frame builds, `re-frame.core/tag-schema` describes trace tags. Runtime validation can be enabled with:

```bash
scripts/eval-cljs.sh '(re-frame.core/set-validate-trace! true)'
```

Use this only when investigating trace-shape mismatches; it is not part of normal operation.

## Upstream Feature References

- `re-frame.core-instrumented`: captures source metadata for dispatch, subscribe, and handler definitions.
- `register-epoch-cb`: native assembled-epoch callback.
- `dispatch-and-settle`: settle-aware dispatch tracing.
- `dispatch-with` / `dispatch-sync-with`: per-dispatch fx substitution.
- `day8.re-frame-10x.public`: public 10x epoch/navigation surface.
- `day8.re-frame.tracing/dbg` and `wrap-handler!`: re-frame-debux instrumentation.
