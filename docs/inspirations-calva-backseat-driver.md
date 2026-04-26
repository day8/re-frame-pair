# Inspirations from Calva Backseat Driver

A survey of [Calva Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver) (CBD) — a VS Code extension that exposes Calva's REPL surface to LLMs via the VS Code Language Model API and an MCP server — looking for ideas worth borrowing for re-frame-pair.

The two projects are architecturally different (CBD: VS Code extension + MCP socket server; re-frame-pair: Claude Code skill + babashka shell shims to a shadow-cljs nREPL). They overlap because both let an AI evaluate Clojure against a live runtime.

Sources read: `README.md`, `AGENTS.md`, `PROJECT_SUMMARY.md`, `CHANGELOG.md` from the `master` branch.

---

## CBD's tool inventory (per `PROJECT_SUMMARY.md`)

REPL / discovery:
- `clojure_evaluate_code` — eval against a connected REPL session
- `clojure_list_sessions` — enumerate sessions, project roots, file globs, active session
- `clojure_symbol_info` — docstring + arglists for a symbol
- `clojuredocs_info` — examples from clojuredocs.org
- `clojure_repl_output_log` — buffered REPL output, queryable by `sinceLine`, `includeWho`, `excludeWho`
- `clojure_load_file` — load/evaluate a whole file through the REPL

Structural editing:
- `clojure_create_file` — Parinfer-balanced file creation
- `clojure_append_code` — Parinfer-balanced top-level append
- `replace_top_level_form` / `insert_top_level_form` — form-aware edits, with fuzzy line targeting via `targetLineText`
- `clojure_balance_brackets` — Parinfer over a fragment

Skills/instructions: "Backseat Driver" and "Editing Clojure Files" are exposed as both MCP resources and VS Code skills, with opt-out configuration.

---

## Worth borrowing

### 1. A buffered REPL output log queryable by source ("who's evaluating")

CBD's `clojure_repl_output_log` (CHANGELOG: "Datascript/datalog support and persistence across restarts") buffers stdout/stderr/eval-results across sessions and lets the agent ask "what has run since line N, by author X?" The `who` field separates user-typed evals, agent evals, and lib output — agents read back what the human just did and vice versa.

re-frame-pair has nothing like this. SKILL.md `trace/last-claude-epoch` distinguishes self-dispatched events from the user's, but only at the event-vector level — `(println …)` from a handler, `js/console.log` calls, and uncaught errors don't surface anywhere structured. `runtime.cljs:read-10x-epochs` won't show them.

A v0.2 `console/tail` op could subscribe to `js/console.{log,warn,error}` and stash entries in a ring buffer next to the epoch buffer, tagged `:who #{:user :claude :handler-error :app}`. The "who" tagging needs `tagged-dispatch!` (runtime.cljs:699) to mark the calling context — the plumbing is half-built. High leverage: closes a real blind spot, especially for handlers that swallow exceptions and only log them.

### 2. `symbol_info` / `clojuredocs_info` — symbol metadata as a tool

CBD lets the agent ask "what is `re-frame.core/inject-cofx`?" and get arglists + docstring without reading source. `clojuredocs_info` adds community examples for core symbols.

re-frame-pair's `registrar/describe` (SKILL.md §Read) only covers *user-registered* ids; there's no way to introspect re-frame's own API surface beyond Read-ing the dep. For an agent unfamiliar with a less-common interceptor (`inject-cofx`, `path`, `enrich`), this matters.

A `repl/symbol-info` op wrapping `(cljs.repl/doc …)` or `(cljs.analyzer/resolve-var …)` against the live compiler state would cost ~30 LOC in runtime.cljs. clojuredocs.org integration is lower priority — re-frame's own docs aren't on clojuredocs.

### 3. `list_sessions` — explicit multi-build awareness

CBD enumerates REPL sessions, project roots, and file globs so the agent can pick a context. re-frame-pair's `discover-app.sh` assumes a single shadow-cljs build and picks the first one it finds. STATUS.md *Spike findings §1* notes the port-file probe order is unchanged from the spec.

For projects with multiple shadow builds (`:app`, `:test`, `:worker`) this fails silently — the agent attaches to whichever build's nREPL responds first. A `discover-app.sh --list` mode showing all candidate builds (with the active one marked) would be cheap and would make multi-build setups debuggable.

### 4. Parinfer-balanced fragment validation before eval

