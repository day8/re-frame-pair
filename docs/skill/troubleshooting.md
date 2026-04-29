# Troubleshooting

Every script returns structured edn like `{:ok? false :reason ...}`. Translate the reason into plain English and report the suggested fix.

## Common Reasons

| Reason | What to tell the user |
|---|---|
| `:nrepl-port-not-found` | Start the dev build with `shadow-cljs watch <build>`. |
| `:browser-runtime-not-attached` | Open the app in a browser tab. |
| `:trace-enabled-false` | The build was not compiled with `re-frame.trace.trace-enabled?` true; this is a 10x requirement. |
| `:ns-not-loaded` | re-frame-10x, re-com, or another required namespace is not loaded. Check deps and preloads. |
| `:version-too-old` | Report the dependency, observed version, and required floor. |
| `:handler-error` | The user's handler threw. Point at `:handler/error` and stack details. |
| `:timed-out? true` | The dispatch cascade did not settle. The tab may be backgrounded, debugger paused, or async chain continuing. |
| `:connection :lost` | Reconnect with `scripts/discover-app.sh`. |

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
