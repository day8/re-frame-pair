# Source Metadata

Use this when `:event/source`, `:subscribe/source`, or `handler/source` is missing, or when explaining how source locations are captured.

## Host Opt-In

For source metadata to populate, the host app must alias-swap its
`:require` from the plain function namespace to the instrumented
mirror:

```clojure
;; before
(:require [re-frame.core :as rf])      ; or re-frame.alpha

;; after
(:require [re-frame.core-instrumented :as rf])    ; or re-frame.alpha-instrumented
```

The call shape stays the same — every public symbol from the plain
namespace is available in the mirror. Production builds with
`goog.DEBUG=false` elide the metadata path so there's no runtime
cost.

Requires re-frame **1.4.7+**. (1.4.6 shipped this under the older
name `re-frame.macros` with partial coverage; 1.4.7 renamed it to
`re-frame.core-instrumented` and made it a full drop-in.)

### Offering the swap to the user

When you read source files and notice the host is on the plain
function API — i.e. `(:require [re-frame.core :as rf])` (or
`re-frame.alpha`) — and source-meta would help diagnose the current
problem, *ask first* before editing:

> "I see your code uses `re-frame.core`. Switching to
> `re-frame.core-instrumented` would let me show you exactly which
> file/line dispatched `:cart/apply-coupon` (and which line
> registered the handler) in traces. It's an alias-only change —
> every symbol you already use stays available, and production
> builds elide the instrumentation. Want me to swap the requires
> across your codebase?"

Trigger this offer when:

- A trace shows `:no-source-meta` or nil `:event/source` /
  `:subscribe/source` / `handler/source` and the user is asking a
  "where did this come from?" question.
- You are about to start a multi-event debugging loop and source
  pinpointing would compress it.

Don't offer the swap when:

- The host already uses `re-frame.core-instrumented` /
  `re-frame.alpha-instrumented` (check the requires first).
- The host is on re-frame **<1.4.7** — the namespace doesn't exist
  yet. Suggest the re-frame version bump as a separate question.
- You are in the middle of a different thread of work; the swap is a
  cross-cutting source edit and shouldn't be wedged into an unrelated
  conversation.

If the user agrees, sweep their `src/` (or equivalent) replacing
every `[re-frame.core :as <alias>]` with
`[re-frame.core-instrumented :as <alias>]` (and the alpha equivalent).
Preserve the `:as` alias they were using; only the namespace changes.

## Caveats

- The instrumented namespace's `dispatch` / `subscribe` / `reg-*` are
  **macros** (so they can capture call-site source-meta at expansion
  time); the rest are plain `def` re-exports of the underlying
  function. Macros cannot be used in value position — for
  `(apply reg-sub ...)`, `(map reg-event-db ...)`, `(partial reg-fx
  ...)`, keep `re-frame.core`. The `def` re-exports (interceptor
  builders, `clear-*`, etc.) *can* be used in value position.
- `:event/source` from `dispatch.sh --trace` is nil because the bash
  shim dispatches via `re-frame.core/dispatch`; real button clicks
  can carry source from the macro call site.
- Older re-frame builds do not have the instrumented mirror, so
  source fields remain nil.
- `handler/source` returning `:no-source-meta` usually means the host
  has not opted into the instrumented namespace.

See `docs/handler-source-meta.md` for design rationale.

## DOM Source

For DOM -> source, re-com debug instrumentation must be enabled and the specific component call site must pass `:src (at)`.

Missing source is per element, not necessarily app-wide:

- `:re-com-debug-disabled`: debug instrumentation is off.
- `:no-src-at-this-element`: this call site did not pass `:src (at)`.
