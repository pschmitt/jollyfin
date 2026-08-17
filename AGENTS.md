# AGENTS.md

Repository instructions for AI coding agents working on JollyFin.

See `.just/android-app-ci/AGENTS-shared.md` for the fleet-wide task-tracking convention, dev
environment (`nix develop`/`git-hooks.nix`), CI-is-the-sole-lint-authority rule, and physical test
device docs (this app only has Mi Pad 4, not the other two fleet devices) - read it alongside this
file, not instead of it.

## Task tracking

- This project's `TODO.md` prefix is `JF-N`. Entries through `FINDROID-81` predate the
  `dev.pschmitt.jellyfin`/Jollyfin rename (see `FINDROID-72`) and keep their original prefix as a
  historical record - don't renumber them. `JF-N` starts fresh at `82` for everything after.

## CLI parity

- `cli/jollyfin-cli` (see FINDROID-45/FINDROID-49) talks to the app over its local control API
  (`core/.../localcontrol/`) and is meant to eventually cover most of what the app itself can do
  or configure. When adding new app functionality that has a CLI-shaped equivalent (a setting,
  an action like triggering a download, something worth scripting or inspecting from Termux),
  add the matching local control endpoint (`LocalControlRouter`) and CLI subcommand in the same
  change, not as a follow-up. Skip this for things with no sensible CLI shape (e.g. purely visual
  UI/theme changes).
- New CLI subcommands must render both a pretty default (table via `print_table`, or plain text
  for non-tabular results) and support `--json` (the raw `{status, body}` envelope) — see the
  existing commands for the pattern. Verify new commands end-to-end on a real device from actual
  Termux (not just `adb shell`), the same standard FINDROID-49 established after a root-requirement
  bug was only caught that way.

## Dev environment

See the shared doc for the `nix develop`/`git-hooks.nix` basics and the no-ktfmt-pre-commit-hook
rationale (the `settings.gradle.kts` `include()` blank-line incident it references happened here).
Prefer the `justfile` recipes over raw `./gradlew`/`ssh`/`adb` invocations — run `just --list` for
the full set. It wraps everything below (remote builds, fetching APKs, and Mi Pad 4
install/logcat/adb-enable) in composable recipes.

## Builds

- **Never run Gradle builds locally on this machine (`fnuc`)** — this is a multi-module
  Android project and local compiles are heavy. Always build on `rofl-13.brkn.lol` or
  `rofl-14.brkn.lol` instead. The `justfile` automates this:
  - `just sync [host]` — rsync the working tree to the remote build host (excludes
    `.git`, `build/`, `.gradle/`). The remote destination directory is namespaced per git
    worktree (`jollyfin-verify-<worktree-dirname>` when run from a linked worktree, e.g. one
    of Claude's isolated agent worktrees under `.claude/worktrees/`; plain `jollyfin-verify`
    from the main checkout) so parallel agents each building/testing in their own worktree
    don't clobber each other's remote sync directory mid-build. Override with
    `JOLLYFIN_REMOTE_PATH` if you need a specific shared path instead.
  - `just gradle [host] <tasks...>` — sync, then run arbitrary Gradle tasks remotely via
    `nix develop --command ./gradlew <tasks>`.
  - `just build [flags]` — build the libre-flavor APK remotely. Flags: `--tv`/`--phone`
    (default `--phone`), `--debug`/`--release` (default `--debug`), `--host=<host>`.
    E.g. `just build --debug` (phone debug, the common case) or `just build --tv --release`.
  - `just lint` — remote `ktfmtCheck` (mirrors `.github/workflows/lint.yaml`).
  - `just test` — remote unit test suites for `:data` and `:core`.
  - `just fetch [flags]` — scp the built APK split back to `./dist/` locally. Same flags
    as `just build`, plus `--abi=<abi>` (default `arm64-v8a`).
  - `just build-fetch [flags]` — build + fetch in one step. Same flags as `just build`.
  - Flag parsing for `build`/`fetch`/`build-fetch`/`deploy` is shared via
    `.just-parse-flags.sh` (not a real just recipe — plain bash, invoked by those recipes
    since just has no native flag/option parser).
  - Manually, the equivalent is:
    1. `rsync -az --delete --exclude='.git' --exclude='**/build/' --exclude='.gradle/' --exclude='**/.gradle/' ./ rofl-13.brkn.lol:~/devel/private/pschmitt/jollyfin-verify/`
    2. `ssh rofl-13.brkn.lol 'cd ~/devel/private/pschmitt/jollyfin-verify && nix develop --command ./gradlew <tasks>'`
    3. Re-run the rsync after every local edit before rebuilding remotely — there is no
       watch/sync daemon, it's a one-shot copy each time.
  - Plain Nix derivation builds (non-Gradle) already offload to rofl-13/rofl-14 via
    configured remote builders; only Gradle itself needs this manual redirect, since
    Gradle always executes wherever it's invoked regardless of Nix's remote-builder config.
- Gradle modules use product flavors (at least a "Libre" flavor), so bare task names like
  `:core:compileDebugKotlin` are ambiguous. Use the flavor-qualified task name (e.g.
  `compileLibreDebugKotlin`), or run `./gradlew tasks` in the target module first to confirm
  the exact name.
- Formatting a Kotlin file directly (not a full Gradle build) is fine to run locally:
  `just format` runs the standalone `ktfmt` CLI over all tracked `.kt`/`.kts` files.
  Treat it as an advisory quick pass only — it's a newer `ktfmt` version than CI's
  pinned one (see the pre-commit note above) — and confirm with `just lint` before
  relying on it.

## Releases

- Before tagging/pushing a release (`vX.Y.Z`), run `just lint` and confirm it passes on the exact
  commit being tagged. Do not tag a release on a commit with known-unformatted Kotlin. A failing
  `Lint` workflow on a release tag doesn't block the `Release`/`Play Store Release` workflows (they
  run independently), so a bad tag still ships — it just leaves CI red and forces a follow-up patch
  release to fix formatting alone. This happened with 2.14.3: it shipped with a ktfmt violation
  that had to be fixed in a separate 2.14.4 release.
- Bumping `versionCode` back down (e.g. reverting an in-progress bump) is unsafe once any tag with
  the higher code has already had its `Play Store Release` workflow succeed — Play Store rejects
  any later upload whose `versionCode` isn't strictly greater than what's already on the track
  (`You cannot rollout this release because it does not allow any existing users to upgrade`).
  Check `gh run list` for a prior successful `Play Store Release` at the higher code before ever
  lowering `Versions.kt`'s `APP_CODE`.

## Physical test device

See the shared doc's Mi Pad 4 section (`just mipad-connect`/`mipad-install`/`mipad-uninstall`/
`mipad-logcat`/`mipad-shell`, the signature-mismatch gotcha) - jollyfin is the one app in the
fleet that doesn't also have Zenfone/Pixel 5 recipes. `just deploy [flags]` is this repo's own
build+fetch+install-on-MiPad recipe, same flags as `just build` (e.g. `just deploy --debug`).
