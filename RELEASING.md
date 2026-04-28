# Releasing re-frame-pair

Releases go out through the `release.yml` GitHub Actions workflow when
a semver tag is pushed. This doc captures the checklist and the expected
tag formats.

## Prerequisites

- `NPM_TOKEN` secret set on the repo, with publish scope on `@day8` (npm Automation token).
- Locally: a checkout of `main`, npm-cli installed (only needed if you want to dry-run `npm pack` before tagging).
- You have maintainer rights on the `day8` npm scope.

## Version numbers

Single source of truth: `package.json` `version`. Two other files must match:

- `package.json`
- `.claude-plugin/plugin.json`

The release workflow cross-checks these at publish time and fails if they drift.

## Cutting a release

1. **Decide the version.** Follow semver. Pre-1.0, everything is pre-release: use `0.x.y-alpha.N`, `0.x.y-beta.N`, `0.x.y-rc.N`.
2. **Update the three version strings** (package.json, plugin.json):
   ```bash
   npm version 0.1.0-alpha.2 --no-git-tag-version
   # then bump .claude-plugin/plugin.json's "version" by hand to match
   ```
3. **Commit the version bump** on `main`:
   ```bash
   git add package.json .claude-plugin/plugin.json
   git commit -m "Release v0.1.0-alpha.2"
   ```
4. **Tag and push:**
   ```bash
   git tag v0.1.0-alpha.2
   git push origin main --tags
   ```
5. **Watch the workflow.** `release.yml` will:
   - verify tag == package.json version
   - verify plugin.json matches
   - smoke-test the babashka shims
   - (future) run CLJS unit tests against the fixture
   - `npm publish` with public access under `@day8` scope
   - create a GitHub release with auto-generated notes; marked prerelease for `-alpha`/`-beta`/`-rc`
6. **Verify publish:** `npm view @day8/re-frame-pair versions --json` should list the new version.
7. **Smoke-test install** from a clean machine:
   ```bash
   npx skills add day8/re-frame-pair
   # or, when using the plugin path:
   # /plugin install re-frame-pair@day8
   ```

## Tag format

Regex the workflow accepts: `v[0-9]+.[0-9]+.[0-9]+*`

Examples:
- `v0.1.0-alpha.1` ✓
- `v0.1.0-rc.1` ✓
- `v1.0.0` ✓
- `0.1.0` ✗ (missing `v`)
- `v0.1` ✗ (incomplete semver)

## Rolling back a bad release

npm doesn't allow unpublishing published versions after 72 hours, and even inside 72 hours it's discouraged. The normal path is:

1. Publish a patched version (e.g., `v0.1.0-alpha.2` → `v0.1.0-alpha.3`).
2. `npm deprecate @day8/re-frame-pair@0.1.0-alpha.2 "Superseded by 0.1.0-alpha.3 — bug X"`.

## Release notes

Two surfaces, both maintained per release:

- **`CHANGELOG.md`** (in-repo) — the authoritative on-disk record of
  user-visible changes per version. Operators installing the skill
  via `/plugin install` can grep this without leaving the editor.
  Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
  Every PR that changes operator/agent-facing behaviour adds an
  entry under `[Unreleased]`; cutting a tag promotes that section.
- **GitHub release notes** — generated automatically from commits +
  PRs since the previous tag by `action-gh-release`. To shape them,
  use conventional-commit-ish prefixes on PR titles (`feat:`, `fix:`,
  `docs:`, `chore:`). Mirrors but does not replace `CHANGELOG.md`.

## Pre-1.0 release cadence

While pre-alpha / pre-spike:

- Tag `v0.1.0-alpha.N` for major surface changes.
- Tag `v0.1.0-beta.N` once the spike (`§8a` of `docs/initial-spec.md`) has validated the plumbing.
- Tag `v0.1.0-rc.N` once there's a working end-to-end path against the fixture.
- `v0.1.0` when the full v1 scope in the spec is implemented and tested.

## Distributing via Claude Code's plugin path

`npm publish` handles the Agent Skill distribution (`npx skills add day8/re-frame-pair`). For Claude Code Plugin distribution (`/plugin install`), the plugin discovery currently pulls from the same repo — no separate publish step needed. If Claude's plugin registry grows a separate index, update this doc.
