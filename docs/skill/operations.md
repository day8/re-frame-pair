# Operations Reference

Read this when you need the exact operation name, command form, or epoch field.

## Epoch Fields

Every epoch returned by `trace/*` ops is coerced into an agent-readable shape:

| Field | What it tells you |
|---|---|
| `:event` | The dispatched event vector after interceptor rewrites. |
| `:event/original` | The event pinned at handle-entry before `trim-v`, `unwrap`, `path`, etc. |
| `:event/source` | `{:file :line}` of the dispatch call site when the host uses `re-frame.macros/dispatch`; `nil` otherwise. |
| `:dispatch-id` / `:parent-dispatch-id` | UUIDs threaded through cascades. Root events have no parent. |
| `:app-db/diff` | Compact changed values only. Full snapshots are available through `epoch-app-db-snapshots`. |
| `:effects/fired` | Effects as a tree: `:db`, `:dispatch`, `:http-xhrio`, custom fx. |
| `:interceptor-chain` | Ordered interceptor ids. |
| `:subs/ran` | Each entry includes query-v, source, inputs, and timing. |
| `:subs/cache-hit` | Subs that did not re-run. Same source shape as `:subs/ran`. |
| `:renders` | Components that re-rendered, with re-com category and `:src` where available. |
| `:debux/code` | Per-form trace when re-frame-debux instrumentation is active. |
| `:coeffects` | Injected coeffects. |

## Read

| Op | Invocation | Returns |
|---|---|---|
| `app/summary` | `scripts/app-summary.sh` | Versions, registrar, live subs, app-db shape, health. |
| `health` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/health)'` | Re-arms listeners; returns session and trace status. |
| `versions/report` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/version-report)'` | Observed/floor version checks. |
| `app-db/snapshot` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/snapshot)'` | Current `@app-db`. |
| `app-db/get` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/app-db-at [:path])'` | Path-scoped value. |
| `app-db/schema` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/schema)'` | Opt-in schema or `nil`. |
| `registrar/list` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/registrar-list :event)'` | Ids under `:event`, `:sub`, `:fx`, or `:cofx`. |
| `registrar/describe` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/registrar-describe :event :foo/bar)'` | Kind and interceptor ids. |
| `subs/live` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/subs-live)'` | Currently-subscribed query vectors. |
| `subs/sample` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/subs-sample [:cart/total])'` | One-shot deref. |
| `handler/source` | `scripts/handler-source.sh :event :foo/bar` | Source location of the handler, or `:no-source-meta`. |
| `handler/ref` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/registrar-handler-ref :event :foo/bar)'` | Opaque hash that flips on hot-swap or reload. |

## Write

| Op | Invocation | Notes |
|---|---|---|
| `dispatch` | `scripts/dispatch.sh '[:foo ...]'` | Queued by default; `--sync` for `dispatch-sync`; `--trace` to await and return epochs. |
| `reg-event` / `reg-sub` / `reg-fx` | `scripts/eval-cljs.sh '<full reg-* form>'` | Hot-swap immediately. Ephemeral. |
| `app-db/reset` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/app-db-reset! ...)'` | Logged via `tap>`. Use sparingly. |
| `repl/eval` | `scripts/eval-cljs.sh '<form>'` | Escape hatch. Prefer structured ops first. |

## Trace

| Op | Invocation | Returns |
|---|---|---|
| `trace/last-epoch` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/last-epoch)'` | Most recent epoch. |
| `trace/last-claude-epoch` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/last-claude-epoch)'` | Most recent sync epoch this session dispatched. |
| `trace/epoch` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/epoch-by-id "<id>")'` | Named epoch, including heavy fields. |
| `trace/epoch-app-db` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/epoch-app-db-snapshots <id>)'` | Full `{:before :after}` app-db snapshots for one epoch. |
| `trace/dispatch-and-settle` | `scripts/dispatch.sh --trace '[:foo ...]'` | Fire + await cascade + return root and cascaded epochs. |
| `trace/recent` | `scripts/trace-recent.sh <ms>` | Epochs added in the last N ms. |
| `trace/find-where` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/find-where <pred>)'` | Most recent epoch matching a predicate. |
| `trace/find-all-where` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/find-all-where <pred>)'` | All matching epochs, newest first. |

## Console And Errors

Console calls are buffered and tagged:

- `:claude` for sync agent-dispatched work.
- `:app` for normal app output.
- `:handler-error` for re-frame event handler throws.

| Op | Invocation |
|---|---|
| `console/tail` | `scripts/console-tail.sh` |
| `console/tail-since` | `scripts/console-tail.sh --since-id 42` |
| `console/tail-claude` | `scripts/console-tail.sh --who claude` |
| `console/tail-handler-errors` | `scripts/console-tail.sh --who handler-error` |

## DOM And Source

When re-com debug instrumentation is enabled and components pass `:src (at)`, re-com attaches `data-rc-src="file:line"` to rendered elements.

| Op | Invocation | Returns |
|---|---|---|
| `dom/source-at` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/dom-source-at "#save-button")'` | Source for a selector or `:last-clicked`. |
| `dom/find-by-src` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/dom-find-by-src "view.cljs" 84)'` | Live DOM elements rendered by that source line. |
| `dom/fire-click-at-src` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/dom-fire-click "view.cljs" 84)'` | Synthetic click on the element rendered by that line. |
| `dom/describe` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/dom-describe "#save-button")'` | Attrs, `data-rc-src`, listeners. |

## Watch

| Op | Invocation |
|---|---|
| `watch/window` | `scripts/watch-epochs.sh --window-ms 30000 --event-id-prefix :checkout/` |
| `watch/count` | `scripts/watch-epochs.sh --count 5` |
| `watch/stream` | `scripts/watch-epochs.sh --stream --event-id-prefix :cart/` |
| `watch/stop` | `scripts/watch-epochs.sh --stop` |

Predicates may include `--event-id`, `--event-id-prefix`, `--effects`, `--timing-ms '>100'`, `--touches-path`, `--sub-ran`, and `--render`.

## Time Travel

Time-travel ops navigate the epoch buffer. They rewind `app-db` only; side effects already fired are not undone.

| Op | Invocation |
|---|---|
| `undo/step-back` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-step-back)'` |
| `undo/step-forward` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-step-forward)'` |
| `undo/most-recent` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-most-recent)'` |
| `undo/to-epoch` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-to-epoch <id>)'` |
| `undo/replay` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-replay)'` |
| `undo/status` | `scripts/eval-cljs.sh '(re-frame-pair.runtime/undo-status)'` |