CBD's `clojure_balance_brackets` lets the agent check a fragment before sending it through `clojure_evaluate_code`. Saves a round-trip on bracket typos.

re-frame-pair has no client-side validation; `eval-cljs.sh` ships the form to the nREPL and the reader error comes back as `:reason :read-error`. For Claude this isn't a frequent failure mode (LLMs are decent at brackets in Clojure), but for the small overhead of running rewrite-clj from babashka it'd be a nice belt-and-braces. **Lower priority** than #1–3.

### 5. Image-as-image return values

CHANGELOG: "Returns image data as images to the agent." If a Clojure form produces a `{:type :image …}` map (e.g., a chart from `tablecloth`), CBD returns it through the LMK API as an actual image rather than a stringified blob.

re-frame-pair returns everything via `eval-cljs.sh` as edn-stringified `:value`. Claude Code's tool output is text-only at the bash-shim level, so this doesn't fit cleanly today. Worth flagging for the day Claude Code grows multimodal tool returns — the change would be in `ops.clj:cljs-eval-value` to detect image-shape returns and emit an inline data-URI the harness can render.

---

## Already covered

- **REPL eval** — `scripts/eval-cljs.sh` is the equivalent of `clojure_evaluate_code`.
- **Form-aware editing of source** — re-frame-pair delegates to Claude Code's `Edit`/`Write` plus the `tail-build.sh --probe` reload protocol (SKILL.md §Hot-reload protocol). CBD's Parinfer/`replace_top_level_form` is genuinely better at structural edits, but it's solving an editor-integration problem we offload to the harness.
- **"Use the REPL, don't guess"** — AGENTS.md's central directive is identical to SKILL.md's "Experiment, don't speculate" (line 305) and "Read before you write" (line 302). Confirms the design instinct.
- **REPL-first / live-system bias** — CBD's whole pitch is "evaluate code in the user's running environment rather than guessing." re-frame-pair takes this further by making the *event loop* a first-class object (10x epoch buffer, `trace/find-where`, time-travel) rather than treating the REPL as just an eval channel.

---

## Doesn't fit

- **MCP socket server, port-file management, multi-workspace.** Claude Code skills run as bash shims; MCP would be a complete rewrite of the dispatcher. The babashka-over-nREPL choice is deliberate — re-frame-pair's spec §3 trades MCP's discoverability for shell-shim simplicity.
- **VS Code Language Model API integration / Copilot opt-in defaults.** Editor-specific.
- **Skill resources exposed via `resources/list`/`resources/read`.** That's MCP-side discoverability for non-Copilot clients. Claude Code already loads `SKILL.md` directly.
- **Datascript-backed persistence of eval history across restarts.** Could be useful, but persistence-across-page-reload conflicts with re-frame-pair's deliberate ephemerality (SKILL.md *Cardinal rule*) — REPL changes are *meant* to vanish so the user knows what's permanent.
- **Workspace-scoped enable/disable settings for REPL eval.** re-frame-pair gates this through Claude Code's `allowed-tools` in SKILL.md frontmatter.

---

## Top 3 actionable takeaways (ranked by leverage)

1. **Buffered console/REPL output log with `:who` tagging.** This is the biggest gap. Handlers that `console.log` or throw quietly are currently invisible to the agent; the epoch buffer only captures re-frame's own trace events. A ring buffer of `js/console.*` output, tagged with `:user`/`:claude`/`:handler-error`, would close that blind spot. Implementation path: extend `tagged-dispatch!` (runtime.cljs:699) to set a thread-local "who" marker, hook `js/console` once at injection, surface via `console/tail --since-id N --who claude`. Estimate: ~80 LOC runtime + 40 LOC ops.clj + 1 shim.

2. **`repl/symbol-info` for re-frame's own API surface.** Cheap to add, removes a class of "agent reads source for docstring" round-trips. `(cljs.repl/doc …)` against the live compiler is the simplest path. Estimate: ~30 LOC runtime + 1 op-table row in SKILL.md.

3. **Multi-build awareness in `discover-app.sh`.** Add a `--list` mode and surface all candidate shadow builds in the failure case so multi-build projects don't get silent wrong-build attachment. Estimate: ~20 LOC in `ops.clj:read-port` (STATUS.md §Spike findings 1).

The structural-editing tools (#4) and image-return (#5) are notable but lower-leverage — the first competes with Claude Code's built-in `Edit`, the second is gated on harness multimodal support.
