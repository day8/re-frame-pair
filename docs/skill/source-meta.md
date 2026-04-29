# Source Metadata

Use this when `:event/source`, `:subscribe/source`, or `handler/source` is missing, or when explaining how source locations are captured.

## Host Opt-In

For source metadata to populate, the host app must alias-swap from the function API to the macro mirror:

```clojure
;; before
(:require [re-frame.core :as rf])

;; after
(:require [re-frame.macros :as rf])
```

The call shape stays the same for normal calls. Production builds with `goog.DEBUG=false` elide the metadata path.

## Caveats

- Macros cannot be used in value position. For `(apply reg-sub ...)`, `(map reg-event-db ...)`, `(partial reg-fx ...)`, keep `re-frame.core`.
- `:event/source` from `dispatch.sh --trace` is nil because the bash shim dispatches via `re-frame.core/dispatch`; real button clicks can carry source from the macro call site.
- Older re-frame builds do not have the macro mirror, so source fields remain nil.
- `handler/source` returning `:no-source-meta` usually means the host has not opted into the macros.

See `docs/handler-source-meta.md` for design rationale and history.

## DOM Source

For DOM -> source, re-com debug instrumentation must be enabled and the specific component call site must pass `:src (at)`.

Missing source is per element, not necessarily app-wide:

- `:re-com-debug-disabled`: debug instrumentation is off.
- `:no-src-at-this-element`: this call site did not pass `:src (at)`.
