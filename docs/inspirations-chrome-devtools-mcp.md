# Inspirations: Chrome DevTools MCP

A look at Google's official [Chrome DevTools MCP server](https://github.com/ChromeDevTools/chrome-devtools-mcp) (`chrome-devtools-mcp`, maintained under the `ChromeDevTools/` GitHub org), and what re-frame-pair could borrow, integrate, or recommend alongside it.

The server runs as a Node MCP process (`npx -y chrome-devtools-mcp@latest`), drives Chrome via Puppeteer over the Chrome DevTools Protocol, and either launches its own browser or attaches to a running one (`--browser-url` / `--wsEndpoint`, plus a Chrome 144+ auto-connect to the user's profile). It complements re-frame-pair cleanly: re-frame-pair owns the **CLJS-runtime** view of the app (app-db, epochs, registrar, re-com `:src`); Chrome MCP owns the **browser-platform** view (network, console, performance, accessibility, screenshots).

## 1. What Chrome DevTools MCP gives us

33 tools across 8 categories. The ones relevant to a re-frame debugging session:

**Debugging / inspection**
- `take_snapshot` — text snapshot of the **accessibility tree** with stable `uid`s for each element. Preferred over screenshots for reasoning about page structure.
- `take_screenshot` — PNG/JPEG/WebP of the page or a specific `uid`'d element.
- `evaluate_script` — runs a JS function in page context (we already have a CLJS path for this; useful only for pure-JS probes).
- `list_console_messages` / `get_console_message` — console output since last navigation, with `includePreservedMessages` retaining ~3 navigations of history.

**Network**
- `list_network_requests` — every HTTP request since last navigation, filterable by `resourceTypes`, paginated.
- `get_network_request` — full request/response for a `reqid`, optionally written to a file.

**Performance**
- `performance_start_trace` / `performance_stop_trace` — record a DevTools performance trace (Core Web Vitals + frame timeline).
- `performance_analyze_insight` — drill into a specific insight surfaced by a trace (e.g. `DocumentLatency`, `LCPBreakdown`).
- `lighthouse_audit` — accessibility / SEO / best-practices report (perf excluded — that's the trace tools).

**Memory**
- `take_memory_snapshot` — heap snapshot for leak hunts.

**Driving the page**
- Input: `click`, `type_text`, `fill`, `fill_form`, `hover`, `drag`, `press_key`, `upload_file`, `handle_dialog`.
- Navigation: `navigate_page`, `new_page`, `select_page`, `close_page`, `list_pages`, `wait_for`.
- Emulation: `emulate` (device profile), `resize_page`.

## 2. Mapping to re-frame-pair recipes

For each existing SKILL.md recipe, would Chrome MCP help, hurt, or be neutral?

| Recipe | Verdict | Notes |
|---|---|---|
| **"What's in app-db?" / "What did the last event do?"** | Neutral. | re-frame-pair's `app-db/snapshot` and `trace/last-epoch` already speak directly to the data structure. The browser layer adds nothing. |
| **"Why didn't my view update?"** | **Helps.** | After tracing the sub chain, `take_screenshot` (or `take_snapshot` with the `:src`-bearing element's `uid`) confirms what the user actually sees vs. what the sub returned. The accessibility tree also disambiguates "Save button is grey" — re-frame-pair sees state, Chrome MCP sees rendered ARIA disabled. |
| **"Explain this dispatch"** | **Helps for the network domino.** | re-frame-pair narrates effects from `:effects/fired`, but `:http-xhrio` only shows that an HTTP fx was *requested*. `list_network_requests` after the dispatch shows what actually went on the wire (URL, status, response). This is the largest single gap re-frame-pair has today. |
| **"Post-mortem — how did we get here?"** | **Helps significantly.** | The retention caveat (10x's bounded epoch ring) means transitions can age out. `list_console_messages` with `includePreservedMessages` adds an *independent* second log — error stacks from `:reason :handler-threw`, plus warnings the runtime never sees. When the user is stuck and `find-where` returns nothing, console history may have the smoking gun. |
| **"What effects fired?"** | **Helps for `:http-xhrio`.** | Pair epoch's `:effects/fired` with `list_network_requests` filtered by time-since-epoch — turns "queued, not landed" into "queued, landed, 502'd". |
| **"What caused this re-render?"** | Neutral, leaning helpful. | Re-render causality is a sub-graph question, owned by re-frame-pair. But `performance_start_trace` around a suspect interaction can show whether the re-render was the *expensive* part of the frame, or a paint/layout downstream. |
| **"Where in the code does this come from?"** | Neutral. | `dom/source-at` via re-com's `:src` is the right tool here. `take_snapshot` could *substitute* if `:src` is unavailable, but only by surfacing tag/aria — not source. |
| **"Understand this component"** | **Helps marginally.** | After `dom/source-at` + reading source, `take_snapshot` of the component's subtree gives a structural view. `take_screenshot` grounds *"describe this UI"* requests the user might phrase visually. |
| **"Fire the button at file:line"** | Neutral. | `dom/fire-click-at-src` is more precise than Chrome MCP's `click` (the latter needs a `uid` from a snapshot, losing the source-line bridge). Stick with re-frame-pair's path. |
| **"Dead code scan"** | Neutral. | Pure registrar + epoch question. |
| **"Experiment loop"** | **Helps when the variable is performance.** | If the iteration is "did my re-frame change reduce input latency?", a `performance_*` trace before and after gives an objective number that `:time-ms` on epochs can't (it covers re-frame's six dominoes only, not paint/layout/scripting). |
| **"Alert me on slow events"** | **Helps with root-causing.** | When `watch/stream --timing-ms '>100'` fires, follow up with a tiny perf trace to see whether the cost is in the handler, sub recomputation, or React commit. |

Three specific gap-fillers stand out:

- **Network effects.** re-frame-pair literally cannot see what `:http-xhrio` puts on the wire. `list_network_requests` + `get_network_request` close this.
- **Console / handler stack traces.** `:reason :handler-threw` carries the error; the *full stack* lives in console. `list_console_messages` retrieves it without a separate `js/console.error` shim.
- **Accessibility tree.** re-com's `:src` resolves a specific element to source; it doesn't describe non-re-com elements (third-party widgets, plain `[:div]` markup). `take_snapshot` fills that gap with stable `uid`s.

## 3. Integration shape

**Recommendation: Option C (reference and recommend), with a small Option A tilt for installation guidance.**

Reasoning:

- **Option B (bridge)** is wrong. It would make re-frame-pair runtime a proxy to a separate MCP server — adding an MCP-client surface inside CLJS that has to be wired through nREPL → babashka → MCP. The coupling would be expensive and the failure modes (Chrome MCP not configured, version drift, two browser handles) would all land in re-frame-pair's error surface. Worst of both worlds.
- **Option A (companion)** is what *will actually happen* — operators who want browser-platform tooling will configure Chrome MCP alongside re-frame-pair in their `~/.claude.json`. Both surfaces become available at the conversation level. No code changes to re-frame-pair.
- **Option C (reference and recommend)** is the cheapest concrete contribution re-frame-pair can make today: extend SKILL.md's recipes with **"if Chrome DevTools MCP is also configured, also do X"** sub-bullets — *"after `trace/dispatch-and-collect`, call `list_network_requests` to see what `:http-xhrio` landed"*. Pure documentation; the agent composes the two skills naturally because both are in scope.

Concrete next step would be a section in SKILL.md under `## Companion tools` describing Chrome MCP's role, plus inline sub-recipe references on the four high-value recipes (Explain this dispatch, Post-mortem, What effects fired, Alert me on slow events). The plugin's `allowed-tools` does *not* need to enumerate Chrome MCP tools — they're a separate MCP server, surfaced by the host.

A future Option-A+ would ship a bundled `.claude-plugin/plugin.json` snippet operators can copy to add Chrome MCP to their config, but that's a packaging convenience, not architecture.

## Top 3 concrete capabilities Chrome DevTools MCP unlocks for re-frame-pair

1. **`:http-xhrio` ground-truth** via `list_network_requests` + `get_network_request` — turns the fired/landed gap into a closed loop. Largest single capability re-frame-pair lacks today.
2. **Handler-error stack traces** via `list_console_messages` — pairs with `:reason :handler-threw` epochs to give the user line-numbered call stacks the runtime never collected.
3. **Performance traces around slow epochs** via `performance_start_trace` / `performance_stop_trace` / `performance_analyze_insight` — bridges re-frame's six-domino timing to browser-frame timing, making "is this slow because of the handler or the render?" answerable with numbers.
