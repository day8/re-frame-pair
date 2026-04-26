# Inspirations from `bhauman/clojure-mcp`

A survey of Bruce Hauman's [clojure-mcp](https://github.com/bhauman/clojure-mcp) MCP server, asking what — if anything — re-frame-pair should borrow for v0.2. Sources cited are the project's `README.md` and the directory listings under `src/clojure_mcp/` and `src/clojure_mcp/tools/`.

## Context: the design gap

clojure-mcp is a *general-purpose Clojure development server*. It assumes a **stateless tool model** — each tool is a function from inputs to outputs, dispatched by an MCP host. Its REPL is the user's project JVM; its first-class objects are *files, namespaces, forms, and ports*.

re-frame-pair is the opposite: a **single-purpose, stateful, live-runtime** skill. Its first-class objects are `app-db`, `epochs`, `subs`, `effects`, `:src` annotations. The shared substrate is "we both shell out to an nREPL and eval Clojure forms." Most of clojure-mcp's value is in patterns surrounding that substrate — discovery, eval ergonomics, project memory, structural editing — *not* in the data model.

---

## Worth borrowing

### 1. `list_nrepl_ports` — multi-REPL discovery as a first-class op

clojure-mcp's `tools/nrepl_ports/` discovers every running nREPL on the machine and exposes them as a list, so the LLM can target shadow-cljs and a Clojure JVM independently (`README.md` "Multi-REPL Support").

re-frame-pair's `scripts/discover-app.sh` (per `SKILL.md` "Connect first") finds *one* port via a fixed candidate cascade and assumes a single shadow-cljs build. That's correct for the common case, but the moment a developer has two builds running (e.g. `app` and `storybook`) it picks one silently.

**Borrow:** add a `discover/list` op that returns every plausible nREPL port plus the shadow build ids reachable on each, and let the agent pick — or warn — when more than one is live. Cheap (a babashka shell scan over `.shadow-cljs/nrepl.port`, `.nrepl-port`, `~/.shadow-cljs/`, plus `(shadow.cljs.devtools.api/active-builds)` over each). CLJS-applicable.

### 2. Project introspection resource

clojure-mcp ships `clojure_inspect_project` (a resource) and a `create-update-project-summary` prompt that materialises a `PROJECT_SUMMARY.md` (`README.md` "Resources" / "Prompts"). The summary lists deps, namespaces, key files — context the LLM gets *for free* on session start.

re-frame-pair's equivalent today is the version sniff inside `discover-app.sh` plus per-op `registrar/list` calls. There is no equivalent of "here's a list of every event/sub/fx in this app, ranked by recency-of-use, alongside re-com / re-frame / shadow versions" served at session-start.

**Borrow:** an `app/summary` op that returns a one-shot bundle: re-frame + 10x + re-com + shadow versions, the registrar inventory by kind, the live sub roots, the top-level keys of `app-db`, plus the depth/branching of the sub graph. This is the natural "READ.ME of the running app" — the LLM-flavoured equivalent of opening the inspector and squinting. CLJS-applicable; goes well beyond what clojure-mcp does because re-frame's runtime carries the relationships explicitly.

### 3. Structured "scratch pad" for inter-op state

`scratch_pad` (`tools/scratch_pad/`) is a persistent workspace where the LLM stashes structured data across tool calls (`README.md` "scratch_pad"). clojure-mcp uses it for chat-session resume (`chat-session-summarize` / `chat-session-resume` prompts).

re-frame-pair has nothing like this. Capturing "the baseline epoch id for the experiment loop" or "the registrar handler hash before the edit" (per `SKILL.md` "Experiment loop" step 1 and step 5) is left to the conversation log. If the conversation compacts, those handles are lost.

**Borrow (lightweight):** a `notes/put` and `notes/get` op writing to a file under `.re-frame-pair/notes.edn`. Specifically *not* a generic blob — a typed map of `{:baseline-epoch-id ... :pre-edit-handler-hash ... :last-watch-summary ...}` so the agent has stable handles for the recipes that span multiple turns. Cheap.

### 4. Collapsed-view file reader for `defn`/`reg-*` headers

`unified_read_file` shows a **"Collapsed View"** of Clojure files — function signatures only, with pattern matching by name (`README.md` "Collapsed View"). For an LLM, reading a 600-line view file collapsed-first then drilling into one `defn` saves a lot of context.

re-frame-pair currently relies on Claude Code's stock `Read` tool, which dumps full file content. For "Understand this component" (`SKILL.md` recipe), the agent often pulls a 200-line view file just to find the right `defn`.

**Borrow:** not the tool itself — Claude Code already has `Read` + `Grep`. But borrow the *idea*: an `app/find-handler` op that resolves `:cart/apply-coupon` → `{:file ... :line ... :form-preview ...}` by reading the registrar's stored handler metadata. The bridge from event-id to source location, parallel to the existing DOM↔source bridge (`SKILL.md` "DOM ↔ source bridge"). CLJS-applicable; arguably a one-liner via `(meta handler-fn)`.

