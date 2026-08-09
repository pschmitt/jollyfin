# JollyFin task runner.
#
# Gradle must never run on this machine directly - it's a heavy multi-module Android
# project, so every build/test/lint recipe here shells out to a remote host
# (rofl-13.brkn.lol or rofl-14.brkn.lol) over SSH instead. See AGENTS.md.

set shell := ["bash", "-euo", "pipefail", "-c"]

remote_host := env_var_or_default("JOLLYFIN_REMOTE_HOST", "rofl-13.brkn.lol")

# Empty for the main checkout; "-<worktree-dirname>" when run from a linked git worktree (e.g.
# one of Claude's isolated agent worktrees under .claude/worktrees/). Keeps parallel worktree
# agents from clobbering each other's remote sync directory mid-build - see AGENTS.md.
worktree_suffix := `gd=$(git rev-parse --git-dir); gcd=$(git rev-parse --git-common-dir); if [ "$gd" != "$gcd" ]; then basename "$(git rev-parse --show-toplevel)" | sed 's/^/-/'; fi`

remote_path := env_var_or_default("JOLLYFIN_REMOTE_PATH", "~/devel/private/pschmitt/jollyfin-verify" + worktree_suffix)
local_dist := env_var_or_default("JOLLYFIN_DIST_DIR", "./dist")

mipad_host := env_var_or_default("MIPAD_HOST", "mi-pad-4.lan")
mipad_ssh_port := env_var_or_default("MIPAD_SSH_PORT", "8022")
mipad_adb_port := env_var_or_default("MIPAD_ADB_PORT", "5555")
mipad_abi := env_var_or_default("MIPAD_ABI", "arm64-v8a")

# The Play Console listing's actual package, matching applicationId in app/phone|tv/build.gradle.kts
# and `gpc apps list` - NOT fastlane/Appfile's "dev.pschmitt.jellyfin" (that's the separate debug
# build under test for screenshots, and appears to be a pre-existing typo there regardless).
play_package := "dev.pschmitt.jollyfin"

# List all available recipes
default:
    @just --list

# --- Remote build (rofl-13 / rofl-14) -------------------------------------

# Sync the working tree to the remote build host (excludes .git/build/.gradle). The .git
# exclude has no trailing slash so it matches both a real .git/ directory (the main checkout)
# and a plain .git file (a linked worktree's gitlink, which points at a local-only
# .git/worktrees/... path that doesn't exist on the remote host and breaks `nix develop` there
# if it gets copied over).
sync host=remote_host:
    rsync -az --delete \
        --exclude='.git' --exclude='**/build/' \
        --exclude='.gradle/' --exclude='**/.gradle/' \
        ./ {{host}}:{{remote_path}}/

# Run one or more Gradle tasks on the remote host (syncs first)
gradle host=remote_host *tasks: (sync host)
    ssh {{host}} 'cd {{remote_path}} && nix develop --command ./gradlew {{tasks}}'

