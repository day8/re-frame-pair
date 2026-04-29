# Probing And Hot-Swap

Use this when you need to test whether a fix works before editing source, or when a dispatch would fire side effects you do not want to run during iteration.

## Experiment Loop

The goal is to compare two epochs from the same starting state with only the code changed.

1. Run `trace/dispatch-and-settle` with `scripts/dispatch.sh --trace '[:foo ...]'` and capture the baseline epoch id.
2. Check `undo/status` and warn about side effects that cannot be rewound.
3. Rewind with `undo/step-back` or `undo/to-epoch <id>` when appropriate.
4. Apply the smallest probe:
   - handler/sub/fx: hot-swap with a `reg-*` form through `eval-cljs`
   - helper/view: redefine the var through `eval-cljs`
   - permanent fix: edit source, then wait for hot reload
5. Verify the probe landed. For event handlers, compare `handler/ref` before and after.
6. Re-dispatch and compare the new epoch with the baseline.
7. If the probe is correct and the user wants to keep it, transfer it to source.

REPL changes are ephemeral. They can survive hot reload of unrelated namespaces, but disappear on full page reload.

## Hot Reload Protocol

After any source edit:

```bash
scripts/tail-build.sh --wait-ms 5000 --probe '<probe-form>'
```

Pick a probe that changes when the edited code lands:

- For a `reg-*` handler, use `handler/ref`.
- For a view or helper, use a short form that depends on the edited var.
- If no reliable probe exists, omit `--probe`; the script falls back to a timer and returns `:soft? true`.

If the probe times out, treat it as a compile/reload failure. Read the reported output and do not dispatch against stale code.

## Side-Effect Stubbing

When a dispatch would fire HTTP, navigation, local storage, or another effect you do not want to execute, pass `--stub <fx-id>`:

```bash
scripts/dispatch.sh --trace --stub :http-xhrio --stub :navigate '[:user/login {...}]'
```

The named fx handlers are replaced with record-only stubs for that dispatch and its cascade. Captured values are available through:

```bash
scripts/eval-cljs.sh '(re-frame-pair.runtime/stubbed-effects-since 0)'
```

Each `--stub` id is checked against the fx registrar. If an id is unknown, the shim refuses to dispatch so the real effect cannot accidentally run.

For a custom stub function, call `dispatch-with!` directly via `eval-cljs`; functions cannot round-trip through the bash CLI. The signature is `(dispatch-with! event-v {fx-id stub-fn ...})` — the second arg is a map from fx ids to stub functions, each `(fn [fx-value] ...)`.

## Form-Level Trace

For expression-by-expression tracing inside handlers, subs, or fx, use re-frame-debux when available. Probe availability with `(re-frame-pair.runtime/debux-runtime-api?)` (returns truthy when the runtime API is loaded). The full procedure lives in `docs/skill/debux.md`.