### 5. Profiles / config for client adaptation

clojure-mcp ships `:cli-assist` vs Claude Desktop profiles (`README.md` "Client Adaptation") because what's noisy in one client is essential in another.

re-frame-pair is squarely a Claude Code skill, but the same lever — a `.re-frame-pair/config.edn` for "verbose-trace-shape on/off", "auto-include-renders true/false", "default watch window" — would let teams tune the cost/detail tradeoff without editing the skill. Low priority; useful once two teams adopt it.

---

## Already covered

- **Eval-as-primitive.** clojure-mcp's `clojure_eval` ≈ `scripts/eval-cljs.sh` (`SKILL.md` "Operations"). Both wrap nREPL eval and surface structured returns. re-frame-pair goes further by auto-reinjecting the runtime sentinel — clojure-mcp has no equivalent because it has no runtime to reinject.
- **Discovery before eval.** clojure-mcp probes for nREPL; re-frame-pair's `discover-app.sh` runs *five* health checks (`SKILL.md` "Connect first") because the live-runtime contract is richer (`re-frame-10x` loaded, `trace-enabled?`, re-com debug, etc.).
- **REPL hot-swap.** clojure-mcp's `clojure_eval` allows redefining vars; `SKILL.md` "Write" table makes this a first-class op (`reg-event` / `reg-sub` / `reg-fx`) with the **ephemeral vs source-edit** distinction explicit (`SKILL.md` "Cardinal rule").
- **Structural code editing.** clojure-mcp's `clojure_edit` / `paren_repair` / `form_edit` are CLJ-source-text operations. re-frame-pair leaves source editing to Claude Code's `Edit` and instead specialises in *runtime state mutation* (dispatch, app-db reset, hot-swap). Different problem.
- **Bash escape hatch.** Both have one (`tools/bash/` vs the implicit `scripts/eval-cljs.sh '<arbitrary form>'` in `SKILL.md` "Write").

---

## Doesn't transfer

- **`paren_repair` / parinfer / cljfmt.** Source-text formatting concerns. re-frame-pair's `runtime.cljs` is hand-maintained and small; views and handlers are the user's problem, edited via `Edit`. CLJ source-text affordances are out of scope.
- **`deps_*` family** (`deps_common`, `deps_grep`, `deps_list`, `deps_read`, `deps_sources`). Clojure CLI tooling for `deps.edn`. CLJS dep graphs live in `shadow-cljs.edn` + `package.json`; the questions an LLM asks of them are different and rarely about a dependency's *source*. Skip.
- **`figwheel` tool.** Build tool integration for figwheel-main. shadow-cljs is the only CLJS build target re-frame-pair supports, and `tail-build.sh` (`SKILL.md` "Hot-reload coordination") already handles compile coordination with a probe-based protocol that's *better* than figwheel's because it can verify the new code is live in the browser, not just compiled.
- **`architect` / `code_critique` / `dispatch_agent`.** Sub-agent tools that route through external APIs and key management. Claude Code already provides agent dispatch and review affordances at the harness level; building them inside the skill duplicates harness features and adds a key-management headache.
- **`clojure_inspect_project` as currently implemented.** The CLJ version reads `deps.edn` and namespace metadata. The CLJS version of this question — "what does this app *do*?" — is answered by the registrar + sub graph + app-db shape, which is why borrow #2 above is an *adaptation*, not a copy.
- **Server-Sent Events transport** (`sse_core.clj` / `sse_main.clj`). MCP-over-HTTP plumbing. re-frame-pair is a Claude Code skill invoked over stdio shims; the transport question doesn't arise.

---

## Top 3 actionable takeaways

Ranked by leverage (impact ÷ work):

1. **`app/summary` op** (borrow #2). One-shot session-start bundle of versions + registrar inventory + sub roots + app-db shape. Highest leverage because it converts five separate `discover/`/`registrar/` calls the agent makes today into a single warm-start payload, and it's the natural place to grow as the runtime learns more (`runtime.cljs` already has every piece). ~2 hours.
2. **`discover/list` (multi-port)** (borrow #1). Defends against the silent-misconnect failure mode when a developer has two shadow builds running. Small, but prevents a class of confusing-bug session. Ideally rolls into the existing `discover-app.sh` health output. ~3 hours.
3. **Typed scratch pad** (borrow #3). Keyed handles (`baseline-epoch-id`, `pre-edit-handler-hash`) survive context compaction and make the experiment-loop recipe (`SKILL.md` "Experiment loop") robust across multi-turn conversations. The smallest possible version — one edn file, two ops — captures most of the value. ~3 hours.

Borrow #4 (handler-id → source-location bridge) is also attractive but partly already served by `registrar/describe`; promote it if the handler-source jump becomes a recurring friction point.
