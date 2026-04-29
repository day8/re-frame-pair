# UI Debugging

Use this when a view did not update, a rendered value is wrong, or the user asks where a visible control is wired.

## Why Didn't The UI Update?

1. Identify the subscription the view reads. Ask the user if it is not visible in the view file.
2. Read `trace/last-claude-epoch` or `trace/last-epoch` to find the recent dispatch that should have updated it.
3. Walk the Layer 2 -> Layer 3 subscription chain.
4. For each layer, compare the pre- and post-epoch value.
5. Report the equality gate that held the value constant, or the layer where the expected value changed but render did not follow.

Example conclusion:

> The handler wrote to `[:user :pending-profile]`, but the header reads `[:user/profile]` through `[:user/display-name]`. Layer 2 returned the same value, so Layer 3 cache-hit and the header did not render.

## Verify Rendered Output

When the user asks whether a panel is showing the right values, prove data -> sub -> render -> DOM.

1. Read the data inputs with `app-db/get` or relevant subscription samples.
2. Compute the expected displayed value.
3. Use `dom/find-by-src` for a known line, or query `[data-rc-src]` for matching text.
4. Compare rendered text with expected text.
5. If data and sub are right but DOM is wrong, investigate render invalidation or hot reload.

## Where Is This UI Wired?

1. Use `dom/source-at` on a selector or `:last-clicked`.
2. Read the source around that line.
3. Identify the component, props, event dispatches, and subscriptions.
4. If `data-rc-src` is missing, report whether re-com debug is disabled or that specific call site lacks `:src (at)`.

## Which View Subscribed To X?

1. Use `subs/live` to confirm the query is currently alive.
2. Pull a recent epoch and inspect `:subs/ran` plus `:subs/cache-hit`.
3. Read `:subscribe/source` to locate the outer `(rf.macros/subscribe ...)`.
4. For composite subscriptions, inspect `:input-query-vs` and `:input-query-sources`.

Cache-hit subtlety: re-frame's cache key ignores metadata, so shared subscriptions may report the first originating call site. To enumerate all readers, combine `subs/live` with source search.

## Why Did This Event Fire?

Read both sides:

1. `trace/last-epoch` or `trace/epoch` -> `:event/source` gives the dispatch call site.
2. `handler/source` for the event id gives the registered handler.

If `:event/source` is nil, the host may not be using `re-frame.macros/dispatch`; see `source-meta.md`.
