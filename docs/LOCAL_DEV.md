# Running re-frame-pair locally (no npm needed)

npm publishing is for distribution to other people. While developing the skill itself — or dogfooding it against a real re-frame app before the first release — you run it straight from a clone. Three install paths, most to least convenient.

## Prerequisites on your machine

Same as the README's *Requirements*:

- [`babashka`](https://babashka.org) on `PATH` — the shell shims exec `bb` regardless of how the skill is installed.
- [Claude Code](https://docs.claude.com/en/docs/claude-code).
- A re-frame + re-frame-10x + re-com + shadow-cljs app to exercise it against.

## 1. Symlink (recommended for dev)

Edits you make in the repo are live immediately — no copy to keep in sync.

### macOS / Linux

```bash
mkdir -p ~/.claude/skills
ln -s "$HOME/code/re-frame-pair" ~/.claude/skills/re-frame-pair
```

### Windows

With Developer Mode or admin:

```powershell
New-Item -ItemType SymbolicLink `
  -Path "$env:USERPROFILE\.claude\skills\re-frame-pair" `
  -Target "$env:USERPROFILE\code\re-frame-pair"
```

Without admin, use a directory junction:

```cmd
mklink /J %USERPROFILE%\.claude\skills\re-frame-pair %USERPROFILE%\code\re-frame-pair
```

Junctions behave like symlinks for read purposes; fine for skill loading.

## 2. Copy (snapshot the current state)

```bash
cp -r ~/code/re-frame-pair ~/.claude/skills/re-frame-pair
```

Simple, but you have to re-copy after every change. Useful if you want to pin a specific commit and keep iterating on the repo itself without affecting Claude's view of the skill.

## 3. Project-local (only active in one app)

Same content, but under a specific target project rather than your home directory:

```bash
cd ~/some-re-frame-app
mkdir -p .claude/skills
ln -s "$HOME/code/re-frame-pair" .claude/skills/re-frame-pair
```

Useful if you only want the skill loaded when you open the specific app you're debugging — and useful for testing what the project-local install flow feels like before anyone ships the skill.

## Invoking it in Claude Code

Once the skill directory is in place:

- **Implicit**: ask about your running re-frame app in natural language (*"what's in `app-db` under `:cart`?"*). Claude auto-matches the skill's description.
- **Explicit**: type `/re-frame-pair` or name it in a prompt (*"Using re-frame-pair, trace `[:cart/apply-coupon …]`"*).

First use of a session runs `scripts/discover-app.sh` — that connects to your shadow-cljs nREPL, verifies prerequisites, and injects the runtime namespace.

## Dev loop: iterating on the skill itself

The power of the symlink approach is that editing `SKILL.md` / `scripts/runtime.cljs` / `scripts/ops.clj` in the repo takes effect immediately:

| You edited… | What Claude sees after your next prompt |
|---|---|
| `SKILL.md` frontmatter or body | New vocabulary / recipes on next invocation (may need to restart the Claude Code session for the description change to be re-indexed — depends on how Claude caches skill metadata). |
| `scripts/ops.clj` | Next `bb` invocation picks it up — no action needed. |
| `scripts/runtime.cljs` | The injected code in the browser is **stale** until you explicitly re-push it. Run `scripts/inject-runtime.sh` in the connected session; it now force-reinjects regardless of the session sentinel so your edits land without a full page refresh. |
| Shell shims (`*.sh`) | Next invocation picks them up. |

## Troubleshooting

### The skill doesn't appear in `/` completion

- Confirm the directory landed where Claude Code looks: `ls ~/.claude/skills/re-frame-pair/` (or the project-local equivalent).
- Confirm `SKILL.md` is at the top level of that directory, not nested.
- Restart Claude Code — it reads the skill registry at session start.
- Check the skill name in `SKILL.md`'s frontmatter — it must match the directory name (`re-frame-pair`).

### `babashka-missing` error from `discover-app.sh`

`bb` isn't on `PATH`. Verify with `which bb` (macOS/Linux) or `where bb` (Windows). Install:

- macOS: `brew install borkdude/brew/babashka`
- Linux / Windows: [babashka install guide](https://github.com/babashka/babashka#installation)

Restart the shell (and Claude Code) so the new `PATH` takes effect.

### `:nrepl-port-not-found`

`discover-app.sh` couldn't locate `target/shadow-cljs/nrepl.port`, `.shadow-cljs/nrepl.port`, or `$SHADOW_CLJS_NREPL_PORT`. Start your dev build:

```bash
npx shadow-cljs watch <build-id>
```

…and make sure nREPL is enabled for the build.

### `:ns-not-loaded :missing :re-frame-10x`

Your shadow-cljs build doesn't have re-frame-10x as a dev-time preload. Add it per [10x's README](https://github.com/day8/re-frame-10x).

### `:trace-enabled-false`

The build wasn't compiled with `re-frame.trace.trace-enabled?` set to `true` via `:closure-defines`. 10x's install guide covers this.

### Dispatch / trace ops say `:reason :no-epoch-appeared` or `:reason :no-new-epoch`

`re-frame.trace` debounces callback delivery (~50ms) — 10x's `::receive-new-traces` only runs once the buffer flushes. `tagged-dispatch-sync!` returns `:before-id` and defers id resolution; `dispatch-and-collect` waits trace-debounce + 1 frame (80ms total) before sampling the head. If you're seeing `:no-new-epoch` consistently, check that `re-frame.trace.trace-enabled?` is true and that `day8.re-frame-10x.preload` is in your `:preloads`.

### Dispatch / trace ops say `:reason :ten-x-missing`

`runtime.cljs` reads epochs from 10x's *inlined* re-frame app-db at `day8.re-frame-10x.inlined-deps.re-frame.<ver>.re-frame.db/app-db`. The version slug (`v1v3v0` today) is probed against a known list. If 10x ships a new inlined version, add the slug to `inlined-rf-version-paths` in `runtime.cljs`. The same applies to the `undo/*` ops — they dispatch into 10x's inlined re-frame instance, so 10x must be loaded.

### DOM ops return `{:reason :re-com-debug-disabled}` or `{:src nil}`

Two separate preconditions:

- re-com's debug instrumentation must be enabled (a flag in `re-com.config`).
- Call sites must pass `:src (at)` — only those components carry `data-rc-src`.

Both are re-com's own conventions; see re-com's debug docs.

### Watch ops don't stream anything

Two likely causes:

- **Stub data** (see above). If `scripts/eval-cljs.sh '(re-frame-pair.runtime/epoch-count)'` returns `0`, the buffer accessor isn't yet wired.
- **No activity matches the predicate**. Try `scripts/watch-epochs.sh --count 5` with no predicate to confirm the transport works, then add filters.

### Changes to `runtime.cljs` aren't taking effect

The session sentinel fast-path in `discover-app.sh` skips re-injection if the runtime is already present. To push edits into a live session, run `scripts/inject-runtime.sh` — it force-reinjects regardless.

## Uninstall / reset

```bash
# symlink or junction:
rm ~/.claude/skills/re-frame-pair

# copy:
rm -rf ~/.claude/skills/re-frame-pair
```

Restart Claude Code. The skill disappears from completion; no residual state.
