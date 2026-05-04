# Contributing to re-frame-pair

> **Audience: anyone (human or agent) working on the re-frame-pair skill itself.** If you're using the skill to debug a host re-frame app, the entry point is [`SKILL.md`](SKILL.md) and detail-on-demand lives under [`docs/skill/`](docs/skill/) — read those, not this file.

This file holds the maintainer-facing workflow: issue tracking, session-completion protocol, build & test commands, the fixture integration loop, and shell-non-interactivity tips.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:ca08a54f -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

## Session Completion

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd dolt push
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds
<!-- END BEADS INTEGRATION -->

> **Note on regenerating the BEADS INTEGRATION block, and the `bd setup --check` warning.**
>
> The `bd setup claude` recipe is hardcoded to `CLAUDE.md` — there is no per-recipe target override. As a result:
>
> - Running bare `bd setup claude` will write the block back into `CLAUDE.md` (re-polluting our router). Use `bd setup claude -o CONTRIBUTING.md` instead to regenerate the block in this file.
> - `bd setup claude --check` will warn `⚠ CLAUDE.md exists but no beads section found`. **This warning is expected** and reflects the deliberate decoupling — ignore it. The hooks side of the same `--check` (`✓ Project hooks installed`) is still meaningful.
>
> A bd-tooling improvement (configurable target per recipe) would let us drop this caveat. Tracked upstream: [gastownhall/beads#3698](https://github.com/gastownhall/beads/issues/3698).

## Non-Interactive Shell Commands

`cp`, `mv`, `rm` may be aliased to `-i` (interactive) on some systems. Always pass `-f` so an agent doesn't hang on a y/n prompt:

```bash
cp -f source dest           # not: cp source dest
mv -f source dest           # not: mv source dest
rm -f file                  # not: rm file
rm -rf directory            # not: rm -r directory
cp -rf source dest          # not: cp -r source dest
```

Other commands that may prompt:
- `scp` / `ssh` — `-o BatchMode=yes` to fail rather than prompt
- `apt-get` — `-y`
- `brew` — `HOMEBREW_NO_AUTO_UPDATE=1`

## Build & Test

### Unit tests (no fixture needed)
```bash
npm test   # compiles tests/runtime/ and runs via node
```

### Integration testing against the live fixture
When the operator has `cd tests/fixture && npx shadow-cljs watch app` running and a browser tab open at http://localhost:8280, agents can drive the spike scripts directly without operator-in-the-loop. They're shell-callable, return EDN, and auto-reinject after browser refreshes (commit b3a12e8).

Run from `tests/fixture/`:

- `../../scripts/discover-app.sh` — health report
- `../../scripts/eval-cljs.sh '<form>'` — eval CLJS in browser
- `../../scripts/dispatch.sh --trace '[:ev args]'` — fire event, return epoch
- `../../scripts/watch-epochs.sh --count N` — live-watch
- `../../scripts/trace-recent.sh <ms>` — epochs in last N ms
- `../../scripts/inject-runtime.sh` — force re-ship runtime
- `../../scripts/tail-build.sh --probe '<form>'` — wait for hot-reload to land

To dispatch events without operator clicks: `eval-cljs.sh '(re-frame.core/dispatch-sync [:counter/inc])'`.

A `:reinjected? true` flag on a response means the runtime was re-shipped because the sentinel was missing (operator probably refreshed). Informational, not an error.

**Agents must NOT:**
- `pkill` the operator's running `shadow-cljs watch app` (use `npx shadow-cljs compile app` from a fresh shell if you need a one-shot build check)
- Refresh or open the browser (auto-reinject handles refreshes)
- Wait for the operator to click UI elements — dispatch the event yourself via `eval-cljs.sh '(re-frame.core/dispatch-sync [:event/id args])'`

## Cutting a Release

> **Read [`RELEASING.md`](RELEASING.md) before you tag.** A release is more than a commit + tag — at minimum it requires the version bumped *in lockstep across two files* (`package.json` and `.claude-plugin/plugin.json`; the release workflow gates on the match) and the `CHANGELOG.md` `[Unreleased]` section promoted to the new version. Skipping any of these silently produces a broken release. The full checklist + tag-format rules + rollback path live in `RELEASING.md`.

## Architecture Overview

_Add a brief overview of your project architecture_

## Conventions & Patterns

_Add your project-specific conventions here_
