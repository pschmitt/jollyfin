# AGENTS.md

Repository instructions for AI coding agents working on JollyFin.

## Task tracking

- `TODO.md` is the running backlog/changelog for this fork, one `## FINDROID-N:`
  entry per feature or fix, numbered sequentially (never reuse or renumber an id).
  Each entry has a checklist of sub-items (`- [ ]`/`- [x]`) and ends with a
  `Status:` line (`not started` / `in progress` / `mostly done` / `**done**`,
  plus a date and how it was verified).
- Before starting any non-trivial new feature or fix, add (or update) a
  `FINDROID-N` entry describing it — even if the same conversation immediately
  goes on to implement it. Update the checklist/status as work actually lands,
  rather than writing the whole entry retroactively once everything's finished.
  This keeps `TODO.md` an accurate record of what's done vs. still open, not
  just a summary written after the fact.
- Trivial one-off asks (a typo, a single-line tweak) don't need their own entry.

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

- `nix develop` provides the full toolchain (JDK 21, Android SDK, `just`, `ktfmt`) and
  installs the repo's pre-commit hooks (see `flake.nix`'s `git-hooks.nix` integration —
  trailing whitespace, EOF fixer, merge-conflict/large-file checks, `nixfmt`, `statix`).
  The generated `.pre-commit-config.yaml` is gitignored — it's regenerated from
  `flake.nix` on every shell entry, don't hand-edit it.
  - Deliberately **no** `ktfmt` pre-commit hook: nixpkgs only ships a recent standalone
    `ktfmt` (0.63+), but the project's Gradle plugin pins `ktfmt` 0.26.0 (see
    `gradle/libs.versions.toml`), and the two format some constructs differently — a
    hook running the wrong version could "fix" a file into a state that then fails
    CI's real `ktfmtCheck`. This actually happened once: it inserted spurious blank
    lines between every `include()` in `settings.gradle.kts`. Use `just lint` (runs
    the pinned Gradle plugin remotely) as the authoritative formatting check. If `just lint`
    and CI's `Lint` workflow ever disagree, trust CI: `.github/workflows/lint.yaml`'s ktfmt job
    auto-uploads a `ktfmt-diff-patch` artifact whenever `ktfmtCheck` fails (also dispatchable on
    demand via `gh workflow run lint.yaml`), containing exactly what `./gradlew ktfmtFormat`
    would change in CI's own environment. Download it
    (`gh run download <run-id> -n ktfmt-diff-patch`) and `git apply` it rather than guessing.
- Prefer the `justfile` recipes over raw `./gradlew`/`ssh`/`adb` invocations — run
  `just --list` for the full set. It wraps everything below (remote builds, fetching
  APKs, and Mi Pad 4 install/logcat/adb-enable) in composable recipes.

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

- A **Mi Pad 4** (`arm64-v8a`) is available for installing and checking debug builds.
  Reachable via SSH at `mi-pad-4.lan`, port `8022` (Termux, rooted). The `justfile` wraps
  the common operations, all built on real `adb` (not `scp`/`pm install`):
  - `just mipad-connect` — the core primitive. Finds the port `adbd` is actually
    listening on via `su -c 'ss -ltnp'` over SSH (adbd is usually already running — the
    device doesn't rely on a fixed port), `adb connect`s to it, and prints the resulting
    `host:port` on stdout (status goes to stderr) so other recipes can capture it with
    `target=$(just mipad-connect)`. Only if nothing is listening does it fall back to
    forcing `adbd` on via root (`setprop service.adb.tcp.port` + restart).
  - `just mipad-install <apk>` — connects, then `adb install -r`. Deliberately avoids
    `scp` into `/sdcard` + `pm install`: `system_server` can't read the FUSE-backed
    `/sdcard` back (SELinux denies it — `avc: denied { read } ... tcontext=u:object_r:fuse:s0`),
    and Termux's `sshd` has no `sftp-server` subsystem configured anyway (plain `scp`
    fails with "Connection closed" unless you pass `-O` for the legacy protocol). `adb
    install` sidesteps all of that.
  - `just deploy [flags]` — build the APK remotely, fetch it, and install it on the
    Mi Pad 4 in one step. Same flags as `just build`, e.g. `just deploy --debug`.
  - `just mipad-logcat [filter]` — tail `logcat` from the device, optionally grepped.
  - `just mipad-uninstall <pkg>` — `adb uninstall` a package (see the signature-mismatch
    gotcha below).
  - `just mipad-shell` — interactive SSH shell on the device.
  - Signature mismatch gotcha: if the device already has a build signed with a different
    key than the one you're installing, install fails with
    `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Fix is `just mipad-uninstall <applicationId>`
    then install fresh — this wipes local app data (Room DB, playback positions,
    download records). Confirm with the user before doing this if it's not their own
    throwaway data.