# Build an APK remotely. Flags: --tv/--phone (default phone), --debug/--release (default debug), --host=<host>
# Release builds are signed with the persistent CI keystore (fetched from the rbw entry
# "Jollyfin CI Signing Keystore" and staged on the build host only for the duration of the
# build). Without CI_KEYSTORE_*, Gradle silently signs with the host's throwaway
# ~/.android/debug.keystore and devices carrying CI-signed installs (GitHub releases /
# Obtainium) reject the APK with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
build *flags:
    #!/usr/bin/env bash
    set -euo pipefail
    read -r variant flavor host abi < <("{{justfile_directory()}}/.just-parse-flags.sh" {{remote_host}} {{mipad_abi}} -- {{flags}})
    if [[ "$flavor" != "release" ]]
    then
      just gradle "$host" ":app:${variant}:assembleLibre${flavor^}"
      exit 0
    fi
    if ! rbw unlocked >/dev/null 2>&1
    then
      printf 'rbw is locked - run "rbw unlock" first (needed for the CI signing keystore)\n' >&2
      exit 2
    fi
    tmpdir=$(mktemp -d)
    trap 'rm -rf "$tmpdir"' EXIT
    git_revision=$(git describe --always --abbrev=12 --dirty)
    rbw attachment get "Jollyfin CI Signing Keystore" --attachment jollyfin-ci.jks --output "$tmpdir/jollyfin-ci.jks"
    rbw attachment get "Jollyfin CI Signing Keystore" --attachment jollyfin-ci-keystore.env --output "$tmpdir/jollyfin-ci-keystore.env"
    just sync "$host"
    ssh "$host" 'mkdir -p ~/.jollyfin-ci-tmp && chmod 700 ~/.jollyfin-ci-tmp'
    scp -q "$tmpdir/jollyfin-ci.jks" "$tmpdir/jollyfin-ci-keystore.env" "$host:.jollyfin-ci-tmp/"
    # The keystore is shredded on the host whether or not the build succeeds.
    ssh "$host" "
      artifact={{remote_path}}/app/${variant}/build/outputs/apk/libre/${flavor}/${variant}-libre-${abi}-${flavor}.apk
      previous_mtime=0
      [[ -f \"\$artifact\" ]] && previous_mtime=\$(stat -c %Y \"\$artifact\")
      set -a
      . ~/.jollyfin-ci-tmp/jollyfin-ci-keystore.env
      set +a
      export CI_KEYSTORE_PATH=\$HOME/.jollyfin-ci-tmp/jollyfin-ci.jks
      export GIT_REVISION='$git_revision'
      cd {{remote_path}} && nix develop --command ./gradlew ':app:${variant}:assembleLibre${flavor^}' --rerun-tasks 2>&1 | tee ~/jollyfin-release-build.log
      rc=\$?
      if [[ \$rc -eq 0 && (! -f \"\$artifact\" || \$(stat -c %Y \"\$artifact\") -le \$previous_mtime) ]]
      then
        echo 'release build did not refresh its APK artifact' >&2
        rc=1
      fi
      if [[ \$rc -eq 0 ]] && ! (cd {{remote_path}} && nix develop --command sh -c 'unzip -p "\$1" "classes*.dex" | strings | grep -Fx "\$2" >/dev/null' sh "\$artifact" "\$GIT_REVISION")
      then
        echo "release APK does not contain expected revision: \$GIT_REVISION" >&2
        rc=1
      fi
      shred -u ~/.jollyfin-ci-tmp/* 2>/dev/null || true
      rmdir ~/.jollyfin-ci-tmp 2>/dev/null || true
      exit \$rc
    "

# Copy a built APK split back to ./dist locally. Same flags as `build`, plus --abi=<abi>
fetch *flags:
    #!/usr/bin/env bash
    set -euo pipefail
    read -r variant flavor host abi < <("{{justfile_directory()}}/.just-parse-flags.sh" {{remote_host}} {{mipad_abi}} -- {{flags}})
    mkdir -p {{local_dist}}
    scp "$host:{{remote_path}}/app/${variant}/build/outputs/apk/libre/${flavor}/${variant}-libre-${abi}-${flavor}.apk" {{local_dist}}/
    if [[ "$flavor" == "release" ]]
    then
      apk={{local_dist}}/${variant}-libre-${abi}-${flavor}.apk
      git_revision=$(git describe --always --abbrev=12 --dirty)
      if ! unzip -p "$apk" 'classes*.dex' | strings | grep -Fx "$git_revision" >/dev/null
      then
        rm -f "$apk"
        echo "fetched release APK does not contain expected revision: $git_revision" >&2
        exit 1
      fi
    fi

# Build an APK remotely and copy it back to ./dist. Same flags as `build`.
build-fetch *flags:
    #!/usr/bin/env bash
    set -euo pipefail
    just build {{flags}}
    just fetch {{flags}}

# ktfmt check via Gradle, remotely (mirrors .github/workflows/lint.yaml)
lint host=remote_host: (gradle host "ktfmtCheck")

# Run the fast unit test suites remotely
test host=remote_host: (gradle host ":data:testDebugUnitTest" ":core:testLibreDebugUnitTest")

# Remote `./gradlew clean`
clean host=remote_host: (gradle host "clean")

# --- Mi Pad 4 test device (rooted, Termux SSH on port 8022) --------------

# Run an arbitrary command on the Mi Pad 4 over SSH
mipad-ssh +cmd:
    ssh -p {{mipad_ssh_port}} {{mipad_host}} "{{cmd}}"

# Interactive shell on the Mi Pad 4
mipad-shell:
    ssh -p {{mipad_ssh_port}} {{mipad_host}}

# Find the port adbd is actually listening on (via `ss -ltnp` over root SSH),
# starting it as a fallback if it isn't running at all, then `adb connect` to
# it. Prints the resulting "host:port" adb target on stdout so other recipes
# can capture it - status/progress goes to stderr.
mipad-connect:
    #!/usr/bin/env bash
    set -euo pipefail
    port=$(ssh -p {{mipad_ssh_port}} {{mipad_host}} "su -c 'ss -ltnp'" 2>/dev/null \
        | awk '/adbd/ { n = split($4, a, ":"); print a[n]; exit }')
    if [ -z "$port" ]; then
        echo "adbd not listening - starting it via root shell" >&2
        ssh -p {{mipad_ssh_port}} {{mipad_host}} \
            "su -c 'setprop service.adb.tcp.port {{mipad_adb_port}} && stop adbd && start adbd'" >&2
        sleep 1
        port={{mipad_adb_port}}
    fi
    target="{{mipad_host}}:$port"
    adb connect "$target" >&2
    echo "$target"

# Install an APK on the Mi Pad 4 over adb (network, via mipad-connect).
# Simpler and more reliable than scp + `pm install`: adb push/install runs as
# adbd, which doesn't hit the SELinux/FUSE permission issues a plain scp into
# /sdcard runs into when system_server tries to read the file back.
mipad-install apk:
    #!/usr/bin/env bash
    set -euo pipefail
    target=$(just mipad-connect)
    adb -s "$target" install -r {{apk}}

# Uninstall a package from the Mi Pad 4 (e.g. after a signing-key mismatch -
# see AGENTS.md). WARNING: wipes that app's local data (Room DB, playback
# positions, downloads).
mipad-uninstall pkg:
    #!/usr/bin/env bash
    set -euo pipefail
    target=$(just mipad-connect)
    adb -s "$target" uninstall {{pkg}}

# Tail logcat from the Mi Pad 4, optionally filtered by a grep pattern
mipad-logcat filter="":
    #!/usr/bin/env bash
    set -euo pipefail
    target=$(just mipad-connect)
    if [ -n "{{filter}}" ]; then
        adb -s "$target" logcat | grep -i --line-buffered "{{filter}}"
    else
        adb -s "$target" logcat
    fi

# Build an APK remotely, fetch it, and install it on the Mi Pad 4. Same flags as `build`.
deploy *flags:
    #!/usr/bin/env bash
    set -euo pipefail
    read -r variant flavor host abi < <("{{justfile_directory()}}/.just-parse-flags.sh" {{remote_host}} {{mipad_abi}} -- {{flags}})
    just build-fetch {{flags}}
    just mipad-install "{{local_dist}}/${variant}-libre-${abi}-${flavor}.apk"

# --- Formatting / hooks ----------------------------------------------------

# Format Kotlin sources locally with ktfmt (lightweight - not a Gradle build,
# safe to run on this machine). CAUTION: this is nixpkgs' standalone ktfmt,
# which is a newer version than the one CI actually uses (see
# gradle/libs.versions.toml) - the two format some constructs differently.
# Treat this as an advisory quick pass, not a substitute for `just lint`.
format:
    ktfmt --kotlinlang-style $(git ls-files '*.kt' '*.kts')

# Nix formatting/lint for this repo's flake.nix (per global AI context rules)
nix-fmt:
    nixfmt flake.nix

nix-lint:
    nix develop --command statix check

# --- Play Store screenshot fixture (FINDROID-71) ----------------------------

# Fetch the small, official Creative-Commons trailer clips the Jellyfin screenshot fixture
# needs into ci/jellyfin/media/ (gitignored, not vendored - see ci/jellyfin/README.md). Plain
# downloads only, no local transcoding.
jellyfin-fixture-media:
    ./ci/jellyfin/fetch-media.sh

# Bring up the disposable Jellyfin screenshot fixture (pre-baked config + fetched media) on
# http://127.0.0.1:8096. Requires `just jellyfin-fixture-media` to have been run at least once.
jellyfin-fixture-up: jellyfin-fixture-media
    docker compose -f ci/jellyfin/docker-compose.yml up --detach --wait --wait-timeout 90

# Tear down the screenshot fixture and its volumes.
jellyfin-fixture-down:
    docker compose -f ci/jellyfin/docker-compose.yml down --volumes --remove-orphans

# --- Play Console uploads ---------------------------------------------------

# Upload the generated screenshots to the Play Console listing. Deliberately separate from
# capturing them: review the images (build artifact, or the PR screenshots.yaml opens with
# open_pr) before this ever runs - it never deletes existing Play Console images automatically.
screenshots-upload:
    #!/usr/bin/env bash
    set -euo pipefail
    image_dir="fastlane/metadata/android"
    shopt -s nullglob
    image_types=(phoneScreenshots sevenInchScreenshots tenInchScreenshots)
    found_images=0
    for image_type in "${image_types[@]}"
    do
      image_glob=("$image_dir"/en-US/images/"$image_type"/*)
      if [[ ${#image_glob[@]} -gt 0 ]]
      then
        found_images=1
      fi
    done
    if [[ "$found_images" -eq 0 ]]
    then
      printf 'No generated screenshots found under %s\n' "$image_dir" >&2
      exit 1
    fi
    if ! command -v gpc >/dev/null
    then
      printf 'gpc (playconsole-cli) is required for Play Console uploads\n' >&2
      exit 1
    fi
    if ! gpc apps list --output json | rg -q '"package_name":"{{play_package}}"'
    then
      printf 'Play Console package %s was not found via `gpc apps list`\n' "{{play_package}}" >&2
      exit 1
    fi
    for image_type in "${image_types[@]}"
    do
      image_glob=("$image_dir"/en-US/images/"$image_type"/*)
      for image in "${image_glob[@]}"
      do
        printf 'Uploading %s\n' "$image"
        gpc --package {{play_package}} images upload \
          --locale en-US \
          --type "$image_type" \
          --file "$image"
      done
    done

# Upload the already-committed icon (fastlane/metadata/android/en-US/images/icon.png) to the Play
# Console listing. Not locale-scoped, so kept separate from the screenshot upload above.
play-icon-upload:
    #!/usr/bin/env bash
    set -euo pipefail
    icon="fastlane/metadata/android/en-US/images/icon.png"
    if [[ ! -f "$icon" ]]
    then
      printf 'Icon not found: %s\n' "$icon" >&2
      exit 1
    fi
    if ! command -v gpc >/dev/null
    then
      printf 'gpc (playconsole-cli) is required for Play Console uploads\n' >&2
      exit 1
    fi
    if ! gpc apps list --output json | rg -q '"package_name":"{{play_package}}"'
    then
      printf 'Play Console package %s was not found via `gpc apps list`\n' "{{play_package}}" >&2
      exit 1
    fi
    gpc --package {{play_package}} images upload \
      --locale en-US \
      --type icon \
      --file "$icon"

# Upload the already-committed feature graphic
# (fastlane/metadata/android/en-US/images/featureGraphic.png, 1024x500) to the Play Console
# listing. Not locale-scoped, so kept separate from the screenshot upload above.
play-feature-graphic-upload:
    #!/usr/bin/env bash
    set -euo pipefail
    graphic="fastlane/metadata/android/en-US/images/featureGraphic.png"
    if [[ ! -f "$graphic" ]]
    then
      printf 'Feature graphic not found: %s\n' "$graphic" >&2
      exit 1
    fi
    if ! command -v gpc >/dev/null
    then
      printf 'gpc (playconsole-cli) is required for Play Console uploads\n' >&2
      exit 1
    fi
    if ! gpc apps list --output json | rg -q '"package_name":"{{play_package}}"'
    then
      printf 'Play Console package %s was not found via `gpc apps list`\n' "{{play_package}}" >&2
      exit 1
    fi
    gpc --package {{play_package}} images upload \
      --locale en-US \
      --type featureGraphic \
      --file "$graphic"
