# TODO

## FINDROID-7: Dependency currency (Renovate/Dependabot)

- [ ] Review upstream Findroid's dependency updates since this fork diverged and
      selectively pull in the ones that still make sense (don't blindly merge -
      this fork has diverged substantially from upstream in places)
- [x] Enable Renovate or Dependabot on this repo so dependency versions stay
      current going forward without manual tracking. First tried Dependabot
      (2026-07-18) since it needs no GitHub App install, but it produced zero
      PRs in 6 days despite `.github/dependabot.yml` being live and a scheduled
      Monday run passing — switched to Renovate instead (`renovate.json`
      restored to its pre-2026-07-18 content: `config:recommended` +
      `schedule:weekly` + `:semanticCommits`, kotlin/ksp grouped, `dependencies`
      label; validated with `renovate-config-validator`). Removed
      `.github/dependabot.yml` to avoid duplicate/conflicting automation.
      **Manual follow-up required**: install the Renovate GitHub App
      (https://github.com/apps/renovate) on `pschmitt/findroidplus` specifically
      — this is the one step that can't be done from a commit, and is exactly
      why the old inherited `renovate.json` was never actually active despite
      looking configured. Done (2026-07-24, set to "All repositories"). Even
      with the app installed, Renovate still produced nothing at first - this
      repo is a real GitHub fork (`fork: true`, parent
      `jarnedemeulemeester/findroid`), and Renovate disables itself on forks by
      default to avoid spamming them with irrelevant PRs. Added
      `"forkProcessing": "enabled"` to `renovate.json` to override that.

Status: in progress (2026-07-18) - automation enabled; the manual "review and
selectively pull in upstream dependency updates" item is still open and requires
human judgment.

## FINDROID-43: QR-code device provisioning

Scan-to-configure a new Findroid+ install from an already-configured instance,
instead of retyping server URL/credentials and Sonarr/Radarr/Seerr config by
hand on every new device.

Core export/import flow (biometric-gated encrypted QR export on phone, scan
+ apply + restart on import, versioned payload, editable Jellyfin/Sonarr/
Radarr/Seerr overrides, custom `findroidplus://` scheme + deep link, JVM unit
tests) is implemented and merged - see git log for FINDROID-43 commits.

- [ ] Not done: TV-side export (phone-only in v1, see FINDROID-43's own
      scope note above) and interactive on-device UX testing of the full
      flow (biometric prompt → generate → scan → apply) - verified so far
      via `just lint`/`just test`/a full `assembleLibreDebug` compile and a
      real CI-signed release install on both test devices (Mi Pad 4, Pixel
      5 "px5"), but nobody has actually tapped through the feature yet.

Status: mostly done (2026-07-27). Core export/import flow implemented,
compiles, lints, and unit tests pass; release APK built with the real CI
signing key and installed on both physical test devices. Still needs an
actual hands-on run-through of the QR scan/generate UX before calling this
fully done.

## FINDROID-44: Remote configuration of a running Findroid+ instance

Manage a Findroid+ instance running on another device (e.g. push a
Sonarr/Radarr auto-download rule, or a one-off episode download, to the Mi
Pad 4's instance) remotely, without touching that device directly.

- [x] Architecture chosen: no dedicated relay/server. Reuses Jellyfin's own
      per-user `DisplayPreferences` custom-data API (`customPrefs: Map
      <String,String>`) as the transport, scoped to `(displayPreferencesId
      ="findroidplus-remoteconfig", userId, client)` - every instance
      already talks to this same Jellyfin account continuously, so this
      needs zero new infrastructure and works for any fork user. Auth is
      implicit (same Jellyfin session that already gates everything else),
      not a separate mechanism.
- [x] Wire format: `RemoteConfigCommand` (`data/.../models/
      AutoDownloadRemoteCommand.kt`) is a `@Serializable sealed interface`
      with three variants sharing one JSON-serialized queue under
      `customPrefs["pending"]` - `ReconcileRules` (persists an ongoing
      auto-download rule, replaying `AutoDownloadRuleRepository
      .reconcileRules`'s own parameters verbatim rather than modeling
      add/remove separately), `EvaluateNow` (one-time "download whatever
      currently matches this scope, right now," no rule persisted - mirrors
      a local bulk download made without "also download new episodes"), and
      `DownloadItem` (a single already-known item + media source,
      immediate - the "this episode" case). A device heartbeat registry
      (`RemoteDeviceInfo`) lives alongside it under `customPrefs["devices"]`
      so a controller can list "which of my other devices are out there."
- [x] `RemoteConfigRepository` (interface in `data`) /
      `RemoteConfigRepositoryImpl` (impl in **`core`**, not `data` - applying
      `EvaluateNow`/`DownloadItem` needs `AutoDownloadRuleEvaluator`/
      `Downloader`, both `core`-only, and `data` has no dependency on `core`)
      implement enqueue (`pushRuleUpdate`/`pushDownloadWithScope`/
      `pushItemDownload`) and periodic apply (`syncNow`, via
      `RemoteConfigWorker`/`RemoteConfigScheduler`, 15-minute WorkManager
      floor, unconditional). The actual sync decision logic (which commands
      to apply/expire/dead-letter, which devices to prune) is a pure
      top-level function (`planRemoteConfigSync`, `data/.../repository/
      RemoteConfigSyncPlan.kt`) rather than a method on the impl, agnostic
      to which command subtype it's handling, so it's unit-testable without
      mocks - this `data` module has no mocking framework, and the existing
      convention here (`QueueStatusMatchingTest` et al.) is to extract pure
      branching logic into plain functions instead. `RemoteConfigSyncPlanTest`
      covers device-scoping, TTL expiry/dead-lettering, mixed command
      types, and the "device known-stale vs simply never seen yet"
      distinction (a real bug the tests caught - the first implementation
      dead-lettered commands for any device absent from the registry, not
      just ones confirmed stale via heartbeat TTL, which would have
      silently dropped rules pushed to a device before its very first
      sync).
- [x] Controller UX, two entry points, both sharing one `RemoteDevicePicker`
      component (`app/phone/.../film/components/RemoteDevicePicker.kt`,
      modeled 1:1 on `JellyfinServerUserPicker` from the QR export screen):
  - The dedicated auto-download rule editor
    (`AutoDownloadRulesScreen.kt`'s `EditRuleDialog`) - default "This
    device" applies to Room as before, picking another device calls
    `pushRuleUpdate` instead and shows a "Rule sent to X" toast
    (channel-based one-shot event, same pattern as `SearchEvent`/
    `DeleteItemEvent` elsewhere in the app).
  - The regular one-off Download popup (`DownloadScopeDialog.kt`, opened
    from `ItemButtonsBar`'s Download button on Show/Season/Episode
    screens) - the bulk/season scope branches to `pushDownloadWithScope`
    (mirroring each screen's local `downloadWithScope` exactly: an
    `EvaluateNow` command when seasons are picked, a `ReconcileRules`
    command too when "also download new episodes" is on - independent of
    each other, matching local semantics), and the Episode screen's
    "this episode" immediate case (new `DownloaderAction.PushDownload`,
    handled in `DownloaderViewModel` since that's what owns
    `Downloader.downloadItem` locally) branches to `pushItemDownload`.
- [x] Pull-to-refresh added to the auto-download rules screen
    (`PullToRefreshBox`, same Material3 indicator as Downloads/Library/
    Home) - drives an immediate `RemoteConfigRepository.syncNow()` instead
    of waiting out `RemoteConfigWorker`'s 15-minute WorkManager floor.
    Added after discovering (via `adb shell cmd jobscheduler run -f`
    testing) that force-running the WorkManager job doesn't actually
    execute `doWork()` if WorkManager's own scheduler considers it "before
    schedule" - it silently re-defers instead, which is why an early manual
    test looked like the push "didn't work." Pull-to-refresh sidesteps
    WorkManager's timing entirely by calling `syncNow()` directly.
- [x] Verified via remote `:app:phone:compileLibreDebugKotlin`, `ktfmtCheck`,
      and `:data:testDebugUnitTest`/`:core:testLibreDebugUnitTest` on
      rofl-13 - all green (2026-07-28, after fixing two real cross-module
      issues the first build caught: `core` was missing the
      kotlinx.serialization plugin/dependency, and `planRemoteConfigSync`/
      `RemoteConfigSyncPlan` were `internal` in `data`, invisible from the
      impl once it moved to `core`).
- [x] Partial on-device verification (2026-07-28, CI-signed release install
      on Mi Pad 4 and px5): confirmed live against the real Jellyfin
      server - app launches cleanly (no Hilt/DI crash from the new
      `RemoteConfigRepository`/`Downloader` injections), the Download
      dialog's device picker correctly lists a real other device ("Pixel
      5") fetched from the shared `DisplayPreferences` registry, proving
      the transport/heartbeat round-trip genuinely works end to end. Did
      **not** complete an actual push+download, since px5 was in active
      use at the time and confirming would have started a real multi-GB
      background download on it without more explicit go-ahead - stopped
      short of that deliberately rather than risk it.
- [x] Completed push→receive round trip (2026-07-28): pushed a single-episode
      download from the Mi Pad 4 to px5; px5's own natural (unforced)
      `RemoteConfigWorker` cycle picked it up a few minutes later (confirmed
      via logcat - a real "Starting work"/"Worker result SUCCESS", not the
      earlier forced-run deferral) and the episode landed as a completed
      file in its downloads folder, right size and timing for the pushed
      item. First forced-run attempts on both devices hit the same
      before-schedule WorkManager deferral pull-to-refresh was built to
      work around - the natural periodic cycle is what actually delivered
      it here.
- [x] Removal/management gap closed: previously, once a rule or download was
      pushed, the origin device had no visibility or control over it - it'd
      have to be undone by hand on the target. Fixed with:
  - Each device now also publishes a summary of its own currently-active
    auto-download rules (`RemoteActiveRuleSummary`) alongside its heartbeat,
    refreshed every `syncNow()` - the wire format
    (`data/.../models/AutoDownloadRemoteCommand.kt`) is now a
    `@Serializable sealed interface RemoteConfigCommand` (`ReconcileRules`/
    `EvaluateNow`/`DownloadItem`) carrying an `originDeviceId` (so a
    controller can find its own still-pending pushes) and a `displayName`
    resolved once at push time (avoids re-querying Jellyfin just to render
    a management list, and survives the item being renamed/deleted
    server-side afterwards).
  - New `RemoteConfigRepository` methods: `pushRemoveRule` (just
    `pushRuleUpdate` with an empty scope - `reconcileRules` already treats
    that as "clear everything for this series," so no new apply-side logic
    was needed), `listPendingCommandsFromThisDevice`/`cancelPendingCommand`
    (retract a push before its target has even applied it).
  - New dedicated screen: **Settings → Downloads → Remote devices**
    (`app/phone/.../presentation/settings/remotedevices/`) - lists other
    devices with their active rules (each removable, with a confirm
    dialog) and this device's own still-pending pushes (each cancelable,
    no confirm needed since it's non-destructive). Spot-verified on Mi Pad
    4: correctly lists "Pixel 5," relative "last seen" time, and an
    accurate empty "No active rules" state; pull-to-refresh works.
- [x] Per-device opt-out: a "Allow remote management" toggle at the top of
      the Remote devices screen (`AppPreferences.remoteManagementEnabled`,
      default on). Turning it off calls
      `RemoteConfigRepository.setRemoteManagementEnabled(false)`, which
      removes this device's own entry from the shared registry immediately
      (not just once its heartbeat goes stale) and drops any commands still
      queued *for* it - doesn't touch commands this device has queued *for
      others*, since opting out is about not being managed, not about
      withdrawing pushes already sent elsewhere. `syncNow()` no-ops
      entirely while disabled.
- [x] Show posters on the Remote devices screen: each active-rule row now
      resolves and renders the real `FindroidShow` poster
      (`RemoteDevicesViewModel.resolveShowPosters`, concurrent per-show
      `jellyfinRepository.getShow` calls, best-effort) - a
      `RemoteActiveRuleSummary` only carries an id + name on the wire, not
      enough to render a poster, so the viewing device resolves it itself
      via the same shared Jellyfin session.
- [ ] Not done: TV-side support - this only touches the phone module.

Status: implementation done (2026-07-28), including cross-device rule/
download management (remove/cancel), a dedicated Remote devices screen with
poster art, and a per-device opt-out. Passing remote build/lint/unit tests
and spot-verified live against the real server on both physical test
devices, including one full unforced push→receive round trip. No TV-side
counterpart yet.

## FINDROID-45: `findroid-cli` - Termux command-line download management

A shell script (bash + curl/jq) for Termux, with three distinct command
groups:

- **Remote** (`devices`, `rule push`/`remove`, `download push`, `pending
  list`/`cancel`): reuses the exact same Jellyfin `DisplayPreferences`
  shared-bucket transport FINDROID-44 built - the CLI participates in the
  same cross-device mesh as any real Findroid+ install, just without a Room
  DB/Downloader of its own to apply commands sent *to* it.
- **Local download** (`download get`/`list`/`rm`): a lightweight
  download-it-yourself path - resolves an item via the Jellyfin API,
  `curl`s the file straight to local storage. Does not touch the real
  Android app's own downloads at all.
- **Local control** (`local token set`, `local settings get`/`set`, `local
  download trigger`, `local debug`): actually configures the *real running
  Findroid+ app* on the same device - added after the user corrected the
  original scope ("my goal is to configure findroidplus itself, and not
  replace it"), since the two groups above only ever act as an independent
  peer, never touching the app's real settings/Downloader/credentials.

Local control transport went through two designs, the first of which
turned out to be fundamentally broken on real devices:

1. **First attempt**: `android.net.LocalServerSocket` (a Linux
   abstract-namespace unix socket), with `LocalSocket.peerCredentials`
   giving a kernel-verified caller uid and a pairing handshake (notification
   Approve/Deny, then a token) for auth. On-device testing (2026-07-28)
   found this doesn't work at all: connecting from a different app
   (Termux) gets `EACCES` under SELinux enforcing (the normal state on
   every real device) and only succeeds under permissive mode - confirmed
   by directly toggling `setenforce` on a rooted test device. SELinux's
   default policy keeps arbitrary `untrusted_app` domains isolated from
   each other for raw local-socket IPC; no app-level fix can work around
   that boundary.
2. **Current design**: a `ContentProvider`
   (`core/.../localcontrol/LocalControlProvider.kt`, authority
   `${applicationId}.localcontrol`), whose `call()` method is Binder-backed
   - the IPC mechanism Android's own SELinux policy is written to permit
   between apps - and reachable from a plain shell via the OS's own
   `content call` command (no compiled helper needed in Termux). Auth is a
   single bearer token (`LocalControlAuth.getOrCreateToken()`/
   `regenerateToken()`), shown in Settings > Local CLI access and
   regeneratable at will, rather than a per-client pairing handshake -
   simpler, and a `call()` invoked via a shell command doesn't carry
   meaningful "this is Termux" caller identity the way an app-to-app Binder
   call would, so per-client tracking wasn't buying anything real. Request/
   response bodies travel as base64-encoded JSON extras (`token`/`method`/
   `path`/`body` in, `status`/`body` out) since raw JSON can't safely
   round-trip through `Bundle`'s `toString()` output or shell-argument
   passing. Off by default (`AppPreferences.localControlEnabled`).

Endpoints (`LocalControlRouter`, unchanged across both transport designs):
`GET`/`PATCH /settings/downloads` (via `DownloadSettingsBridge`, the 10 real
download `AppPreferences`), `POST /downloads/trigger` (resolves the item,
calls the app's own `Downloader.downloadItem` exactly as
`RemoteConfigRepositoryImpl` does for a remote push), `POST /debug/proxy`
(forwards to Jellyfin/Sonarr/Radarr/Seerr using the app's already-stored
credentials, reusing `PvrHttpClient`/`PvrConfiguration`).

- [x] Remote + local-download groups implemented, shellcheck-clean,
      `bash -n` syntax-checked, JSON wire shape hand-verified against
      `AutoDownloadRemoteCommand.kt`'s kotlinx.serialization output.
- [x] Local control implemented **and verified end-to-end on real
      hardware** (Mi Pad 4, 2026-07-28): enabled the toggle, read the real
      token off the Settings screen (via `uiautomator dump` - the token's
      own base64 charset made a couple of characters genuinely ambiguous
      to read from a screenshot, e.g. `O` vs `0`), ran `findroid-cli local
      settings get` and `local settings set maxParallelDownloads=N` from
      actual Termux (not adb shell) and got real data back both ways,
      reverted the test value afterward.
  - [x] Found and fixed a second real-device-only blocker beyond the
        SELinux one below: `content call`'s external-access path needs
        `android.permission.ACCESS_CONTENT_PROVIDERS_EXTERNALLY`, a
        signature-level permission `pm grant` refuses to hand out even as
        root ("not a changeable permission type") - it's implicitly held
        by the special `shell` uid (why plain `adb shell content call`
        testing worked earlier) but never by a regular app's own uid
        (Termux's, when run as itself). `su -c 'content call ...'` runs it
        as root the same way `adb shell` does, which does work - so
        **a rooted device is required for `local` commands specifically**
        (findroid-cli now routes through `su -c` automatically; everything
        else in the script is unaffected).
- [ ] Not done: real end-to-end test against an actual Jellyfin server for
      the remote/local-download groups (no live server credentials were
      available in this environment).

Status: remote/local-download groups implemented and smoke-tested
(2026-07-28) but never run against a live server. Local control API
redesigned (2026-07-28) from a socket+pairing scheme (found to be blocked
by SELinux on real devices) to a `ContentProvider`+single-token scheme, per
the user's own suggestion ("something more android-native... AIDL? Binder
IPC?") - implemented and verified end-to-end on real hardware the same day,
including finding and fixing the root-requirement blocker above.

## FINDROID-46: Onboarding screen redesign

- [x] Redesign the onboarding screen layout
  - [x] Make the primary button(s) vertical and bigger
  - [x] Move the "Learn more about Jellyfin" button to the top-left corner

Status: done (`f2933311`, 2026-07-28) - `WelcomeScreen.kt`: "Continue" is now
a taller full-width button with larger type, "Learn more" moved to a
corner-pinned text link out of the main action stack.

## FINDROID-47: Automatic backups don't actually run

- [x] Investigate why scheduled auto-backups never fire - root cause found:
      enabling the toggle before picking a destination folder silently
      cancelled/never enqueued the periodic work, with no error surfaced
      (`autoBackupLastError` was only ever written from inside the worker's
      own failure paths, which never got a chance to run).
  - [x] Backup filenames should include the device name.
  - [x] Rename "findroid" to "findroidplus" in backup filenames.

Status: done (`05978a68`, 2026-07-28) - `AutoBackupScheduler` now records a
specific error when bailing out enabled-but-no-folder (surfaced via the
existing Backup & Restore error banner) and clears it once a folder is
picked; filename format extracted into shared `BackupFileNaming` (device
model + `findroidplus` prefix) used by both the scheduled and manual backup
paths. Verified via remote compile/ktfmtCheck/unit tests on rofl-13.

## FINDROID-48: Re-group the main Settings screen

- [x] The main Settings screen currently greets the user with a long, flat
      wall of top-level categories - re-organize into fewer, more sensibly
      grouped sections rather than a 1:1 header per existing group (an
      earlier pass just added section labels to the existing groups
      as-is; this is the follow-up restructuring). Concrete examples from
      the user (2026-07-28):
  - [x] "Cache" settings probably belong under "Network".
  - [x] "Language" might be better homed under "Player".
  - [x] "Offline mode" can probably go under "Downloads".
  - [x] General principle: fewer top-level entries, each one a coherent
        theme, not a 1:1 mapping of every existing category to its own row.

Status: done (`33ba5d1d`, 2026-07-28) - Settings root now shows 7 coherent
groups instead of 10; every individual preference row preserved, only its
top-level home changed. Verified app:phone/app:tv compile + ktfmtCheck on
rofl-13.

## FINDROID-49: Simplify findroid-cli to local-only, drop root requirement, fix gaps

Reported (2026-07-28) after FINDROID-45's local control feature shipped:
- The CLI should ONLY talk to the local app - drop the "remote" command
  group (`devices`/`rule push`/`rule remove`/`pending list`/`pending
  cancel`, the cross-device Jellyfin `DisplayPreferences`-mesh peer
  behavior from FINDROID-44) and the plain "local download" group
  (`download get`/`list`/`rm`, direct-curl-to-storage, bypassing the real
  app entirely) altogether. `local` stops being a prefixed subcommand
  group and becomes the CLI's only mode - e.g. `findroid-cli settings get`
  instead of `findroid-cli local settings get`.
- Why does it require root? Root is a genuine, real limitation of the
  `ContentProvider`+`content call` transport specifically (see FINDROID-45:
  `content call`'s external-access path needs
  `ACCESS_CONTENT_PROVIDERS_EXTERNALLY`, a signature-level permission only
  the `shell`/root uid holds - confirmed `pm grant` refuses it even as
  root). The real fix is switching transport again, to a **loopback TCP
  socket** (127.0.0.1) instead of a ContentProvider: unlike the
  abstract-namespace Unix socket tried before THAT (blocked by SELinux
  domain separation), plain TCP loopback between apps is ordinary,
  unrestricted socket I/O gated only by the `INTERNET` permission the app
  already has - no SELinux wall, no signature permission, no root, and
  plain `curl`/`bash`'s own `/dev/tcp` works directly from Termux. The
  existing token-based auth (`LocalControlAuth`) and endpoint dispatch
  (`LocalControlRouter`) don't care about transport and can be reused
  unchanged - only the "how a request arrives" layer changes again.
- "Why can't I list downloads?" - there simply isn't a
  `GET /downloads` (list) endpoint yet, only
  `GET`/`PATCH /settings/downloads` (settings), `POST /downloads/trigger`
  (start one), and `POST /debug/proxy`. Needs a real "list current/
  in-progress downloads" endpoint (reuse
  `JellyfinRepository.getDownloads()` or equivalent) and a matching CLI
  command.
- "pls make sure *all* the cli commands work!" - every remaining command
  (after the simplification above) needs an actual on-device pass, the
  same way `settings get`/`settings set` were verified for FINDROID-45,
  not just a code read-through.

- [x] Switch local control transport from `ContentProvider` to a loopback
      TCP server (reuse `LocalControlAuth`/`LocalControlRouter` as-is).
      Implemented as `LocalControlServer` (NanoHTTPD, 127.0.0.1:48411,
      Bearer-token auth, honest enable-toggle that reports a real bind
      failure instead of silently claiming success).
- [x] Add a `GET /downloads` (list) endpoint + CLI `download list` command.
- [x] Strip `cli/findroid-cli` down to local-only: remove the remote
      command group and the local-download command group entirely, drop
      the `local` prefix so its subcommands are top-level.
- [x] Verify every remaining command end-to-end on a real device, no root
      required. Done on Mi Pad 4 via real Termux (not just `adb shell`):
      `token set`, `settings get`, `settings set`, `download list` (new),
      `download trigger` (error path - underlying logic unchanged from
      FINDROID-45's already-verified pass), `debug jellyfin`. Caught and
      fixed a real bug along the way: NanoHTTPD's `parseBody()` only
      special-cases `POST`/`PUT`, so `PATCH /settings/downloads` silently
      dropped its body - fixed by reading the raw body directly via
      `Content-Length` instead. px5 (Pixel 5) still needs this pass -
      its wireless-debugging connection was down (device locked/asleep)
      at verification time and wasn't force-reconnected, per the standing
      rule against bypassing a lock screen.

Status: **done** (2026-07-28) on Mi Pad 4; px5 re-enabled after this entry
was written and got the release build + full CLI pass separately (see git
log). CLI also gained `--json` and a pretty-TSV-by-default table renderer
for every data command in a same-day follow-up.

## FINDROID-50: browse Jellyfin/Sonarr/Radarr/Seerr + trigger downloads by name

Requested (2026-07-28): grow `findroid-cli` toward covering most of what the
app itself can do/configure - codified as a standing rule in `AGENTS.md`'s
new "CLI parity" section (new app functionality with a CLI-shaped equivalent
gets a matching local-control endpoint + CLI subcommand in the same change).
First concrete step, per the user: a way to browse the Jellyfin/Sonarr/
Radarr/Seerr libraries, plus triggering a download by name/season instead of
needing an item UUID up front (`findroid-cli download "Rick and Morty"
"Season 3"`).

- [x] `LocalControlRouter`: `GET /jellyfin/libraries`, `GET /jellyfin/items`
      (parentId/search/pagination), `GET /jellyfin/search` - all via the
      already-typed `JellyfinRepository` methods, no raw HTTP needed.
- [x] `LocalControlRouter`: `GET /sonarr/series`, `GET /radarr/movies`,
      `GET /seerr/requests`, `GET /seerr/discover/{path}`,
      `GET /seerr/search` - via the already-typed `SonarrApi`/`RadarrApi`/
      `SeerrApi` clients (same ad hoc construction pattern
      `resolveProxyClient` already uses for the debug proxy).
- [x] `POST /downloads/trigger-by-name`: resolve a movie/show by name
      (exact case-insensitive match, else single-candidate, else an
      ambiguous-match error listing candidates), then for a show resolve
      season/episode by number or name and trigger every matching episode's
      download. Deliberate guard rail: a bare show name with no season and
      no explicit `all` flag is rejected rather than silently downloading
      an entire series.
- [x] CLI: `library list`/`library browse`, `search`, `sonarr list`,
      `radarr list`, `seerr requests`/`discover`/`search`, and reworking
      `download`'s dispatch so anything past `list`/`trigger` is treated as
      a by-name trigger. Also added `--json`/pretty-TSV-table output to
      every data command (a same-day follow-up ask, see FINDROID-49).
- [x] On-device verification on Mi Pad 4 (real Termux, no root): every new
      command confirmed against the real library/Sonarr/Radarr/Seerr data,
      including the by-name guard rail (bare show name rejected), the
      exact-match-priority resolution ("Star Trek" doesn't get flagged
      ambiguous despite several "Star Trek: ..." shows existing), a real
      ambiguous-match error (two shows both literally named "Extras"), and
      a real season+episode resolution + trigger attempt.

Status: **done** (2026-07-28) on Mi Pad 4; deployed to px5 too. Both devices
got the release build from the same batch as this entry.

**Follow-up (2026-07-28, same day)** after real usage turned up gaps:
- [x] `search`/`library browse` were unintentionally movie/show-only (via
      `getSearchItems`) - individual episodes never matched. Repointed
      `search` at the unrestricted `/jellyfin/items` endpoint (already used
      by `library browse`) and added a `--type TYPE[,TYPE...]` filter to
      both, so e.g. `search --type episode "Salute Your Morts"` finds an
      episode whose title isn't also a show/movie name. Removed the now-
      redundant `/jellyfin/search` endpoint (`getSearchItems` was a strict
      subset of what `/jellyfin/items` already does).
- [x] `download NAME` couldn't resolve a bare episode title at all (its
      candidate search was movie/show-only) - it now also matches episodes
      directly, e.g. `download "Salute Your Morts"`. Ambiguous-match errors
      for episodes/seasons now include the series name (`"Pilot" (Severance
      S1E1)` vs. just `"Pilot"`) since a bare title alone doesn't
      disambiguate across shows.
- [x] `download ITEM_ID` (an id copied from `library browse`/`search`
      output, no `trigger` keyword) now auto-detects a UUID-shaped first
      argument and forwards to the id-based trigger, instead of searching
      for the literal UUID string as a title and failing.
- [x] `download -- NAME` added: forces by-name interpretation even when
      NAME collides with a reserved subcommand word (`list`/`trigger`/
      `cancel`/`remove`).
- [x] `download list` only ever showed movies - `FindroidShow.sources` is
      always empty (a show has no media source, only its episodes do), so
      every TV download was silently invisible. Now expands each downloaded
      show's seasons/episodes (offline DB reads, same pattern
      `DownloadsViewModel.refreshDownloads()` already uses) into the flat
      per-source list.
- [x] Added real in-progress-download visibility: each source's `status`
      ("downloading"/"completed") plus, while downloading, a live progress
      snapshot (percent/bytes/speed/eta) via the existing
      `Downloader.getProgressFlow()`. `download list --active`/`--completed`
      filters either way.
- [x] Added `download cancel DOWNLOAD_ID` (`Downloader.cancelDownload`) and
      `download remove ITEM_ID...` (`Downloader.deleteItems`) - both already
      existed on `Downloader` for the app's own Downloads screen, just
      weren't exposed to the local-control API yet. `cancel` verified live
      on Mi Pad 4 (triggered a real not-yet-downloaded episode, confirmed it
      in `download list --active` with real percent/size, cancelled it,
      confirmed it vanished from both `--active` and the completed list -
      no orphaned DB rows). `remove`'s success path is unverified live -
      the test device's Jellyfin server (`tv.brkn.lol`) became DNS-
      unreachable mid-session (unrelated to this change; confirmed via a
      plain `debug jellyfin` connectivity check), and its error paths
      (missing args, both single/multi ITEM_ID forms) were checked instead.
      Reuses `Downloader.deleteItems` verbatim (same method the app's own
      Downloads screen delete action already calls), so this is a real but
      low-severity gap - re-verify the success path once that device's
      Jellyfin connectivity is back.
- [x] Fixed a real correctness bug found while touching this: every
      `downloadId` in a JSON response was a raw 64-bit `Long` number -
      `jq`/JS-style JSON parsers only preserve ~53 bits of integer
      precision, so an extreme id could silently corrupt on the wire and
      break a later cancel/list-by-id call. Encoded as a string everywhere
      instead (`triggerDownload`, `triggerDownloadByName`, `download list`) -
      confirmed on-device with a real negative-valued 64-bit id (`downloadId`
      is `UUID.randomUUID().mostSignificantBits`, which is often negative
      as a signed `Long`) round-tripping through `download list`/`cancel`
      exactly, with no precision loss.
- [x] Follow-up fix: `check_response`'s blanket error text didn't reach
      into `download NAME`'s per-episode `triggered[].error` field, so a
      partial-batch failure (e.g. an episode with no media source) printed
      an unhelpful "409 unknown error". Now renders every row's own
      result/error whenever the response carries a `triggered` array,
      regardless of overall HTTP status. Confirmed on-device (a real
      episode with no media source now shows "No media source" in the
      table instead of "unknown error").

## FINDROID-51: Serve findroid-cli itself from the "Local CLI access" page

Requested (2026-07-28): let a user grab `findroid-cli` directly from the
device it's meant to control, the way Shizuku's `rish` shell client is
downloadable/installable straight from the Shizuku app - instead of the
current requirement to separately clone/copy the script from the
`findroidplus` repo onto the device before it's usable.

- [x] Design how the script gets served: a new unauthenticated `GET /cli` on
      `LocalControlServer`, routed before the bearer-token check (the one
      deliberate exception - it's a public script, not user data), returning
      the bundled `findroid-cli` asset verbatim with `Content-Type: text/plain;
      charset=utf-8` and `Content-Disposition: attachment;
      filename="findroid-cli"`.
- [x] "Local CLI access" settings screen: added a "Get findroid-cli" section
      (mirroring the existing token copy-to-clipboard UX) showing `curl
      http://127.0.0.1:48411/cli -o findroid-cli && chmod +x findroid-cli`
      with its own Copy button. QR code / share-intent considered out of
      scope for v1 - a straight curl one-liner already covers the Termux
      workflow the ticket asked for.
- [x] Keep the bundled script in sync with `cli/findroid-cli` at build time:
      `core/build.gradle.kts` registers a `CopyFindroidCliAsset` task and
      wires it in as a generated asset directory via AGP's variant API
      (`variant.sources.assets.addGeneratedSourceDirectory`) rather than
      writing straight into `src/main/assets` - the naive
      dependsOn-on-merge-tasks approach passed compile/ktfmtCheck/unit tests
      but failed a full `assembleLibreRelease` with "Property has implicit
      dependency" (lint-vital's model-generation task also reads
      `src/main/assets` without an explicit dependency edge, so its ordering
      vs. the copy was undefined). The variant-API registration fixes that by
      letting AGP wire every consumer (merge, lint-vital, packaging) itself.

Verified: `just gradle rofl-13.brkn.lol ":app:phone:compileLibreDebugKotlin"
":core:compileLibreDebugKotlin" "ktfmtCheck" ":core:testLibreDebugUnitTest"
":data:testDebugUnitTest"` -> BUILD SUCCESSFUL. A full CI-signed
`just build-fetch --release --phone` (forced with `--rerun-tasks`) also
succeeded end to end (`BUILD SUCCESSFUL in 3m 14s, 330 actionable tasks`);
extracting `assets/findroid-cli` from the resulting APK and diffing it
against `cli/findroid-cli` confirmed byte-for-byte (26021 bytes) parity.
Installed on the Mi Pad 4 (`dev.pschmitt.findroidplus`) and confirmed live:
`curl http://127.0.0.1:48411/cli` from Termux returns `200` with the correct
headers and exact script body, with no `Authorization` header sent - and a
sanity check that `GET /downloads` (an authenticated route) still returns
`401` without a token, confirming the `/cli` exception didn't leak auth
bypass onto any other route.

Status: done (2026-07-28) - implemented, remote-build-verified, and confirmed
end-to-end on real hardware (Mi Pad 4).

## FINDROID-52: findroid-cli command aliases

Requested (2026-07-28): add short aliases for the more common/verbose
`findroid-cli` subcommands so frequent usage doesn't require typing the full
word every time - e.g. `dl` for `download`, `rm`/`del` alongside `remove` for
`download remove`. Survey the current command list (`cli/findroid-cli`:
`token`, `settings`, `library`, `search`, `sonarr`, `radarr`, `seerr`,
`download` [`list`/`trigger`/`cancel`/`remove`/by-name], `debug`) for other
good alias candidates (e.g. `ls` for `list`, `lib` for `library`) while
implementing this, not just the two examples given.

- [x] Design and implement alias dispatch in `cli/findroid-cli` (top-level
      command aliases and, where it applies, subcommand aliases like
      `download rm`/`download del` alongside the existing `download remove`).
      Keep the canonical long-form names as the ones shown in `--help`/usage
      text; aliases are just shortcuts, not replacements.
- [x] Update the script's usage/help text to mention the aliases.
- [x] shellcheck-clean, `bash -n` syntax-checked, and re-verify the aliased
      commands behave identically to their canonical forms (ideally on a real
      device the way prior findroid-cli work was verified, per FINDROID-45/49/50).

Status: done (2026-07-28) - **static-verified only** (`shellcheck`/`bash -n`
clean, plus a stubbed-dispatch trace confirming every alias reaches the exact
same `cmd_*` function with the exact same args as its canonical form), not
re-verified on a real device like FINDROID-45/49/50 were. Aliases added:
top-level `lib` (library), `dl` (download), `cfg` (settings); subcommand
`ls` (list, everywhere it appears: `library`/`sonarr`/`radarr`/`download`),
`br` (library browse), `trig` (download trigger), `c` (download cancel),
`rm`/`del` (download remove), `req` (seerr requests), `disc` (seerr
discover). Deliberately skipped: `token`/`debug` (already terse),
`search`/`sonarr`/`radarr`/`seerr` top-level (already short, and a
single-letter alias would collide across `settings`/`search`/`sonarr`/
`seerr` all starting with `s`), `seerr search`/`settings get`/`settings set`
(already short). The by-name reserved-word list (needing `download --
NAME`) now also covers the new aliases (`ls`/`trig`/`c`/`rm`/`del`), noted
in the usage text.

## FINDROID-53: findroid-cli version subcommand + auto-download rule management

Requested (2026-07-28): two additions to `findroid-cli`/the local control API -
a `version` subcommand reporting both the CLI's own version and the running
app's build info, and a new `autodownload` command group to manage this
device's own local auto-download rules (add/list/remove) without opening the
app UI. Distinct from FINDROID-44's cross-device rule-push mechanism
(`RemoteConfigCommand`/`pushRuleUpdate`) - this is purely local rule
management, evaluated by this device's own WorkManager.

- [x] Added `CLI_VERSION="1.0.0"` to `cli/findroid-cli` (first time the script
      tracks its own version), with a comment to bump it on meaningful future
      changes.
- [x] New authenticated `GET /info` on `LocalControlRouter` returning
      `{"versionName", "versionCode", "gitRevision"}`. Since `core` can't
      reference `app/phone`'s own generated `BuildConfig` directly (and
      `app/tv` has a separate one it doesn't use for local control), added a
      small `AppVersionInfo` interface in `core` and bound it from
      `app/phone`'s `AppModule` (`@Provides` reading
      `dev.jdtech.jellyfin.BuildConfig.VERSION_NAME/VERSION_CODE/GIT_REVISION`),
      injected into `LocalControlRouter` alongside its other dependencies.
- [x] `findroid-cli version`: always prints the CLI's own version (works even
      with no token configured/app unreachable); additionally calls `GET
      /info` and prints the app's versionName/versionCode/gitRevision via the
      same `--json`/table conventions as `settings get` when reachable - a
      failed app request is non-fatal (still exits 0).
- [x] New auto-download rule management endpoints on `LocalControlRouter`,
      scoped to the current server+user (`AppPreferences.currentServer` /
      `JellyfinRepository.getUserId()`), backed by the existing
      `AutoDownloadRuleRepository` (`reconcileRules`/`deleteRule`/
      `deleteRulesForShow`/`getRules`/`getRulesForSeries`) - no new
      persistence, no touching FINDROID-44's push path:
      - `GET /autodownload/rules` - every rule for this device, with each
        `seriesId`/`seasonId` resolved to a show name/season number so the
        CLI doesn't need a second lookup.
      - `POST /autodownload/rules` - resolves a show by `seriesId` or `query`
        (case-insensitive exact match / sole search result / ambiguous-match
        error, scoped to `BaseItemKind.SERIES` only - reusing
        `triggerDownloadByName`'s resolution template), a season scope
        (`season`/`seasons` by number or name via the existing
        `matchByNumberOrName` helper, `"all": true` for every existing
        season, or neither for a future-seasons-only rule), then calls
        `reconcileRules(...)`.
      - `POST /autodownload/rules/remove` - by `id` (a single rule row) or by
        show (`seriesId`/`query`, clearing every rule for that series at
        once via `deleteRulesForShow` - mirrors
        `AutoDownloadRulesScreen.kt`'s own delete action).
      - Every mutation calls `RemoteConfigRepository.syncNow()` afterwards
        (mirroring `AutoDownloadRulesViewModel.republishActiveRulesSummary()`
        from the same-day `6335e38c` fix), non-fatal on failure, so the
        change is republished to the shared device registry immediately
        instead of waiting on the next periodic WorkManager sync.
- [x] `findroid-cli autodownload` command group (alias: `auto`):
      - `list`/`ls` - table of `ID/SHOW/SCOPE/ENABLED/ONLY_NEW/ONLY_UNWATCHED`.
      - `add`/`a` `NAME_OR_ID [--season S[,S...]] [--all-seasons]
        [--future-seasons] [--only-new] [--only-unwatched]` - resolves
        `NAME_OR_ID` exactly like `download NAME` (a UUID-shaped argument
        routes straight to `seriesId`).
      - `remove`/`rm`/`del` `NAME_OR_ID` - clears every rule for a show.
      - `remove-id RULE_ID` - clears a single rule row.
      - Updated `usage()` with the new `version` and `autodownload` entries
        and their aliases.

Verified remotely: `just gradle rofl-13.brkn.lol
":core:compileLibreDebugKotlin" ":app:phone:compileLibreDebugKotlin"
"ktfmtCheck" ":core:testLibreDebugUnitTest" ":data:testDebugUnitTest"` ->
BUILD SUCCESSFUL in 1m 25s, 117 actionable tasks executed, no warnings in the
touched files. `shellcheck cli/findroid-cli` and `bash -n cli/findroid-cli`
both clean. Locally stubbed `local_request` to trace `cmd_version`,
`cmd_autodownload_add` (season list, UUID+all-seasons, future-only-by-default,
season+future-seasons combined), `cmd_autodownload_remove`(-`_id`), and
`cmd_autodownload_list`'s table rendering - every request body/response
rendering matched the router's expected shape, and every alias/dispatch path
reached the correct function.

A full CI-signed `just deploy --release --phone` build installed successfully
on the Mi Pad 4 (`Performing Streamed Install` -> `Success`). On-device,
enabled/located the already-configured "Local CLI access" token via
`uiautomator`-driven navigation (Settings > Downloads > Local CLI access),
then ran the *real* served `GET /cli` script through the device's actual
Termux installation (its own `bash`/`curl`/`jq`, invoked via root since the
device is Magisk-rooted, rather than a host-side simulation):
- `findroid-cli version` -> printed `findroid-cli: 1.0.0` plus the real
  running app's `versionName 2.11.0` / `versionCode 47` / `gitRevision
  v2.11.0-2-g6335e38c51c0-dirty`.
- `findroid-cli autodownload list` (alias `auto ls`) correctly listed this
  device's 4 pre-existing real rules (Rick and Morty S9 + future seasons,
  House of the Dragon S3 + future seasons) with show names/season numbers
  resolved.
- `findroid-cli autodownload add "Mushoku Tensei: Jobless Reincarnation"
  --season 1 --only-new --only-unwatched` (resolved by name via a real
  Jellyfin search) created exactly the requested season-1 rule; a second add
  with no season flags correctly fell back to a future-seasons-only rule,
  and confirmed the *existing* `AutoDownloadRuleRepository.reconcileRules`
  invariant holds through this new path too - the future-seasons row came
  back `onlyNewEpisodes: true` even though `--only-new` wasn't passed for
  that call, since a future rule is always only-new by definition.
- `logcat` confirmed each add/remove triggered a real `GET`+`POST
  .../DisplayPreferences/findroidplus-remoteconfig` round-trip against the
  live Jellyfin server (`tv.brkn.lol`) immediately after the mutation -
  `syncNow()` republishing verified end-to-end, not just called.
- `findroid-cli autodownload remove "Mushoku Tensei: ..."` and, separately,
  `autodownload remove-id RULE_ID` (targeting just the newly-added rule's own
  numeric id) both worked, leaving the device's original 4 rules untouched
  throughout.
- Also installed the same signed release build on a second connected device
  (`R6AIB700W850L7G`, ASUS_AI2302) - install succeeded, but "Local CLI
  access" had never been enabled on that device before and enabling it was
  out of scope for this pass, so no CLI verification was done there; not a
  blocker per the ticket's own guidance.

Status: done (2026-07-28) - implemented, remote-build-verified, and confirmed
end-to-end on real hardware (Mi Pad 4) using the actual served CLI script run
through the device's own Termux binaries, including a real Jellyfin-server
round-trip for the immediate rule-sync republish. Second device
(ASUS_AI2302) received the same release build but wasn't otherwise exercised.

## FINDROID-54: Merge auto-download rules and remote devices screens

Requested (2026-07-28): "lets merge the auto-download rules and remote
devices views. They are more or less the same in the end." Both screens
were fundamentally "a show + season scope + toggle/remove", just scoped to
different devices - `AutoDownloadRulesScreen` for this device's own rules,
`RemoteDevicesScreen` for other devices' rules and this device's pending
pushes.

- [x] Merged into one screen, one Settings entry. Both existing
      `@HiltViewModel`s (`AutoDownloadRulesViewModel`, `RemoteDevicesViewModel`)
      kept as-is and instantiated side by side via `hiltViewModel()` in the
      merged `AutoDownloadRulesScreen` composable - no ViewModel merge, no
      new cross-module DI.
- [x] One `Scaffold`/`TopAppBar`/`PullToRefreshBox`/`LazyColumn`, top to
      bottom: the "Allow remote management" toggle, a "This device" header
      + this device's own show rule rows (edit/delete dialogs unchanged),
      an other-devices' "Remote devices" header + their active-rule sections
      (only shown when at least one other device exists - no jarring "no
      devices" message next to this device's own rules), then a "Pending"
      section for this device's own not-yet-applied pushes if any. A single
      generic empty state only shows up when there's truly nothing anywhere
      (no local rules, no other devices, no pending pushes).
- [x] Pull-to-refresh drives both ViewModels' `refresh()` - each already
      just launches its own `viewModelScope` coroutine and returns
      immediately, so calling both back to back already runs their
      `syncNow()`+reload concurrently; `isRefreshing` is the OR of both.
      Both event/toast paths wired: `AutoDownloadRuleEvent.RuleSentToDevice`
      via `ObserveAsEvents`, and remote-devices' `RemoveActiveRule`/
      `CancelPendingCommand` toasts inline in the merged `onAction`.
- [x] Navigation: collapsed `RemoteDevicesRoute` into `AutoDownloadRulesRoute`
      in `NavigationRoot.kt`. Removed `SettingsEvent.NavigateToRemoteDevices`
      end to end (`SettingsViewModel`'s now-deleted `remote_devices_title`
      `PreferenceCategory` → `SettingsEvent.kt` → phone/TV `SettingsScreen.kt`/
      `SettingsSubScreen.kt` `when` branches → `NavigationRoot.kt`'s
      `navigateToRemoteDevices` callback), leaving one `auto_download_rules`
      `PreferenceCategory` whose summary now reads "...on this device and
      others". `RemoteDevicesScreen.kt` gutted down to just the reusable,
      now-non-private `RemoteManagementToggleRow`/`DeviceSection`/
      `PendingCommandRow` composables the merged screen imports
      (`RemoteDevicesViewModel`/`RemoteDevicesState`/`RemoteDevicesAction`
      untouched). Deleted now-dead strings (`remote_devices_summary`,
      `remote_devices_empty` in core; `remote_devices_title`/
      `remote_devices_summary` in the settings module, which had their own
      duplicate copies) after grepping every reference first; kept
      `remote_devices_title` (core) since it's reused as the merged screen's
      "Remote devices" section header.

- [x] On-device verification on Mi Pad 4: `just deploy --release --phone`
      (CI-signed), then navigated Settings → Downloads → "Auto-download
      rules" (one entry now, confirmed the old "Remote devices" entry is
      gone). Merged screen renders exactly as designed: "Allow remote
      management" toggle, "This device" header with both real local rules
      (House of the Dragon, Rick and Morty - edit dialog opens with correct
      state incl. the "Push to" device picker, delete-confirmation dialog
      opens with correct show name, both canceled cleanly without touching
      real data), a "Remote devices" header showing "Pixel 5"'s real active
      rule, and no pending-commands section (correctly hidden when empty).
      Confirmed end-to-end against real other-device data: tapped the trash
      icon on Pixel 5's "Rick and Morty" rule, got the real confirm dialog,
      confirmed removal - "Removal sent to Pixel 5" toast fired and a
      "Pending" section appeared with a cancelable row; tapped its cancel (X)
      - "Push canceled" toast fired and the pending row disappeared, rule
      preserved on Pixel 5 (verified by re-reading the screen - the real
      rule was left untouched, nothing destructive done to the user's
      account). Pull-to-refresh worked (re-synced, "last seen" ticked
      forward, no crash). Top app bar's settings-icon action still navigates
      to Downloads settings correctly. `adb logcat` showed no
      exceptions/crashes for the whole session.
- [ ] On-device verification on px5 (second connected device, registered in
      the app as "Pixel 5"): the same CI-signed release APK installed
      successfully (`adb install -r`, no signature mismatch), but the app
      now crashes on every launch with a pre-existing, **unrelated**
      `javax.crypto.AEADBadTagException` inside
      `SecureCredentialStoreModule.provideEncryptedSharedPreferences` during
      Hilt's `BaseApplication.onCreate` - i.e. before any code this ticket
      touched ever runs. Looks like an Android Keystore key on that specific
      device that no longer decrypts its existing `EncryptedSharedPreferences`
      (not caused by this change - nothing in this diff touches DI, crypto,
      or `core`'s Kotlin source, only `app/phone`, `app/tv`, `settings`, and
      `core`'s string resources). `AGENTS.md`'s documented fix for
      keystore/signature trouble is uninstall+reinstall, which wipes that
      device's Room DB, playback positions, and downloads - the same doc
      says to confirm with the user first since it's not throwaway data, so
      that wasn't done here. Left px5 as found (crashing, pre-existing
      state); this needs the user's go-ahead before anyone wipes its app
      data.

Status: implemented, remote-compile-verified (`compileLibreDebugKotlin`/
`compileDebugKotlin` for `app:phone`/`modes:film`/`settings`, `ktfmtCheck`),
and confirmed working end-to-end on Mi Pad 4, including a real cross-device
rule removal + cancel against "Pixel 5"'s actual data. px5 itself couldn't be
exercised - it hit a pre-existing, unrelated keystore crash blocking app
launch entirely, left as-is pending the user's OK to wipe its local data.

**Note found along the way (2026-07-28)**: px5 (registered in-app as "Pixel
5") crashes on every launch of this same release build with a pre-existing
`javax.crypto.AEADBadTagException` in
`SecureCredentialStoreModule.provideEncryptedSharedPreferences` during Hilt's
`BaseApplication.onCreate` - before any code touched by FINDROID-53/54 ever
runs. Looks like an Android Keystore key on that device that no longer
decrypts its existing `EncryptedSharedPreferences`. The documented fix
(uninstall+reinstall) wipes that device's Room DB/playback positions/
downloads, so it needs the user's go-ahead first - not done yet.

## FINDROID-55: Re-organize Settings root into fewer, more logical sections

Requested (2026-07-28), several tweaks in one sitting, all landing on the
same `topLevelPreferences` structure in `SettingsViewModel.kt`:

- [x] Move "Local CLI access" out of Downloads > auto-download into the Data
      section, alongside Backup and Provision device.
- [x] Downloads screen: reorder so "Auto-download" comes before
      "Auto-delete".
- [x] Rename "Connections" to "Accounts and credentials" and move the
      Account section to be the first entry on the Settings root.
- [x] Fold "Player" into "Interface" (renamed "Appearance") - one combined
      visual+playback section instead of two top-level entries. Fixed the
      "MPV options" sub-screen's breadcrumb, which had hardcoded
      `settings_category_player` as its parent index.
- [x] Fold "Network" (general request/connect/socket/PVR-search timeouts,
      plus "Cache") into Downloads, the same way Cache was already folded
      into Network in an earlier pass.
- [x] Move the Data section (Backup, Provision device, Local CLI access) to
      sit just above About.
- [x] Rename the Downloads screen's "New item notifications" section header
      to just "Notifications".
- [x] Add a "Timeouts" header to the (previously unnamed) request/connect/
      socket/PVR-search timeout group folded in from Network.

Final top-level order: Account, Appearance, Downloads, Data, About (was:
Interface, Player, Account, Data, Download, Network, About). Every
individual preference row preserved; only which top-level group it lives
under, its label, and the overall ordering changed.

Status: done (2026-07-28) - `:settings`/`:app:phone`/`:app:tv` all compile
and `ktfmtCheck` passes on rofl-13. Not separately verified on-device beyond
compile (a pure data/config reorganization, no new runtime logic).

## FINDROID-56: Automatic backup silently fails on cloud-backed folders

Reported (2026-07-28, Mi Pad 4): automatic backup failed with "Could not
create backup file - check the backup folder is still accessible" while a
manual backup with the same folder/params succeeded immediately after -
user suspected the destination (Google Drive) was the cause.

- [x] Confirmed: `AutoBackupScheduler`'s `WorkManager` job had no network
      constraint at all (only `setRequiresBatteryNotLow(true)`), unlike
      `RemoteConfigScheduler`/`QueueStatusScheduler` elsewhere in this
      codebase, which both require `NetworkType.CONNECTED`. Manual backup
      uses `ActivityResultContracts.CreateDocument` - an interactive
      foreground picker, always run with the user present and therefore
      virtually always with live connectivity. Automatic backup reuses the
      persisted folder grant and calls `DocumentFile.createFile()`
      non-interactively from a background job that can fire with no network
      at all - a cloud-backed provider (Drive) genuinely needs network to
      create/write a file, unlike a local folder, and silently returns
      `null` (not an exception) when it can't reach the backend.
- [x] Added `setRequiredNetworkType(NetworkType.CONNECTED)` to
      `AutoBackupScheduler`'s constraints, matching the existing pattern.

Status: done (2026-07-28) - root-caused and fixed; verified remote compile.
Not yet re-confirmed on Mi Pad 4 that a subsequent scheduled run actually
succeeds against the Drive-backed folder (would require waiting out a real
backup interval) - the fix addresses the confirmed root cause, but a live
before/after backup-success confirmation on that device hasn't been done.

## FINDROID-57: Manual-import dialog polish + remote device picker polish

Small UI requests (2026-07-28):

- [x] Manual-import "reject" flow (`ManualImportSheet.kt`): the footer
      button read "Delete & blacklist" even though the confirm dialog it
      opens lets you toggle removal-from-client and blocklisting
      independently. Renamed to "Remove" (matching the dialog's own confirm
      button) and gave the dialog the same icon treatment as
      `DeleteSelectedDownloadsDialog`/`RemovePvrQueueItemDialog` elsewhere on
      the Downloads screen (icon+text title, icon+text confirm/dismiss
      buttons) instead of plain text-only buttons.
- [x] `RemoteDevicePicker` (shared by the auto-download rule editor and the
      regular Download popup): moved to the very top of the rule editor's
      `EditRuleDialog` instead of below the season/scope toggles, and
      restyled as a tonal control (leading device icon, rounded
      `surfaceContainerHigh` surface) instead of a plain list row, plus
      per-row device icons and primary-color emphasis on the selected
      device in its own picker dialog.

Status: done (2026-07-28) - verified remote compile/`ktfmtCheck` for both;
not separately re-verified on-device beyond the general release builds
installed for other same-day work.

## FINDROID-58: Seerr icon + reworded header on search's Seerr section

Requested (2026-07-28): on the Home page's search, when a result isn't in
the Jellyfin library, add a Seerr icon to the "Not in your library" header
and reword it.

- [x] `SearchBar.kt`'s Seerr-results header now uses `SectionServiceIcons`
      (the same brand-icon-plus-title `Row` pattern `HomeDiscoverSection`
      already uses) instead of bare text.
- [x] Reworded `media_seerr_section` from "Not in your library — request via
      Seerr" to "Not in your library — yet!" now that the icon itself
      conveys "via Seerr". Shared string, so `LibraryScreen.kt`'s own Seerr
      section picks up the new wording too (icon not added there - out of
      scope, only the Home search header was requested).

Status: done (2026-07-28) - verified remote compile/`ktfmtCheck`. Not
separately verified on-device.

## FINDROID-59: Delete downloaded files when removing a remote device's rule

Requested (2026-07-28): "when we delete auto-download rules from remote
devices we should give the user the option to also delete the already
downloaded files, just like for local devices." Today, removing a rule for
*this* device (`AutoDownloadRulesViewModel.deleteShowRule`) offers a "also
delete downloaded episodes" checkbox (`ClearDownloadsDialog`); removing a
rule shown for an *other* device (`RemoteDevicesAction.RemoveActiveRule` ->
`RemoteConfigRepository.pushRemoveRule`) has no such option - it just queues
a rule-clear command with no way to also ask that device to delete its
already-downloaded files for that show.

- [x] `pushRemoveRule` is just `pushRuleUpdate` with an empty scope, applied
      on the target device via `RemoteConfigRepositoryImpl.applyReconcileRules`
      -> `AutoDownloadRuleRepository.reconcileRules(...)`. Added
      `alsoDeleteDownloads: Boolean = false` to the `ReconcileRules` wire
      command (defaulted so an old-format command from a not-yet-upgraded
      device still decodes fine), threaded through `pushRemoveRule`/
      `pushRuleUpdate`.
- [x] `applyReconcileRules` (on the *receiving* device), when that flag is
      set, resolves the show's downloaded episodes
      (`database.getEpisodesByShowId(seriesId)` + `toFindroidEpisode`,
      mirroring `deleteShowRule`'s exact local pattern) and calls the
      existing top-level `clearDownloads(items, database, downloader)`
      helper after the rule itself is cleared - gated on the command
      actually being a full clear (`seasonIds` empty and `alsoFutureSeasons`
      false), not merely carrying the flag, so a `ReconcileRules` that still
      leaves part of the show's scope active never deletes anything
      regardless of what the pushing device set. `RemoteConfigRepositoryImpl`
      already had `database`/`downloader` injected - no new DI wiring
      needed.
- [x] UI: `RemoteDevicesScreen.kt`'s `ActiveRuleRow` confirm dialog now
      reuses `ClearDownloadsDialog` (same component the local delete flow
      already uses) instead of a plain `AlertDialog`, so it gets the same
      "also delete downloaded episodes" checkbox for free.
      `RemoteDevicesAction.RemoveActiveRule`/`RemoteDevicesViewModel` thread
      the new `alsoDeleteDownloads` flag through to `pushRemoveRule`.
- [ ] Real cross-device on-device verification (like FINDROID-44's own
      original testing) - push a remove-with-delete from one device, confirm
      the other device's rule *and* its downloaded files for that show are
      both gone after its next sync - not done yet.

Status: implemented (2026-07-28) - remote compile
(`:data:compileDebugKotlin`/`:core:compileLibreDebugKotlin`/
`:app:phone:compileLibreDebugKotlin`), `ktfmtCheck`, and
`:data:testDebugUnitTest`/`:core:testLibreDebugUnitTest` all clean. Not yet
verified on a real device - no live cross-device push-with-delete round
trip confirmed yet.

## FINDROID-60: findroid-cli start/stop the app

Requested (2026-07-28): "findroid-cli should have a start/stop command to
start/stop the app."

- [x] `stop`: no OS-level way for an unprivileged Termux process to
      force-stop another app (`android.permission.FORCE_STOP_PACKAGES` is
      signature/privileged-only - the same wall FINDROID-45's original
      ContentProvider transport hit for
      `ACCESS_CONTENT_PROVIDERS_EXTERNALLY`, confirmed `pm grant` refuses it
      even as root). Sidestepped by asking the app to exit *itself* instead:
      new authenticated `POST /app/stop` on `LocalControlRouter` that starts
      a background thread which sleeps 300ms then calls
      `Runtime.getRuntime().exit(0)` (same call `Activity.restartProcess()`
      already uses elsewhere), returning its 200 immediately so NanoHTTPD
      has time to actually write the response before the process dies -
      killing synchronously in the handler would race that write and the
      client would just see a dropped connection. No root needed.
- [x] `start`: the app isn't running yet in this case, so there's nothing to
      ask over the local control API - this is the one command that doesn't
      go through `local_request` at all. Shells out straight to `am start`
      (default package `dev.pschmitt.findroidplus`, overridable via a new
      `FINDROID_PACKAGE_NAME` env var for a debug/staging install). Works
      without root as long as the calling shell is in the foreground at the
      moment the command runs - Android's background-activity-start
      restrictions only block launches from processes with no visible UI.
      **Found and fixed a real bug during on-device verification**: the
      launcher activity is *not* `<applicationId>.MainActivity` - this
      app's `applicationId` (`dev.pschmitt.findroidplus`) was rebranded
      independently of its actual Kotlin/manifest `namespace`, which is
      still `dev.jdtech.jellyfin` (unchanged across every build variant).
      `am start` needs `<applicationId>/dev.jdtech.jellyfin.MainActivity` -
      confirmed via `cmd package resolve-activity` against the real
      installed package after the original guess failed with "Activity
      class ... does not exist" both unprivileged and as root.
- [x] `CLI_VERSION` bumped to 1.1.0. Updated `usage()` with both new
      commands and the new `FINDROID_PACKAGE_NAME` env var.

Status: **done** (2026-07-28) - verified end-to-end on the Mi Pad 4 (real
device, real token, real install): `am start` (corrected component) brings
the app up; `POST /app/stop` takes it back down (confirmed `GET /info`
stops responding); `am start` again brings it back up (confirmed `GET
/info` responds with the real `versionName`/`versionCode`/`gitRevision`).
Also confirmed the same-day Settings reorg (FINDROID-55) and hide-token
toggle are both live and correct on this device along the way.

## FINDROID-61: Rename `dev.jdtech` package namespace to `dev.pschmitt`

Requested (2026-07-28), found while debugging FINDROID-60's `am start`: this
fork's `applicationId` was rebranded to `dev.pschmitt.findroidplus`
(FINDROID-1) but the actual Kotlin/manifest package namespace across the
whole codebase is still `dev.jdtech.jellyfin` (upstream Findroid's
original) - `app/phone/build.gradle.kts`'s `namespace` is still
`dev.jdtech.jellyfin`, unchanged by any `applicationIdSuffix`. This is
exactly what caused FINDROID-60's first `am start -n
<applicationId>/<applicationId>.MainActivity` guess to fail - the real
component is `<applicationId>/dev.jdtech.jellyfin.MainActivity`.

- [x] Confirmed `applicationId` and `namespace` never need to match -
      Android doesn't enforce it. Scoped the rename precisely instead of
      trusting the "558 files" estimate blind: `git grep` for
      `dev\.jdtech\.jellyfin`/`dev/jdtech/jellyfin` found 564 tracked
      files (548 `.kt`, 9 `.kts` `namespace` declarations, 1 `AndroidManifest.xml`,
      plus `TODO.md` itself, both `proguard-rules.pro` files,
      `fastlane/Appfile`, and `cli/findroid-cli`) - deliberately excluding
      `dev.jdtech.mpv`, the real external libmpv Maven group id
      (`gradle/libs.versions.toml` + 3 imports in
      `player/local/.../mpv/MPVPlayer.kt`), which just happens to share the
      `dev.jdtech` prefix and must NOT be touched.
- [x] `git mv`'d 13 `.../src/{main,test,androidTest}/java/dev/jdtech/jellyfin`
      source roots to `dev/pschmitt/jellyfin` (one `git mv` per module/source-set,
      cleaning up the now-empty `dev/jdtech` parent each time), then a
      scripted `sed` pass over the exact 563 remaining files (`TODO.md`
      excluded, to keep its own historical narrative about this rename
      intact) replaced `dev.jdtech.jellyfin`/`dev/jdtech/jellyfin` with
      `dev.pschmitt.jellyfin`/`dev/pschmitt/jellyfin`.
- [x] `app/tv/src/main/AndroidManifest.xml`'s two FQN service names
      (`VideoDownloadService`/`ResumeDownloadsJobService`) converted to
      relative (`.work.X`), matching `app/phone`'s existing convention -
      one less place for the org name to drift again.
- [x] `app/tv/proguard-rules.pro`'s blanket `-keep class dev.jdtech.**`
      covered both our own old code AND libmpv in one rule - split into an
      explicit `-keep class dev.pschmitt.jellyfin.**` (our code, renamed)
      plus a separate `-keep class dev.jdtech.mpv.**` (libmpv's JNI-bound
      classes, left alone on purpose - obfuscating/stripping them would
      break the native binding since the JVM resolves JNI methods by exact
      name, not by any bytecode reference R8 can trace).
- [x] `cli/findroid-cli`'s `MAIN_ACTIVITY` and its explanatory comment
      updated; `fastlane/Appfile`'s `package_name` mechanically renamed too
      (`dev.pschmitt.jellyfin`) - note this was already stale relative to
      the real `applicationId` (`dev.pschmitt.findroidplus`) before this
      change and still is after; that's FINDROID-1's pre-existing gap
      between the two, unrelated to this rename, and out of scope here.
      Fastlane isn't wired into any CI workflow, so nothing consumes this
      value today.
- [x] **Found and fixed a real bug during build verification**: Room's
      schema-export directory name is derived from the `@Database`
      class's fully-qualified name, not just its simple name -
      `data/schemas/dev.jdtech.jellyfin.database.ServerDatabase/` (15
      versioned `.json` files) needed its own `git mv` to
      `dev.pschmitt.jellyfin.database.ServerDatabase/` too. This directory
      uses dots as a single flat segment name rather than `dev/jdtech/jellyfin`
      nested path components, so it was invisible to both the initial
      `find -type d -path ".../dev/jdtech/jellyfin"` sweep and the
      content-based `git grep` sweep (directory *names* aren't file
      content) - only caught because `:data:kspDebugKotlin` failed outright
      ("Schema '2.json' ... was not found ... Cannot generate auto
      migrations") on the first real build attempt. Confirmed the JSON
      files' content has no FQN references needing changes (just table
      schemas/an `identityHash`), so a plain directory rename was the
      complete fix.
- [x] Verified with a real remote Gradle build (`just gradle rofl-13.brkn.lol
      :app:phone:assembleLibreDebug :app:tv:assembleLibreDebug`), not just
      `bash -n`/`shellcheck` on the CLI side - this is exactly the kind of
      rename where "looks right" and "compiles" diverge (see the schema
      bug above, and a second stale-incremental-build false failure on
      `app/tv` needing a `just clean` before it would build fresh - a
      remote-host incremental-cache artifact, not a real code issue).
      Both `assembleLibreDebug` targets build clean from scratch; no
      remaining `dev.jdtech` references anywhere in the tree outside the
      one deliberately-preserved `dev.jdtech.mpv` external library id.
      `:data:testDebugUnitTest`/`:core:testLibreDebugUnitTest` also both
      pass.
- [x] Device-verified (2026-07-28): built `:app:phone:assembleLibreRelease`
      with the CI signing keystore via `just deploy --release`
      (SHA-256 cert `310ba5d5...`, matching both devices' existing
      install), installed on both the Mi Pad 4 and "px5" (Pixel 5).
      `am start -n dev.pschmitt.findroidplus/dev.pschmitt.jellyfin.MainActivity`
      resolves and launches on both. Confirmed the OLD `findroid-cli`
      (1.1.0, predating this rename) fails exactly as expected against the
      renamed install (`Activity class ... dev.jdtech.jellyfin.MainActivity
      ... does not exist`) - real-world proof the rename actually changed
      the component apps launch by. Bootstrapped the Mi Pad's installed CLI
      to 1.2.0 via `curl .../cli` (the documented fallback, since the old
      CLI predates FINDROID-62's `update` command), then exercised the new
      CLI for real: `update` correctly reports "already up to date",
      `--json version` pretty-prints just the body (FINDROID-63), and
      `stop`/`start` round-trips correctly using the renamed
      `MAIN_ACTIVITY`.

Status: **done** (2026-07-28) - renamed, built clean (twice, from
scratch), device-verified on both the Mi Pad 4 and px5. `findroid-cli`'s `MAIN_ACTIVITY` was
updated in lockstep as anticipated when this was originally scoped.

## FINDROID-62: findroid-cli self-update subcommand

Requested (2026-07-28): "I want a self-update subcmd for the findroid-cli!
it should well, update itself by fetching the 'new' bin via the local tcp
server on port 48411."

- [x] The app already serves the exact bundled `cli/findroid-cli` script,
      unauthenticated, at `GET /cli` (`LocalControlServer.CLI_PATH`, added
      for the bootstrap-download use case - "the same way Shizuku's `rish`
      client is downloadable straight from the Shizuku app"). No new
      app-side work needed - this is a CLI-only change.
- [x] New `update` command: `curl`s `${BASE_URL}/cli` directly (bypasses
      `local_request`, same as `start` - no token needed, no JSON), checks
      the response looks like a real script (shebang + a `CLI_VERSION=`
      line) before trusting it, compares that version against this
      process's own `CLI_VERSION`, and - if different (or `--force`) -
      writes it to a temp file next to the resolved self path
      (`readlink -f "$0"`, to follow a PATH symlink to the real file) and
      atomically `mv`s it over itself, preserving the executable bit.
      Skips with "already up to date" otherwise.
- [x] Bump `CLI_VERSION` to 1.2.0, document `update` in `usage()`.
- [x] Follow-up (requested same day): reworded the success message to
      "Upgraded findroid-cli from X to Y (path)" - a real unified diff of
      old vs. new script content was considered but not what was asked for
      ("x and y being the versions" clarified this means the version
      transition, not a text diff).

Status: **done** (2026-07-28) - implemented and exercised against a local
stub HTTP server on 48411 standing in for the app (not a real device):
verified a real update (version bump + content replaced + executable bit
preserved), the "already up to date" skip, `--json` output, and both
failure guards (server unreachable, response that doesn't look like a
script). Also device-verified (2026-07-28, alongside FINDROID-61):
bootstrapped the Mi Pad 4's stale 1.1.0 install to 1.2.0 via `curl
.../cli` (the documented fallback for a CLI that predates this very
command), then confirmed the new `update` command itself correctly
reports "already up to date" against the real running app.

## FINDROID-63: findroid-cli --json should print just the response body

Requested (2026-07-28): "when invoking the cli with --json, let's just
return the body. status is just noise."

- [x] `print_response_json` (the shared helper behind every `--json` call
      site) now prints `.body` instead of the whole `{status, body}`
      wrapper - the HTTP status is still used internally to set the exit
      code (2xx -> 0), same as before, just not printed anymore.
- [x] `cmd_debug`'s own separate `--json` branch (it doesn't go through
      `print_response_json` - a non-2xx there is proxied-service output,
      not a CLI-level failure) updated the same way: status now goes to
      stderr (`HTTP %s`, same as its non-JSON path already did) and stdout
      gets just `.body`.
- [x] Updated the `--json` help text in `usage()` to match ("Print the
      response's body as JSON instead of a table").
- [x] Follow-up (requested same day): pretty-print (indent) the printed
      body instead of compact single-line output - both spots dropped
      `jq`'s `-c` flag. `jq` stays a hard overall dependency
      (`require_deps`, and every subcommand's internal JSON
      parsing/building already needs it regardless) - this is purely
      about the final output's formatting, not about making `jq` optional
      tool-wide (that would be a much larger rewrite, explicitly
      considered and declined).

Status: **done** (2026-07-28).

## FINDROID-64: replace the Jellyfin-derived mark with an original one ("Scout")

The launcher icon, TV banner (both the adaptive `ic_banner_foreground.xml` and the flat
`ic_banner.xml` shown across five phone setup screens), and in-app header logo
(`ic_logo.xml`) all traced Jellyfin's actual logo path (the triangular play-badge with an
Android-robot-head cutout), tinted Android's own green fading into Jellyfin's own blue. Real
trademark exposure for a fork with its own listing, not just a hobby-repo nicety.

- [x] Design an original mark ("Scout" - a small scanner-droid: satellite-dish ears, a
  camera-lens eye, an antenna capped with the "+" that marks this as the "+" fork) and confirm
  the direction before implementing.
- [x] Replace `ic_launcher_foreground.xml` (phone + TV launcher icon).
- [x] Replace `ic_logo.xml` (in-app header logo - Home, top bar, integrations screen, TV main
  screen).
- [x] Replace the bird portion of `ic_banner_foreground.xml` (TV banner), keeping the
  "Findroid" wordmark glyphs untouched, and drop the standalone "+" badge that used to sit next
  to the wordmark now that Scout's antenna carries the "+" itself.
- [x] Same treatment for `ic_banner.xml`, the larger flat banner actually shown across Login,
  Servers, AddServer, Welcome, and Users setup screens (higher real visibility than the
  TV-only adaptive banner).
- [x] Update `logo_primary`/`logo_secondary` in `core/src/main/res/values/colors.xml` from
  Android green/Jellyfin blue to Scout's own indigo/violet, while leaving the `debug`/`staging`
  flavor overrides (red/blue build-variant tinting) untouched - all four files reference
  `@color/logo_primary`/`logo_secondary` rather than hardcoded hex, so that tinting still works.
- [x] Verify with remote `:app:phone:assembleLibreDebug` and `:app:tv:assembleLibreDebug`, then
  install the phone build on every attached device.

**Why:** user's explicit direction, following the same fix already applied to Nyetbox's
NetBox-derived icon the same day.
**How to apply:** Scout is authored in a 200x200 viewport (`M100,68 Q120,52 120,34` antenna,
`M100,66 A48,48...Z` pill body, etc.) wrapped in a `pivotX="100" pivotY="100" scaleX="0.72"
scaleY="0.72"` group for the icon/logo surfaces. For the two banners, that same pivot-scaled
group (or the raw paths, for `ic_banner.xml`'s larger canvas) is nested inside an outer
positioning `<group translateX=... translateY=... scaleX=... scaleY=...>` to land it in the
banner's existing left-hand mark area without disturbing the wordmark's own transform chain -
`ic_banner_foreground.xml` reuses its pre-existing outer `0.6666667`/`53.333332,30` wrapper
(math: `outer_local = (final - translate) / scale`), `ic_banner.xml` has no such wrapper so its
group transform was derived directly.

Status: **done**, 2026-08-04 - remote `:app:phone:assembleLibreDebug` and
`:app:tv:assembleLibreDebug` both passed; phone build installed on Zenfone 10, Mi Pad 4, and
Pixel 5. Not yet done: the raster promotional banner (`images/findroid-banner.png` /
`core/src/main/res/drawable/findroid_banner.png`, shown in the About screen and the README) and
the Play Store `fastlane` icon/feature-graphic images still carry the old mark - no Play Console
listing exists for this fork to push those to, but the raster banner is a real follow-up.

## FINDROID-65: Scout follow-up - contrast, size, header, and the real Jellyfin logo

Direct user feedback on FINDROID-64's Scout mark, applied the same day.

- [x] Antenna stalk was `#241B4E` (near-black navy) floating directly on the adaptive icon's
  pure-black `ic_launcher_background` - nearly invisible. Recolored to `#8A4DFF`, matching the
  antenna ring, across all four surfaces (`ic_launcher_foreground.xml`, `ic_logo.xml`,
  `ic_banner_foreground.xml`, `ic_banner.xml`).
- [x] "Still looks small" - shortened the antenna curve (`Q120,52 120,34` to `Q114,54 114,42`,
  ring/badge moved from `(120,34)` to `(114,42)`), which trims Scout's own bounding height and
  lets the pivot-scale group go from 0.72 to 0.80 while staying inside the adaptive-icon safe
  zone - a visibly bigger mark without redrawing anything else. Both banner files' outer
  positioning transforms were re-derived for the new bounding box
  (`ic_banner_foreground.xml`: translate `-156,-74` scale `1.66`; `ic_banner.xml`: translate
  `-80,-69` scale `3.3`).
- [x] Bug found via on-device screenshot, not caught by the build: `ic_banner.xml` (the banner
  actually shown across Login/Servers/AddServer/Welcome/Users) still had its own standalone
  "+" badge circle left over from before FINDROID-64 - `ic_banner_foreground.xml`'s copy had
  already been removed, this one was missed. Showed as a stray red circle (debug-flavor
  `logo_primary` tint) sitting on Scout's body. Removed.
- [x] `TopBarTitle`'s icon was pinned to a flat 24dp regardless of context - enlarged to 32dp.
- [x] Added the *real*, unmodified official Jellyfin logo (`ic_jellyfin_logo.xml`, sourced from
  `jellyfin/jellyfin-ux`'s `logos/SVG` - the color-on-dark/color-on-light variants are
  pixel-identical in color, so one drawable covers both) and swapped it in for the three
  per-section "which service is this from" icons on the home feed (`HomeSection.kt`,
  `HomeCarousel.kt`, `HomeView.kt`'s `SectionServiceIcons` calls) - explicitly *not* in
  `TopBarTitle`/`HomeHeader.kt`, which keeps Scout. This is normal nominative use (crediting the
  actual upstream service you're browsing), the opposite situation from FINDROID-64's problem
  of using Jellyfin's mark *as this fork's own* identity.
- [x] Verified via on-device screenshots on Zenfone 10 (caught the leftover badge bug this way),
  then remote `:app:phone:assembleLibreDebug`/`assembleLibreRelease` and
  `:app:tv:assembleLibreDebug`, then deployed debug and a freshly signed release build to all
  three attached devices (Zenfone 10, Mi Pad 4, Pixel 5) - user asked specifically to keep the
  production/release install current, not just debug.

**Why:** user's direct, same-day feedback on FINDROID-64; the Jellyfin-logo addition was a
separate explicit request once Scout's own identity was settled.
**How to apply:** see FINDROID-64 for Scout's base geometry/rationale; this entry only covers
what changed on top of it.

Status: **done**, 2026-08-04.

## FINDROID-66: rebuild the promo banner and shrink the logo for its small call sites

The two remaining pieces flagged as follow-up in FINDROID-65.

- [x] Rebuilt `images/findroid-banner.png` / `core/src/main/res/drawable/findroid_banner.png`
  (identical files, README hero + About screen) from scratch - same layout as the original
  (black rounded card, mark on the left, "Findroid+" / "For Jellyfin" wordmark on the right),
  Scout in place of the Jellyfin-derived mark. Built as an SVG (real gradient, real text) and
  rasterized with `magick` - no `rsvg-convert` on this machine, but ImageMagick's built-in MSVG
  fallback handles gradients and text fine at this level of complexity.
- [x] `ic_logo.xml` (the icon shared by `TopBarTitle`/Home's header, the TV tab row, and the
  integrations settings row) rewritten as "Scout Mini": every one of its remaining call sites
  renders at 28-32dp, where the full character's ears/antenna/iris-ring/glint compress into
  noise. Mini keeps only the gradient body and the plain eye (2 shapes instead of 9) and is
  scaled to fill more of its own viewport now that it isn't sharing geometry with the launcher
  icon. The full character is untouched everywhere it's actually rendered big (launcher icon,
  both banners).
- [x] Verified the banner change on-device (Welcome screen screenshot, Zenfone 10) and the Mini
  geometry via a standalone SVG render at 32px/28px before trusting it in the app, since the
  device wasn't logged into a Jellyfin server to reach the small-icon call sites directly.
  Remote `:app:phone:assembleLibreDebug`/`assembleLibreRelease` and `:app:tv:assembleLibreDebug`
  all passed; debug and a fresh signed release build installed on Zenfone 10, Mi Pad 4, and
  Pixel 5.

**Why:** the two items explicitly deferred at the end of FINDROID-65, picked back up same day
per user request ("let's do the remaining stuff").

- [x] Follow-up (same day): the Play Store `fastlane` images still carried the old mark, flagged
  as low-priority since no listing exists to push them to - user asked for these too (told to
  hold off on the phone/tablet screenshots for now). Rebuilt `fastlane/metadata/android/en-US/
  images/icon.png` (512x512, Scout filling the frame on black, matching the real launcher icon)
  and `featureGraphic.png` (1024x500, reusing the promo banner's layout minus its rounded
  corners, since the original feature graphic was the same design on a plain black rect).

Status: **done**, 2026-08-04.

## FINDROID-67: Scout v2 - no antenna, bigger, properly centered; Jellyfin logo on server rows

Direct user feedback after seeing FINDROID-64/65's Scout live on-device.

- [x] Dropped the antenna/branch and its "+" entirely (four concepts were shown - user picked
  "pure creature", no badge anywhere on the mark itself).
- [x] Trimmed the ear circles (r12/r5 to r9/r4) so the body reads as the dominant shape.
- [x] Removing the antenna shrank the true bounding box enough to scale the mark up
  substantially - landed on 8% safe-zone margin (scale 0.97, up from 0.80) per explicit
  request ("fill as much of the safe space as possible").
- [x] `ic_launcher_background` changed from black to neutral grey (`#232323`) per user request,
  compared against several shades in the concept review first.
- [x] Bug found via user report, not caught by any of the safe-zone math: the character was
  sitting low in the circle, eye nowhere near center. Root cause - the body/eye were never
  vertically centered on the pivot point to begin with (eye at y=112 vs. pivot y=100), so
  scaling around that pivot only ever made the offset worse. Fixed by shifting all geometry up
  12 units so the eye actually sits on the safe-zone center, then rebalancing the scale for the
  corrected (and much better-behaved) bounding box.
- [x] Re-derived every dependent transform for the shift: both banner files' outer positioning
  groups, the raster promo banner, the Play Store icon and feature graphic (which also moved to
  the grey background to match the real installed icon).
- [x] The real Jellyfin logo now marks individual server entries specifically - the header's
  server-switcher bottom sheet (`ServerSelectionItem.kt`) and the per-server rows in Settings >
  Accounts (`IntegrationsSettingsScreen.kt`'s `JellyfinServerRow`) - instead of a generic
  `ic_server` icon. Both needed `Icon` swapped for `Image` (or a new `iconTinted = false` escape
  hatch on `PreferenceCategory`, for the settings-list case) since Material's `Icon` flattens
  any painter to a single tint color, which would have erased the logo's gradient.
- [x] Renamed the Settings row "Accounts and credentials" to "Accounts".
- [x] `ic_jellyfin_logo.xml` duplicated into `:settings`'s own resources rather than depended on
  from `:core` - `:core` already depends on `:settings`, so the reverse would be a circular
  module dependency. Noted in a comment on both copies to keep them in sync if this static
  upstream asset ever needs to change.
- [x] Verified centering via on-device screenshots (app drawer icon, Zenfone 10) before and
  after the fix - the "before" screenshot is what caught the bug in the first place. Remote
  `:app:phone:assembleLibreDebug`/`assembleLibreRelease` and `:app:tv:assembleLibreDebug` all
  passed. Deployed debug and release to all three attached devices (px5 dropped its ADB
  connection mid-session and needed a manual reconnect on a new port).

**Why:** direct, same-day user feedback on FINDROID-64/65/66's Scout.
**How to apply:** see FINDROID-64 for Scout's original geometry/rationale.

Status: **done**, 2026-08-04 - released as part of v2.12.2.

## FINDROID-68: rename the app from Findroid+ to JollyFin

The GitHub repo was already renamed `pschmitt/findroidplus` -> `pschmitt/jollyfin` and the local
remote repointed. This entry covers the in-repo half: package id, docs, README, Play Store
metadata, and every other user-facing "Findroid+" (or bare self-referential "Findroid") string,
version bump to 2.13.0. `Findroid*`-prefixed Kotlin class names (`FindroidItem`, `FindroidTheme`,
etc.), the `dev.pschmitt.jellyfin` namespace, `Theme.Findroid`/`ShapeAppearance.Findroid*` style
resource names, and the "fork of Findroid" attribution language were deliberately left alone -
internal architecture naming and legitimate upstream lineage, not this app's own branding.

- [x] `applicationId` in `app/phone/build.gradle.kts` and `app/tv/build.gradle.kts`:
  `dev.pschmitt.findroidplus` -> `dev.pschmitt.jollyfin` (namespace untouched).
- [x] `settings.gradle.kts`: `rootProject.name` -> `"jollyfin"`.
- [x] Version bump: `Versions.kt` `APP_CODE` 50 -> 51, `APP_NAME` `"2.12.2"` -> `"2.13.0"`, plus
  `fastlane/metadata/android/en-US/changelogs/51.txt`.
- [x] Every user-facing "Findroid+" string (and bare self-referential "Findroid", e.g. the
  never-updated non-English `welcome`/`welcome_text`/`privacy_policy_notice` translations) ->
  "JollyFin": `app_name` (default/debug/staging), setup welcome screen, QR export/scan strings,
  About screen GitHub link, `BackupCrypto`'s corrupt-backup message, and all 42 locale
  `strings.xml` files that still said bare "Findroid" in-string.
- [x] Deep-link scheme `findroidplus://` -> `jollyfin://`: `AndroidManifest.xml`'s intent-filter,
  `MainActivity.kt`, `QrScanScreen.kt` comment, `QrConfigCodec.kt` (`URI_PREFIX` + exception
  text), and its unit test.
- [x] `BackupFileNaming`'s `findroidplus-backup-...` prefix -> `jollyfin-backup-...`.
- [x] `RemoteConfigRepositoryImpl`'s `DisplayPreferences` bucket/client id
  (`findroidplus-remoteconfig` / `FindroidPlusRemoteConfig`) -> `jollyfin-remoteconfig` /
  `JollyFinRemoteConfig`. Note: any user with a pending remote-config queue under the old bucket
  name will see it as empty after upgrading - low-frequency, self-healing, accepted tradeoff.
- [x] TV banner assets - same "hand-traced vector lettering can't be text-replaced" problem
  FINDROID-64 already solved for the mascot, now hitting the wordmark. Converted all three
  affected files from vector to raster (SVG source + `magick`, reusing Scout's geometry from
  `ic_launcher_foreground.xml`):
  - `core/.../drawable/ic_banner_foreground.xml` (TV launcher banner, 320x180) -> flat raster at
    `drawable-nodpi/ic_banner_foreground.png`. Simplified `mipmap-anydpi/ic_banner.xml` from an
    `<adaptive-icon>` wrapper to a plain `<bitmap>` (the background is now baked into the PNG),
    and dropped the now-unused `ic_banner_background` color resource.
  - `core/.../drawable/ic_banner.xml` (1536x512, shown across Login/Servers/AddServer/Welcome/
    Users on phone) -> raster. This one used `?attr/colorOnBackground` for the wordmark so it'd
    read on both light and dark app themes - a static PNG can't do that, so it's now two PNGs
    (`drawable-nodpi` + `drawable-night-nodpi`) selected by Android's night-mode qualifier, which
    matches every real call site since `FindroidTheme` always derives `darkTheme` from
    `isSystemInDarkTheme()` (no explicit override anywhere in the app).
  - `app/tv/.../drawable/ic_banner.xml` - a **separate, module-local** resource (referenced via
    plain `R.drawable.ic_banner` from TV's own `WelcomeScreen.kt`, not `CoreR`) discovered while
    grepping for "Findroid" - not one of the two files FINDROID-64-67 touched. It still carried
    the *pre-Scout*, Jellyfin-trademark-derived triangle/Android-head mark plus a standalone
    green "+" badge ("Keep the TV launcher banner visibly distinct from upstream Findroid").
    Swapped only its wordmark to "JollyFin" and dropped the now-meaningless "+" badge (the "+"
    is gone from the name everywhere else too); left the old mascot itself alone since
    redesigning it is mascot-level work, not a rename. **Flagging this as a known gap**: this
    asset is still visually inconsistent with the rest of the app and still carries the exact
    kind of mark Scout was created to retire - worth a fast-follow to bring it onto Scout.
- [x] Raster promo banner (`images/findroid-banner.png` / `core/.../findroid_banner.png`) and
  the Play Store `featureGraphic.png` re-rasterized with "JollyFin" via the same SVG+`magick`
  pipeline the Scout session used (recovered its working SVG from `/tmp/scout-v2-work/` to keep
  the exact geometry/gradient/subtitle). Renamed to `jollyfin-banner.png`/`jollyfin_banner.png`
  for consistency; updated README's image embed and `AboutScreen.kt`'s `painterResource`.
  `icon.png` left untouched - its source SVG has no text, just Scout on the grey background.
- [x] `README.md`: title, intro (kept the "fork of Findroid" lineage/GPLv3 attribution, reworded
  around it), install section, Obtainium badge JSON payload, GitHub URLs.
- [x] `REPRODUCIBLE_BUILDS.md`: app name, `git clone` URL. Also fixed the worked example's APK
  filename (`findroid-plus-latest-arm64-v8a-release.apk`, which never matched the real
  `phone-libre-<abi>-release.apk` naming even before this rename) while touching that line.
- [x] `AGENTS.md`: title, remote-verify sync dir naming (`findroid-verify*` ->
  `jollyfin-verify*`), `FINDROID_REMOTE_PATH` -> `JOLLYFIN_REMOTE_PATH`.
- [x] `justfile`: header comment, `FINDROID_REMOTE_HOST`/`FINDROID_REMOTE_PATH`/
  `FINDROID_DIST_DIR` -> `JOLLYFIN_*`, remote-verify default path, the release build's remote
  log filename. Left the `"Findroid CI Signing Keystore"` rbw entry name and every
  `findroid-ci*`/`.findroid-ci-tmp` filename/dirname exactly as-is - that credential isn't being
  renamed as part of this task.
- [x] `.github/workflows/release.yaml`: applicationId mentions in the release-notes template,
  REPRODUCIBLE_BUILDS.md links, and the uploaded-asset label prefix (`findroid-plus-<tag>` ->
  `jollyfin-<tag>`, which is what release APK filenames on GitHub actually carry). Left
  `findroid-ci.jks` (same keystore exception) untouched. `sync-upstream.yaml` needed no changes -
  its "findroid" mentions correctly refer to the actual upstream repo.
- [x] `fastlane/metadata/android/en-US/title.txt` and `full_description.txt` -> "JollyFin".
- [x] `cli/findroid-cli`: fixed the now-broken `PACKAGE_NAME` default
  (`dev.pschmitt.findroidplus` -> `dev.pschmitt.jollyfin` - this was a real functional bug, not
  just cosmetic, since "start" launches by applicationId) and its matching comment, renamed the
  `FINDROID_LOCAL_URL`/`FINDROID_PACKAGE_NAME` env vars to `JOLLYFIN_*`, and swapped "Findroid+"
  in help/error text to "JollyFin". Left the script's own command name, `~/.config/findroid-cli`
  config dir, and self-update messaging as `findroid-cli` - not explicitly in this task's scope,
  and renaming the actual command a Termux user types is a bigger call than a text/id rename.
  Flagging for a decision rather than guessing.
- [x] Final sweep (`grep -rIli findroidplus` / `grep -rIln "Findroid+"`, excluding `.git`,
  `build`, `dist`, `.gradle`, and the stale `.claude/worktrees/` copies of the old `findroid.git`
  checkout) clean except: `TODO.md`'s own historical entries (not rewritten, per house style),
  README's one intentional "previously known as Findroid+" mention, and this entry's own prose.

**Why:** user's explicit direction - "EVERYTHING" per their own words, except the Play Console
listing (must be created manually, tracked separately) and Scout's mascot geometry/colors
(redesigned same day, immediately prior, left alone here).
**How to apply:** see FINDROID-64 for Scout's geometry if extending it to `app/tv`'s still-old
banner mentioned above. The raster banner pipeline (SVG + `magick`, Liberation Sans Bold/Italic)
is the same one FINDROID-66 established; its working files are one-off scratch SVGs, not checked
into the repo, so regenerate from scratch (mark path data is in `ic_launcher_foreground.xml`)
rather than expecting to find them again.

- [x] Build-verified remotely: `assembleLibreDebug`/`assembleLibreRelease` for both
  `:app:phone`/`:app:tv` all passed on rofl-13. Installed and screenshot-checked on real devices
  (Mi Pad 4, px5) - Welcome screen, banner, and About screen all render correctly as "JollyFin"
  with no leftover "Findroid+"/"Findroid" text, and no white-corner artifact from the transparency
  bug caught and fixed along the way (see below).
- [x] Found and fixed a real rasterization bug via the on-device check above: the hero/TV banner
  PNGs came out with an opaque white background baked in instead of transparent (ImageMagick's
  `-background none` flag only takes effect when placed *before* the SVG input on the command
  line - it was after, so it silently no-op'd and the MSVG delegate's default opaque-white canvas
  won instead). Invisible in a Read-tool preview against a light backdrop, but showed as an ugly
  white box behind the mark on the app's actual dark Welcome screen. Re-rasterized all three
  affected PNGs (`core/.../drawable-nodpi/ic_banner.png`, `core/.../drawable-night-nodpi/
  ic_banner.png`, `app/tv/.../drawable-nodpi/ic_banner.png`) plus the promo banner's rounded
  corners (same root cause, `images/jollyfin-banner.png` /`core/.../jollyfin_banner.png`) with
  `-background none` placed correctly and `PNG32:` forced for a real alpha channel. Verify with
  `magick <file>.png -format "%[pixel:p{0,0}]" info:` before trusting any future re-rasterize of
  these three assets - it should read `srgba(0,0,0,0)`, not opaque white.
- [x] Found and fixed a second real bug via live device testing (backup/restore round-trip,
  requested by user): `LocalControlServer.PORT` was a single hardcoded `48411` shared by every
  build variant. Installing/running a debug build alongside an already-running release build
  (exactly what happened testing restore with both on px5) crashed the second one to start at
  `BaseApplication.onCreate()` with `BindException: EADDRINUSE` - confirmed via `adb logcat -d`,
  **not** a restore-logic bug (`RestoreBackupViewModel.loadBackup()` already catches broadly and
  surfaces errors in-UI rather than crashing; the actual crash came from the process-restart-after-
  restore flow (`Activity.restartProcess()`) relaunching into a still-EADDRINUSE port). Fix:
  `LocalControlServer.portFor(packageName)` now offsets by build-variant suffix (`.debug` ->
  `BASE_PORT+1`, `.staging` -> `+2`, release stays `BASE_PORT`); `cli/findroid-cli` derives the
  matching default port from `JOLLYFIN_PACKAGE_NAME` automatically so `JOLLYFIN_LOCAL_URL` only
  needs setting for something unusual (forwarded port, etc). Touched: `LocalControlServer.kt`,
  `LocalAccessViewModel.kt` (now reads the instance `port` property, not a removed `PORT`
  const), `cli/findroid-cli`.

**Handoff - resolved in the 2026-08-05 follow-up session:**
- [x] **Port fix rebuilt and re-verified on-device.** Built `assembleLibreDebug` +
  `assembleLibreRelease` remotely (see "Concurrency gotcha" below for why rofl-14 was used
  instead of rofl-13), installed both on the Mi Pad 4 alongside each other, force-stopped and
  relaunched together. Confirmed via `/proc/net/tcp6` (NanoHTTPD binds `127.0.0.1` as an
  IPv4-mapped IPv6 socket, so plain `/proc/net/tcp` shows nothing - check `tcp6`) that release
  listens on 48411 and debug on 48412 simultaneously, plus `curl http://127.0.0.1:<port>/cli`
  returned `200` on both. No `EADDRINUSE`/crash in either. One false alarm along the way: a
  first attempt crashed release with the exact `EADDRINUSE` the fix targets - root cause was the
  device's stale **pre-rename** `dev.pschmitt.findroidplus`/`.debug` installs (old code, still
  hardcoded to port 48411 unconditionally) running in the background and holding the port, not a
  flaw in the fix. Uninstalled both stale packages (confirmed throwaway - literally the
  pre-rename version of the same app this whole ticket replaces) and the conflict was gone.
- [x] **Fastlane Play Store screenshots** - all 15 audited (`phone`x5, `sevenInch`x5,
  `tenInch`x5). All clean: every screenshot shows either the "Stable Demo" server-selector chrome
  or an in-content screen (movie details, episode list) with no app name, launcher icon, or
  Settings/About text anywhere. No retakes needed.
- [x] Play Console listing for `dev.pschmitt.jollyfin` now exists (user confirmed) - the "doesn't
  exist yet" gap noted originally is stale, no action needed from the fastlane metadata side.

**Handoff - corrected finding, 2026-08-05:** `app/tv/src/main/res/drawable-nodpi/ic_banner.png`
(flagged above as still carrying the pre-Scout mascot) turned out to be **dead code, not a live
bug**. Verified two ways: (1) `app/tv`'s `WelcomeScreen.kt` imports `dev.pschmitt.jellyfin.core.R`
explicitly and its `R.drawable.ic_banner` reference resolves through that import to **core's**
already-Scout-ified `ic_banner.png` (with day/night variants) - not this module-local file, since
`app/tv` (`namespace = "dev.pschmitt.jellyfin"`) and `core` (`namespace =
"dev.pschmitt.jellyfin.core"`) get separate, non-merging R classes. (2) The Android TV launcher
tile (`AndroidManifest.xml`'s `android:banner="@mipmap/ic_banner"`) resolves to core's
`mipmap-anydpi/ic_banner.xml` too, since `app/tv` never defines its own `mipmap/ic_banner`
override. Confirmed conclusively via `app:tv:assembleLibreRelease`'s R8 resource-shrinker report
(`app/tv/build/outputs/mapping/libreRelease/resources.txt`): the only `ic_banner`-named resources
listed as *reachable* are core's (`dev.pschmitt.jellyfin.core.R$drawable.ic_banner` from the
`WelcomeScreen.kt` reference, and the manifest's mipmap reference) - the `app/tv`-local file never
appears as reachable from anywhere, meaning R8 already silently strips it from real release
builds. **Both the actual Welcome screen and TV launcher tile have shown Scout correctly since the
main FINDROID-68 pass** - there was never a live pre-Scout mascot on screen; the earlier note
mistook an orphaned, shadowed file for the live asset. Deleted the dead file (`git rm`) rather than
redesigning something nothing displays; rebuilt `assembleLibreDebug` for both `:app:phone`/
`:app:tv` remotely to confirm the removal doesn't break anything.

**Handoff - resolved:**
- [x] `git tag v2.13.0` created (annotated) and pushed to `origin` - the original "more testing
  needed first" condition is satisfied by the port-fix re-verification above, and the user
  explicitly confirmed 2026-08-05 they wanted this tagged now.

**Concurrency gotcha found this session:** the remote build directory
(`~/devel/private/pschmitt/jollyfin-verify` on rofl-13/rofl-14) is only namespaced per
*worktree* - two sessions both building from the plain main checkout land in the identical
remote path and can corrupt each other's in-flight build (this session's first release-build
attempt on rofl-13 failed 3m33s in with "keystore file doesn't exist" after a concurrent,
unrelated `compileDebugAndroidTestKotlin` invocation showed up in the same directory mid-build).
Switching to rofl-14 side-stepped it cleanly. Worth remembering if a release build fails with a
file-existence error partway through for no obvious reason - check `ps` on the build host for a
second Gradle invocation in the same path before assuming the build itself is broken.

Status: **done**, 2026-08-05 - every FINDROID-68 handoff item from the prior session is resolved:
port fix rebuilt/verified, screenshots audited, Play Console listing confirmed to already exist,
and the TV banner "still pre-Scout" note turned out to be a dead-code false alarm (deleted, see
above) rather than a live bug. Tagged as `v2.13.0` per explicit user confirmation. Backup format
migration metadata is tracked separately as FINDROID-69.

## FINDROID-69: backup format versioning/migration metadata

Flagged as a "consider it, but don't spend time on it" aside during FINDROID-68 (rename); picked
up as a small follow-up since it was still open. `BackupEnvelope` already carried a `version: Int`
field, but nothing ever read it, and there was no record of which app build actually wrote a given
`.frb` file - so a future format change would have nothing to detect and migrate from except a raw
`SerializationException` on a hard schema mismatch.

- [x] `BackupEnvelope` (`data/.../backup/BackupData.kt`): added `appVersionName: String = ""`,
  `appVersionCode: Long = 0`, `packageId: String = ""`, all defaulted so backups written before
  these fields existed still decode unchanged.
- [x] `BackupManager.buildBackup()`: populates the three new fields from
  `context.packageManager.getPackageInfo(context.packageName, 0)` - reads `context` directly
  (already a constructor field) rather than the app/phone-only `AppVersionInfo` interface, since
  `BackupManager` lives in `data` and must also work for `app/tv` (which never binds
  `AppVersionInfo` - see `core/di/AppModule.kt`).
- [x] `BackupManager.readBackup()`: added a version guard - a backup with `version` higher than
  this build's `CURRENT_VERSION` (currently `1`, matching the field's own default - there's only
  ever been one format so far, nothing to migrate yet) now throws the new
  `UnsupportedBackupVersionException` with a clear "created by a newer version" message instead of
  falling through to whatever raw deserialize error would otherwise surface.
  `RestoreBackupViewModel.loadBackup()`'s existing broad `catch (e: Exception)` already surfaces
  `e.message` in-UI, so no new UI wiring was needed.
- [x] `BackupManager`'s `Json {}` instance: added `ignoreUnknownKeys = true` (every other `Json{}`
  in the repo already sets this; this one had been the sole exception) - lets a future field
  addition decode gracefully on an app version that predates it, instead of hard-failing on the
  unrecognized key.
- [x] Automated test coverage: added `data/src/test/.../backup/BackupDataTest.kt` (5 cases,
  mirroring `QrConfigCodecTest`'s existing style of testing codec logic directly rather than
  through Android-dependent classes) - round-trips the new fields, decodes a pre-existing backup
  missing them (defaults kick in), decodes a hypothetical future backup carrying a field this
  build has never seen (`ignoreUnknownKeys` holds), and checks
  `UnsupportedBackupVersionException`'s message both with and without a known writing-app
  version. Chosen over a manual on-device UI tap-through: `BackupManager`'s core logic (JSON
  codec + version guard) needs no Android framework dependencies to exercise directly, and a
  written test is permanent regression coverage instead of a one-off manual check - full
  `data`/`core` test suites plus `ktfmtCheck` still pass with it added.
- [ ] Not implemented (deliberately, per FINDROID-68's own reasoning): actual migration *logic*
  between format versions. There's only ever been one format, so there's nothing to migrate yet -
  this entry only adds the metadata + guard rail a future format change would need, not
  speculative handling for a version bump that doesn't exist.

**Why:** explicit ask during FINDROID-68, deferred there to keep that session scoped to the
rename; picked up once the rename's own handoff items were otherwise clear.
**How to apply:** when a real format change happens, bump `BackupEnvelope.version` and
`BackupManager.CURRENT_VERSION` together, and add the actual field-mapping/migration logic in
`restore()` gated on `envelope.version` - the guard added here only rejects backups *newer* than
what this build understands, it doesn't yet handle migrating an *older* envelope shape forward.

Status: **done**, 2026-08-05 - implemented and verified: `:data`/`:core` unit tests (including
the new `BackupDataTest`), `ktfmtCheck`, and `assembleLibreDebug` for both `:app:phone`/`:app:tv`
all pass on rofl-14. Not exercised via an actual on-device Settings > Backup UI round-trip
(deliberately - see the automated-test note above for why that wasn't the right tool here); the
version-guard's only untested edge is real migration logic, which doesn't exist yet by design.

## Backlog sweep, 2026-08-05

User asked to finish everything in this file, not just the FINDROID-68/69 rename family. Went
through every remaining `- [ ]` in the file above FINDROID-68 to check whether each is actually
still open or just stale bookkeeping. All of them are genuinely still open, and all of them are
blocked on something this session couldn't supply - listed here so "still open" reads as verified,
not skipped:

- **FINDROID-7** (dependency currency): reviewing which upstream Findroid dependency updates to
  selectively cherry-pick requires human judgment about which changes still make sense given how
  far this fork has diverged - explicitly not a mechanical task, own status line already says so.
- **FINDROID-43** (QR provisioning): TV-side export is unbuilt feature scope (a real TV UI would
  need to be designed, not just fixed), and the interactive scan/generate UX check needs an actual
  camera pointed at a second device's screen - not something adb/scripting can stand in for.
- **FINDROID-44** (remote configuration): TV-side support is the same kind of unbuilt feature scope
  as FINDROID-43's TV item, not a bug to fix.
- **FINDROID-45** (`findroid-cli`): the remote/local-download groups' end-to-end test needs a live
  Jellyfin server with real credentials, which isn't available in this environment.
- **FINDROID-54** (merge auto-download/remote-devices screens): px5's on-device verification is
  blocked by a pre-existing, unrelated `AEADBadTagException` keystore crash on that specific
  device, and the documented fix (uninstall+reinstall) wipes real user data - needs the user's
  explicit go-ahead per `AGENTS.md`'s own gotcha, not decided here. (Also consistent with this
  session's own instruction to prefer the Mi Pad 4 over px5 for testing.)
- **FINDROID-59** (delete downloads on remote rule removal): the cross-device verification needs a
  second real device with actual downloaded episodes talking to a live Jellyfin server - same
  missing-infrastructure blocker as FINDROID-45, just for a different flow.

Everything at or after FINDROID-60 was individually reviewed too (see each entry's own Status
line) and has no remaining `- [ ]` items other than the FINDROID-68/69 ones already closed above.

## FINDROID-70: bump compileSdk to 37

Renovate (see FINDROID-7's fix earlier today - `schedule:weekly` was blocking every PR from ever
being created) opened 16 dependency-update PRs. 12 merged clean. 4 (`androidx.hilt` 1.4.0,
`androidx.lifecycle` 2.11.0, `androidx.core` 1.19.0, `aboutlibraries` 15.0.4) all fail the same
way: each now requires `compileSdk 37` or later, and this project is still pinned to 36
(`platforms-android-36` is the only Android platform the flake currently provides - see
`flake.nix`). One shared blocker across all 4 PRs, not four separate problems.

- [x] Added `platforms-android-37-0` + `build-tools-37-0-0` to `flake.nix`'s Android SDK
  composition. Google's API 37 platform packages are versioned `37.0`/`37.1` (no bare `37`
  package exists, unlike 36) - empirically confirmed `37-0` (not `37-1`) is the one AGP's bare
  `compileSdk = 37` actually resolves to: the wrong choice fails with "Failed to install ...
  platforms;android-37.0 ... SDK directory is not writable" since Gradle tries (and fails, since
  the Nix store is read-only) to auto-install the missing platform at build time.
- [x] Bumped `Versions.COMPILE_SDK` 36 -> 37. Left `TARGET_SDK`/`MIN_SDK` at 36/28 untouched -
  those change runtime behavior and device compatibility, `compileSdk` only changes which APIs
  are available at compile time.
- [x] Rebuilt remotely on rofl-14: `assembleLibreDebug`/`assembleLibreRelease` for both
  `:app:phone`/`:app:tv`, `:data`/`:core` unit tests, `ktfmtCheck` - all pass. Only pre-existing
  deprecation warnings (`MasterKey`/`EncryptedSharedPreferences`, `LocalClipboardManager`,
  `java.util.Locale(String)`), no new errors. `nixfmt`/`statix` also clean on the `flake.nix`
  change itself.
- [x] Rebased and merged PRs #15/#16/#17/#21 (`androidx.hilt` 1.4.0, `androidx.lifecycle` 2.11.0,
  `androidx.core` 1.19.0, `aboutlibraries` 15.0.4) - all green once `compileSdk 37` landed on
  `main`, all 16 Renovate PRs merged. PR #16 needed a second manual rebase (real, one-line
  conflict this time - two version bumps landing on adjacent `libs.versions.toml` lines) since
  each squash-merge invalidated the diff of whatever else was still open on the same file.
- [x] Found and fixed a real CI regression right after: PR #12's `ktfmt` 0.26.0 -> 0.27.0 bump
  changed formatting rules more broadly than expected, and `ktfmtCheck` on `main` post-merge
  failed on 6 files across two passes of `ktfmtFormat` (5 first, then 1 more the first pass
  missed). Ran `ktfmtFormat` (mass-reformats 234 files repo-wide - whitespace/line-wrap only,
  semantically inert) and re-verified the full build/test/lint suite before considering this
  done - exactly the risk `AGENTS.md` already flags about ktfmt version drift, just triggered by
  Renovate bumping the *project's own* pinned version this time instead of a local tool mismatch.
- [x] Version bump: `APP_CODE` 51 -> 52, `APP_NAME` "2.13.0" -> "2.13.1", plus
  `fastlane/metadata/android/en-US/changelogs/52.txt`.

**Why:** direct user follow-up the same day, once the compileSdk-37 blocker on 4 Renovate PRs was
identified and documented.

Status: **done**, 2026-08-05 - tagged as `v2.13.1`.

## FINDROID-71: automated Play Store screenshot CI (Jellyfin-only for now)

Requested (2026-08-05): mirror the screenshot-automation CI the sibling `netbox-and-chill`
project (Nyetbox) built - see that repo's `.github/workflows/screenshots.yaml`,
`app/src/androidTest/kotlin/dev/pschmitt/nyetbox/StoreScreenshotTest.kt`, `ci/netbox/` fixture,
and `docs/screenshots.md`. Same shape here: a disposable, seeded backend + an
`reactivecircus/android-emulator-runner`-driven instrumented test capturing fastlane
`screengrab` output straight into `fastlane/metadata/android/en-US/images/`, matrixed over
phone/sevenInch/tenInch, with an opt-in `open_pr` job. Explicit scope for v1, per direct
instruction: **Jellyfin only** - no Sonarr/Radarr/Seerr screens, and no attempt to also cover
`app/tv` in the same pass.

Key difference from Nyetbox's NetBox fixture: per explicit instruction, ship the **baked Jellyfin
config as a committed fixture** (`ci/jellyfin/config/`) rather than scripting Jellyfin's setup
wizard + library scan fresh on every CI run - Jellyfin has no netbox-docker-style
`SUPERUSER_*`-env-var auto-provisioning, and its own onboarding wizard + metadata-provider-backed
library scan is slow and non-deterministic (external TMDB/OMDb calls) if run live in CI every
time. Baking once and shipping the result sidesteps both.

- [x] Source small, genuinely-CC video files, fetched (not committed) via
  `ci/jellyfin/fetch-media.sh`/`just jellyfin-fixture-media` - the official small **trailer**
  encodes for Big Buck Bunny (~3.9MB, iPhone encode) and Sintel (~4.4MB, 480p), both Blender
  Foundation/CC BY 3.0, plain downloads with no local transcoding. Deliberately *not* the full
  Caminandes/Pioneer One/etc. lineup the existing hand-captured screenshots show (likely captured
  against the public `demo.jellyfin.org` "Stable Demo" server previously) - two items is enough
  for a v1 Home + detail-screen journey, and trailers avoid needing to trim/re-encode a full
  film locally (heavy CPU work that has no business running on a developer workstation - first
  approach here did exactly that and got corrected mid-session). Poster art: Wikimedia Commons
  (the same blender.org-sourced, CC BY 3.0 images each film's Wikipedia article uses), no local
  image generation needed.
- [x] Media deliberately gitignored (`ci/jellyfin/media/`) rather than committed - video doesn't
  compress well in git history; `just jellyfin-fixture-media` reproduces it on demand, in CI and
  locally alike.
- [x] Baked the fixture: ran Jellyfin locally via `docker run jellyfin/jellyfin:10.11.11`,
  completed the startup wizard via its REST API (`POST /Startup/User` **first** - calling it
  before `/Startup/Configuration` 404s for reasons not fully root-caused, possibly a startup-race
  on a just-booted container - then `/Startup/Configuration`, `/Startup/RemoteAccess`,
  `/Startup/Complete`), added the Movies library pointed at `/media/movies` (matching CI's mount
  path exactly, `EnableInternetProviders: false` at the library level so a hypothetical future
  live re-scan never depends on TMDB), then ran an explicit per-item
  `POST /Items/{id}/Refresh?metadataRefreshMode=FullRefresh&imageRefreshMode=FullRefresh&replaceAllImages=true&replaceAllMetadata=true`
  - confirmed this is what actually triggers a real TMDB lookup regardless of the library's own
  `EnableInternetProviders` setting (that flag only gates *automatic* scans, not an explicit
  admin-triggered full refresh) - got real overviews/genres/posters/backdrops/logos for both
  items this way, better than the originally-planned local-NFO-only approach and no extra work.
  Exported the resulting `/config` (minus `log/`/`SQLiteBackups/`, ~5MB) as
  `ci/jellyfin/config-fixture/`. Verified end-to-end from a **fresh** copy (fresh Docker volume,
  `docker compose up`, zero setup API calls) that login + full metadata/images are immediately
  available - see `ci/jellyfin/README.md` for the full rationale and regeneration steps.
- [x] `ci/jellyfin/docker-compose.yml`: a `config-seed` init container copies the read-only
  `config-fixture/` into a named `jellyfin-config` volume once before `jellyfin` starts (keeps
  Jellyfin's runtime writes - SQLite WAL, scheduled-task state - out of the git-tracked fixture
  entirely, in CI's ephemeral checkout and for local use alike). `just jellyfin-fixture-up`/
  `-down` wrap it for local testing.
- [x] Added `testTag()`s to the phone Compose screens needed for automation - `app/phone` had
  **zero** existing `testTag()` usage before this, unlike Nyetbox's `e2e-*` convention this is
  modeled on. Tagged the minimum needed for the full first-run journey (Welcome has no tag, it's
  reached via `onNodeWithText("Continue")` instead - see below): `e2e-server-url`/
  `e2e-connect-button` (AddServerScreen), `e2e-username`/`e2e-password`/`e2e-login-button`
  (LoginScreen), `e2e-home-screen` (HomeScreen root), `e2e-item-card` (ItemCard, reused by every
  Home row), `e2e-movie-title` (MovieScreen), `e2e-settings-button` (HomeHeader's gear
  `IconButton` - its `Icon` has `contentDescription = null`, so unlike Nyetbox's equivalent
  settings action this needed a tag rather than a content-description wait target).
- [x] `app/phone/src/androidTest/java/dev/pschmitt/jellyfin/StoreScreenshotTest.kt` (new
  `androidTest` source set, plus `AnrDismissRule.kt`/`E2eScreenshot.kt` helpers ported from
  Nyetbox) + `fastlane/Screengrabfile`. Walks the **full** first-run setup flow every fresh
  install requires - Welcome → Servers (empty, "Add server" FAB) → AddServer → Users (empty,
  "Add user" FAB) → Login → Home (see `NavigationRoot.kt`; there is no way to jump straight to
  AddServer/Login) - then Home → tap an `e2e-item-card` → movie detail (waits on the
  `e2e-movie-title` tag and the "Play" content-description), then back to Home. For the dark
  variant: Home → `e2e-settings-button` → the "Appearance" category → "Theme" → "Dark" in the
  resulting dialog (a plain `SettingsSelectDialog` `LazyColumn` of clickable rows - not a
  `DropdownMenu` like Nyetbox's color-scheme picker, so no UiAutomator-fallback click was needed
  here), two system back-presses back to Home, repeat the Home/detail capture with a `_dark`
  suffix. Compiles clean (`:app:phone:assembleLibreDebugAndroidTest` built successfully on
  rofl-13), but **not yet run on an emulator** - the wait/click sequence is source-verified, not
  execution-verified; treat first CI/local runs as the real test of the click choreography, same
  as Nyetbox's own POC needed a few iterations to get flake-free.
- [x] `.github/workflows/screenshots.yaml`: `workflow_dispatch` with an `open_pr` boolean input,
  matrix over phone/sevenInch/tenInch (`pixel_2`/`Nexus 7`/`medium_tablet`, API 34
  `google_apis`), fetches fixture media then `docker compose up` the baked fixture, builds debug +
  androidTest APKs *before* booting the emulator (building while it's up starves it of CPU -
  confirmed the hard way in Nyetbox's own build-out) via `:app:phone:assembleLibreDebug
  :app:phone:assembleLibreDebugAndroidTest` (output paths confirmed via a real remote build:
  `phone-libre-x86_64-debug.apk` and `phone-libre-debug-androidTest.apk`, the latter has no ABI
  suffix), grants KVM access, `adb reverse tcp:8096 tcp:8096` so the emulator can reach the
  host-published fixture, runs `screengrab`, uploads artifacts, tears the fixture down
  unconditionally. Second `open-pr` job (gated on `inputs.open_pr` and the matrix job succeeding)
  flattens the three artifacts into the real fastlane directories and opens/updates a PR on a
  stable branch name for human review before merge - screenshots never get pushed straight to the
  live Play Console listing by this workflow itself. `actionlint` reports no issues.

**Why:** direct user request, explicitly modeled on Nyetbox's own proven implementation.
**How to apply:** see Nyetbox's `docs/screenshots.md` for the full rationale/gotchas write-up this
entry summarizes - read it before touching the workflow/test file, it documents several
non-obvious failure modes (ANR dialogs landing in captures, Compose clicks silently missing
occluded nodes, artifact-download nesting, etc.) already hit and fixed once there.

Status: **done**, verified end-to-end via real emulator/CI runs (several iterations, 2026-08-05,
fixing real click-choreography/PAT-push issues found along the way - see `fix(FINDROID-71)`
commits). Re-dispatched (`gh workflow run screenshots.yaml --ref main`, run `31170861481`,
2026-08-07) to confirm the test still passes after the same day's FINDROID-74 Settings reorg
(Appearance moved between cards several times) - all three matrix jobs (phone/sevenInch/tenInch)
passed without any test-tag fix needed, since `e2e-settings-appearance-category`
(`SettingsGroupCard.kt`) matches by string-resource id rather than screen position, so it survived
the reorg unmodified. If the click choreography ever needs fixes, `docs/screenshots.md`-style
debugging (readable failure-screenshot pulls, `waitFor*` on screen-unique facts) is already wired
in via `captureE2eScreenshot`/the workflow's failure-screenshot pull step.

## FINDROID-72: rename `Findroid*` model classes, `FindroidTheme`, `cli/findroid-cli`, and the CI
keystore/rbw entry to Jollyfin

FINDROID-68 deliberately left several things alone as "internal architecture naming, not this
app's own branding": the `Findroid*`-prefixed Kotlin model classes (`FindroidItem`,
`FindroidMovie`, etc.), `FindroidTheme` and the `Theme.Findroid`/`ShapeAppearance.Findroid*` style
resource names it wraps, and `cli/findroid-cli`'s own command name - the latter explicitly flagged
as "a decision rather than guessing." Direct user request (2026-08-06), given incrementally over
the course of the session: make those decisions - rename all of it to Jollyfin now, including (a
follow-up ask mid-task) the `findroid-ci*` filenames and the `Findroid CI Signing Keystore` rbw
vault entry itself. The "fork of Findroid" upstream-attribution language (README, PRIVACY,
`sync-upstream.yaml`, `jarnedemeulemeester/findroid` URLs) and every `FINDROID-N` ticket id are
still out of scope - not asked for this time either, same boundary FINDROID-68 drew.

- [x] `data/.../models/Findroid*.kt` (26 files) -> `Jollyfin*.kt`, renaming every declared type
  (`FindroidItem` -> `JollyfinItem`, etc.) and every `toFindroidX`/`findroidX`-style
  function/parameter name that referenced them, across every module (`data`, `core`, `modes`,
  `app/phone`, `app/tv`, `settings`, `player`) - not just the model files themselves. Done via a
  word-boundary-safe sweep (`Findroid(?!Theme)` -> `Jollyfin`, `findroid` -> `jollyfin`) over every
  tracked `.kt`/`.kts` file, so it also caught the same self-referential-prose and CLI-name
  mentions the next two items describe - those weren't separate passes in practice.
- [x] `FindroidTheme` -> `JollyfinTheme` (~128 files, mostly `@Preview` call sites) plus the style
  resources it wraps: `Theme.Findroid`/`Theme.Findroid.Player`/`Base.Theme.Findroid` ->
  `Theme.Jollyfin*` (`core`/`app/tv` `themes.xml`, both `AndroidManifest.xml`s),
  `ShapeAppearance.Findroid.Corner.*`/`ShapeAppearanceOverlay.Findroid.Image` ->
  `ShapeAppearance.Jollyfin.*` (`shape.xml`), `ThemeOverlay.Findroid.Amoled` -> `ThemeOverlay.
  Jollyfin.Amoled` (`values-night/themes.xml`, currently unreferenced but renamed for consistency).
  Explicitly confirmed with the user before doing this - FINDROID-68 had drawn this exact boundary
  twice already, so it warranted asking rather than assuming "even bigger" meant this too.
- [x] `cli/findroid-cli` -> `cli/jollyfin-cli`: the script's own header, `CONFIG_DIR`, `version`
  output, and self-update messaging, plus every live reference to the old path/name (`README.md`,
  `AGENTS.md`, `core/build.gradle.kts`'s asset-bundling task/class/output-dir names,
  `LocalControlServer.CLI_ASSET_NAME` and its comments, `LocalAccessViewModel`/
  `LocalAccessScreen`'s curl snippets, and the `local_access_*` strings in `core`/`settings`).
  `TODO.md`'s own historical `findroid-cli` mentions left as-is, same house style as FINDROID-68's
  historical sweep.
- [x] Follow-up ask mid-task: renamed the CI signing keystore's rbw vault entry itself -
  `Findroid CI Signing Keystore` -> `Jollyfin CI Signing Keystore` (`rbw set --name`), and its two
  attachments `findroid-ci.jks`/`findroid-ci-keystore.env` -> `jollyfin-ci.jks`/
  `jollyfin-ci-keystore.env` (rbw has no attachment-rename primitive, so: downloaded both under the
  new names to a scratch dir, uploaded them as new attachments via `rbw attachment create`, removed
  the old-named attachments via `rbw attachment rm`, then `shred -u` + `rmdir` the scratch copies).
  Updated `justfile`'s `build` recipe (rbw entry/attachment names, `.findroid-ci-tmp` ->
  `.jollyfin-ci-tmp`) and `.github/workflows/release.yaml`'s `fileName: 'findroid-ci.jks'` (a CI-
  runner-local temp filename only, unrelated to rbw - trivial rename, no coordination needed).
  Verified end-to-end: `just build-fetch --release` fetched the renamed rbw entry, signed
  successfully, and the resulting APK installed with `adb install -r` over the previous
  CI-signed release on all three attached devices with no `INSTALL_FAILED_UPDATE_INCOMPATIBLE` -
  confirms the signing cert is unchanged, only the vault entry/attachment names moved.
- [x] Incidental cleanup caught by the same sweep: stray self-referential "Findroid" code comments
  in `PvrDtos.kt`, `SonarrApi.kt`, `CalendarEntry.kt`, `CalendarRepository.kt`,
  `PvrDiskSpaceRepository.kt`, `SonarrSearchRepository.kt`, `SeerrRepository.kt`,
  `CalendarMatching.kt` (FINDROID-68's sweep grepped for `findroidplus`/`Findroid+`, not bare
  `Findroid`, and missed these) -> "Jollyfin". Also `QrCodecTest.kt`'s arbitrary
  `"findroid-qr-setup:"` test payload -> `"jollyfin-qr-setup:"` (not a real prefix anywhere, just
  sample data).
- [x] Build-verified remotely: `assembleLibreDebug` for `app/phone`+`app/tv` (after the model-class
  rename), then again after the `FindroidTheme`/style-resource rename together with
  `:data:testDebugUnitTest`/`:core:testLibreDebugUnitTest` - all green. `just lint` (remote, CI's
  pinned ktfmt) caught 6 files ktfmt wanted reformatted as a side effect of the renames; fixed via
  `just gradle rofl-13 ktfmtFormat` + manually `rsync`ing the reformatted files back (no
  local-ktfmt-vs-CI-ktfmt version mismatch risk, since this ran the actual pinned remote plugin,
  not nixpkgs' newer standalone binary) - `just lint` clean afterward. Built and deployed the
  release APK (post keystore-rename) to all three attached devices (ASUS phone, Mi Pad 4, px5) with
  no install errors.
- [x] Three locale `strings.xml` translations (`values-fi`, `values-et`, `values-cs-rCZ`) still
  said bare "Findroid" in `privacy_policy_notice` in *inflected/declined* form
  ("Findroidia"/"Findroidi"/"Findroidu" - partitive/genitive cases), missed by FINDROID-68's
  literal-string sweep. Initially flagged rather than fixed, pending a native speaker's call on
  correct declension of "JollyFin" - user said not to worry about grammatical correctness, so
  fixed by dropping the case ending entirely and using bare "JollyFin", matching the convention
  every other already-fixed inflected-language locale uses (German "Nutzung von JollyFin", Polish
  "z JollyFin", Russian "используя JollyFin" - none of them decline it either).

**Why:** direct user request, resolving the items FINDROID-68 explicitly deferred, expanded
mid-session ("we can do even bigger", the `FindroidTheme` FYI, explicit asks for the keystore
filename/rbw entry, then "just JollyFin, don't worry about it" for the three locale strings).
**How to apply:** see FINDROID-68 for the boundary this entry does still respect (upstream
attribution language, ticket ids).

## FINDROID-73: auto-trigger screenshot capture on a real tagged release

`screenshots.yaml` (FINDROID-71) was entirely manual (`workflow_dispatch` only). Direct user
request: fire it automatically off `release.yaml`'s real `vX.Y.Z`-tag path (not the rolling
"latest" prerelease, which republishes on every `main` push - that would make this an
~90min x3-device emulator job run on every commit instead of once per release). Requested for the
sibling `augh`/`nyetbox` projects too - see their own repos for the equivalent change.

- [x] Added an `actions: write` permission and a final "Trigger screenshot capture" step to
  `release.yaml`, gated on `steps.params.outputs.tag_name != 'latest'`, calling
  `gh workflow run screenshots.yaml --ref main -f open_pr=true`. Uses the default `github.token` -
  no PAT needed, since `workflow_dispatch` (unlike push/PR events) is explicitly exempted from
  GitHub's "events triggered by GITHUB_TOKEN don't start a new workflow run" restriction, per
  GitHub's own docs.
- [x] `open_pr=true` so the auto-triggered run lands as a reviewable PR (existing behavior from
  FINDROID-71's `open-pr` job) rather than only a build artifact nobody looks at.

**Why:** direct user request, same session as FINDROID-72.
**How to apply:** if this ever needs a different ref than `main` (e.g. if `screenshots.yaml` moves
to reading its own version off the just-pushed tag), update the `--ref` flag - currently pinned to
`main` since that's guaranteed to have the workflow file's `workflow_dispatch` schema.

Status: **done**, 2026-08-06. Not yet verified by an actual tag push through the full pipeline
(the trigger step didn't exist yet when v2.13.3 was tagged) - the next real release will be the
first live test.

Status: **done**, 2026-08-06 - build/test/lint-verified remotely and deployed to all three
attached devices; not yet tagged as a release build.

## FINDROID-74: Profiles follow-ups (QR profile/service scoping, Seerr renaming, Settings reorg)

Requested (2026-08-07), immediately after v2.14.0 (Profiles) shipped:

- [x] Verify the "setup another device" QR pairing flow (FINDROID-43) still works correctly now
      that Sonarr/Radarr/Seerr config is per-profile instead of a single global singleton - it was
      built and tested before Profiles existed. Read through `QrConfigManager`: `buildEnvelope()`
      already resolves through the profile-aware `PvrConfigResolver` (not stale globals), and
      `applyEnvelope()` already calls `ProfileMigrationRunner.reconcileAfterExternalRestore()` - so
      the envelope mechanics themselves are correctly profile-aware (this was apparently already
      wired correctly as part of the original Profiles work, not something broken by it).
  - [x] Found a **real, separate bug** while checking this, not a QR-specific one: `HomeViewModel`,
        `ShowViewModel`, `SeasonViewModel`, `HomeLayoutSettingsViewModel`, and
        `QueueStatusScheduler` all still read the pre-Profiles global
        `appPreferences.sonarrEnabled`/`radarrEnabled`/`seerrEnabled` directly for their
        "should I do this at all" gating checks, even though most of them *also* already inject the
        profile-aware `PvrConfiguration`/`PvrConfigResolver` for other checks in the same file (a
        partial migration) - meaning after switching profiles or overriding a service per-profile,
        these five spots would keep reflecting whatever the stale global flag says instead of the
        active profile's real state. Fixed: swapped all five to
        `PvrConfiguration.isXConfigured()` (already injected in four of the five;
        `QueueStatusScheduler` - a plain `object`, not Hilt-injected - now takes a
        `PvrConfigResolver` parameter from its one call site in `app/phone/BaseApplication.kt`).
        Note `QueueStatusScheduler.schedule()` still only runs once at process startup, so a
        profile switch mid-session won't re-evaluate it until the next cold start - a smaller,
        separate follow-up if that turns out to matter in practice.
  - [x] Verified: remote `ktfmtCheck` and `:app:phone:compileLibreDebugKotlin`/
        `:app:tv:compileLibreDebugKotlin` both green on rofl-13. Not yet re-verified on a physical
        device.
- [x] Let the QR export flow choose which profile's config gets encoded, instead of always
      encoding whichever profile happens to be active. Added a profile picker to the phone export
      screen; the selected profile now drives service availability, base URL prefill, Jellyfin
      account defaults, and `QrConfigManager.buildEnvelope(profileId = ...)` resolution through
      `PvrConfigResolver.resolveConfigForProfile()`. Verified with remote ktfmt, phone/TV Kotlin
      compilation, and core/data unit tests.
- [x] Add toggles to the QR export flow for whether to include each of Sonarr, Radarr, and Seerr in
      the generated envelope, rather than always bundling all three. Turned out this already
      existed: `QrExportState.includeSonarr/includeRadarr/includeSeerr` +
      `QrExportScreen.kt`'s per-service switches were already wired end to end. No change needed -
      correcting this item's status so the next pass doesn't duplicate it.
- [x] Sweep remaining "Jellyseer"/"jellyseer" spellings (strings.xml, identifiers, comments) and
      rename to "Seerr" - the rest of the codebase already uses `PvrService.SEERR`/"Seerr". First
      pass renamed the two user/dev-facing text hits (`settings_category_profiles_summary`,
      a doc-comment in `ProfileSelectionBottomSheet.kt`) and deliberately left the persisted
      preference/credential-store key *string values* alone, since they were already documented as
      locked in for backward compat. The user then clarified they want the persisted key names
      renamed too, accepting that this needs a real migration - done as a second pass:
  - [x] `PvrCredentialKeys.SEERR_API_KEY` now `"seerr_api_key"` (was `"jellyseerr_api_key"`, kept as
        `LEGACY_JELLYSEERR_API_KEY` for migration reads only).
  - [x] `AppPreferences.seerrEnabled`/`seerrBaseUrl` now back onto `"pref_pvr_seerr_enabled"`/
        `"pref_pvr_seerr_base_url"` (were `"pref_pvr_jellyseerr_*"`).
  - [x] New `ProfileMigrationRunner.migrateLegacySeerrKeyNames()`: copies any value still under an
        old key name to its new name, for both `AppPreferences` (plain `SharedPreferences`) and
        `SecureCredentialStore`. Naturally idempotent (copies only when the new key is absent and
        the old one isn't) rather than flag-gated, so no new `AppPreferences.*Migrated` bool was
        needed. Called from two places: `BaseApplication.onCreate()` (both phone/tv) ahead of
        `profileMigrationRunner.run()` - since `run()`'s one-time backfill itself reads
        `seerrEnabled`/`seerrBaseUrl` and would otherwise miss an old-named value on a real upgrade
        - and at the top of `reconcileAfterExternalRestore()`, so **restoring an older backup/QR
        export that still has the old key names in its payload keeps working**: `applyEnvelope()`/
        `restorePreferences()` write the envelope's keys verbatim (old names, since that's what's
        baked into an old file), then this migration step runs immediately after and copies them
        into the new names before the rest of the reconcile logic reads via the new ones - same
        "copy before anything reads the new name" ordering the original Profiles migration bug fix
        established for the secret-loss case earlier this session.
  - [x] Verified: remote `ktfmtCheck`, `:app:phone:compileLibreDebugKotlin`/
        `:app:tv:compileLibreDebugKotlin`, and `:core:testLibreDebugUnitTest`/
        `:data:testDebugUnitTest` all green on rofl-13. Not yet re-verified on a physical device
        with a real old-format backup file (no such file was available in this pass).
- [x] Reorganize Settings grouping/ordering again - split the oversized Appearance screen into
      focused Appearance and Player sections, and split Advanced into Integrations (PVR sync plus
      new-item notifications), Network (request/connect/socket/PVR-search timeouts), and Advanced
      (image cache). The top-level order is now Account, Appearance, Player, Downloads,
      Integrations, Network, Data, Advanced, About. Preferred media languages moved with Player;
      app language stayed with Appearance. Updated summaries, MPV navigation, and the screenshot
      test tag. Verified with remote `ktfmtCheck`, phone/TV Kotlin compilation, and core/data unit
      tests; not physically tested.
- [x] Refine the Settings root presentation after trying the first grouping: combine Account,
      Appearance, and Downloads into one titleless card; move titleless Advanced immediately above
      titleless About at the bottom. Keep the focused Player, Integrations, Network, and Data
      sections between those cards. Verified with remote `ktfmtCheck`, phone/TV Kotlin compilation,
      and core/data unit tests; not physically tested.
- [x] Remove redundant outer titles from the remaining single-entry Settings root cards (Player,
      Integrations, Network, and Data); their cards already contain the corresponding titled row.
- [x] Move Player, Integrations, and Network under the Advanced Settings screen, keeping Advanced
      immediately above About at the root and preserving nested navigation paths. Verified with
      remote `ktfmtCheck`, phone/TV Kotlin compilation, and core/data unit tests; deployed to the
      Mi Pad 4 and PX5.

Status: **done** (2026-08-07) - less frequently used settings consolidated under Advanced and
deployed.

## FINDROID-75: Refresh and rename the privacy policy

- [x] Rename the root privacy policy from `PRIVACY` to `PRIVACY.md`.
- [x] Rewrite the policy for JollyFin, covering direct configured-service traffic, local storage,
      backups, permissions, external links, and the absence of developer-side analytics or
      automatic crash reporting.
- [x] Update the in-app privacy-policy URLs and remove the remaining Findroid branding from the
      privacy-policy notice.

Status: **done** (2026-08-09) - policy and in-app link updates implemented; all 36 Android string
XML files parsed successfully, `git diff --check` passed, and stale upstream privacy URLs were
removed.

## FINDROID-76: Resume battery-saver-paused downloads and show their cause

- [x] Resume downloads marked by the battery-saver pause flow when power saver turns off, including
      after the app process was restarted.
- [x] Preserve the distinction between battery-saver pauses and user pauses in the download model
      and show a battery-saver icon beside the paused status.
- [x] Register the power-save broadcast dynamically so Android delivers it on real devices.
- [x] Verify with remote formatting, compilation, and unit tests.
- [x] Bump the patch release from 2.14.2 (57) to 2.14.3 (58), including the English changelog.

Status: **done** (2026-08-11) - `ktfmtCheck`, `data`/`core` unit tests, and the signed
`assembleLibreRelease` build passed remotely on rofl-14; a 2.14.4 (59) test/deployment build was
installed without uninstalling the apps. On px5, a real 2.2 GiB download changed to
`Paused` with the battery-saver indicator when saver was enabled, resumed with live progress when
it was disabled, and a manually paused download remained paused across the same toggle. Temporary
test media was cancelled afterward; both devices were restored to saver OFF and normal charging.

## FINDROID-77: Customize the navigation bar

- [x] Add Appearance settings for reordering, hiding, and restoring navigation destinations without
      changing the current default layout.
- [x] Support Home, Media/libraries, Downloads, Calendar, Favorites, Next Up, and Settings as
      configurable destinations, while retaining dynamic library availability and offline-mode
      behavior.
- [x] Keep the persisted format forward-compatible with future pinned show/episode destinations.
- [x] Verify remote production compilation and install on the connected ASUS device, Mi Pad 4,
      and Pixel 5.
- [ ] Run the separate remote ktfmt and unit-test checks.

Status: mostly done (2026-08-12) - production APK 2.14.4/versionCode 59 built on `rofl-14` and
installed successfully on all three attached devices; ktfmt and unit-test checks remain.

## FINDROID-78: Improve custom navigation-bar search and presentation

- [x] Search custom navbar destinations as the user types and show cover art in result rows.
- [x] Give pinned media destinations editable short labels, with sensible acronym defaults.
- [x] Render pinned cover art in the navbar with a generic-icon fallback.
- [x] Backfill cover art for pins created before artwork persistence was added, while preserving
      their existing custom labels.
- [x] Add a per-item setting to show or hide cover art in the navbar.
- [x] Show a live visual preview of the configured navbar on the settings page.
- [x] Verify formatting, compilation, and relevant tests remotely.
- [ ] Re-run the pinned formatter, phone compilation, and unit tests after the per-item cover-art
      toggle and navbar preview changes when a remote build host is reachable.

Status: in progress (2026-08-13) - per-item cover-art controls and the live navbar preview are
implemented; remote verification is pending because rofl-13 and rofl-14 are currently unreachable.

## FINDROID-79: Fix missing offline badge on Next Up and move it bottom-right

- [x] Fix `JellyfinRepositoryImpl.getNextUp()` dropping the local-download `sources` merge (it
      called `toJollyfinEpisode` without the `database` param, unlike every other home-feeding
      query), which made `isDownloaded()` always return false for Next Up episodes and hid the
      downloaded badge there even when the episode was on disk.
- [x] Move the icon-only downloaded badge (`ItemCard`) from the top-right badge row to the
      bottom-right corner of the poster, per user preference; the queue/played/count/new badges
      stay top-right.
- [x] Verify formatting (`just lint`), `data`/`core` unit tests (`just test`), and phone module
      compilation remotely on rofl-13.
- [x] Build and install a signed release APK on the ASUS phone, Mi Pad 4, and px5.
- [x] Bump the patch release from 2.14.3 (60) to 2.14.4 (61), including the English changelog.

Status: **done** (2026-08-13) - `just lint`, `just test`, and
`:app:phone:compileLibreDebugKotlin` all passed on rofl-13; a signed
`assembleLibreRelease` build was installed on all three devices via adb.

## FINDROID-80: Show the offline badge on show posters too (e.g. "Latest Shows")

- [x] `JollyfinShow.sources` is always empty (no single file backs a whole series), and the shared
      `BaseItemDto.toJollyfinItem` dispatcher never passed `database` through to `toJollyfinShow`
      in the first place, so `isDownloaded()` could never be true for a show poster - "Latest
      Shows" and any other show-poster row never showed the badge even when episodes were
      downloaded.
- [x] Add `JollyfinShow.hasDownloadedEpisodes`, computed via a batched
      `getEpisodesByShowId`/`getSourcesForItems` lookup (same pattern as
      `ShowViewModel.downloadsSizeBytes`), instead of repurposing `sources`/`isDownloaded()` -
      `ItemButtonsBar` reads `item.isDownloaded()` directly on the show item to decide between the
      bulk-download flow and a single "Delete download" button, so redefining `sources` for shows
      would have broken that screen.
- [x] Thread `database` through `toJollyfinShow` (both the online and offline-DB-row mapping
      functions) and the `toJollyfinItem` dispatcher's `SERIES` branch.
- [x] `ItemCard` now badges a poster when either `item.isDownloaded()` or (for shows)
      `hasDownloadedEpisodes` is true.
- [x] Verify formatting (`just lint`), `data`/`core` unit tests (`just test`), and phone module
      compilation remotely on rofl-13.
- [x] Bump the patch release from 2.14.4 (61) to 2.14.5 (62), including the English changelog.

Status: **done** (2026-08-13) - `just lint`, `just test`, and `:app:phone:compileLibreDebugKotlin`
all passed on rofl-13. Scoped to the phone app's home dashboard, matching what was reported; the TV
app's separate `ItemCard`/`isDownloaded()` usage was left untouched.

## FINDROID-81: Fix missing offline badge on "Latest Shows" episode thumbnails

- [x] The server's `Latest` endpoint returns Episode items (not Series) for a TV library's "Latest"
      row by default, so FINDROID-80's `SERIES` branch fix didn't cover what's actually shown
      there. The `toJollyfinItem` dispatcher's `EPISODE` branch had the same bug as FINDROID-79's
      `getNextUp` (dropped `database` when calling `toJollyfinEpisode`), so `isDownloaded()` stayed
      false for every episode reached through this shared dispatcher - "Latest Shows" and anything
      else built on `getLatestMedia`/`toJollyfinItem` for episodes.
- [x] Pass `serverDatabase` through the `EPISODE` branch, matching `MOVIE`/`SERIES`.
- [x] Verify formatting (`just lint`), `data`/`core` unit tests (`just test`) remotely on rofl-13.
- [x] Bump the patch release from 2.14.5 (62) to 2.14.6 (63), including the English changelog.

Status: **done** (2026-08-13) - `just lint` and `just test` passed on rofl-13.

<!--
Entries above use the FINDROID-N prefix from before the Jollyfin rename (see FINDROID-72) and
keep it as a historical record - they are not renumbered. Entries below use JF-N, starting at 82.
See AGENTS.md's "Task tracking" section.
-->

## JF-82: Refresh pending PVR downloads immediately

- [x] Make Home pull-to-refresh fetch the current Sonarr/Radarr queue instead of waiting for the
      background poll.
- [x] Keep queued and importing PVR entries visible in Home's "Pending downloads" row, not just
      entries currently transferring.
- [x] Verify formatting, compilation, and relevant tests remotely.

Status: **done** (2026-08-19) - remote `ktfmtCheck`, `data`/`core` unit tests, and
`:app:phone:compileLibreDebugKotlin` all passed on rofl-13.

## JF-83: Fix "Pending downloads" and Calendar tab vanishing after backup restore

- [x] `BackupManager.dumpPreferences()`/`dumpSecrets()` only exported the resolved API key per PVR
      service - `enabled`/`baseUrl`/HTTP headers/basic-auth still came from the dead legacy
      `AppPreferences` fields nothing writes to post-Profiles, so a restore always looked
      "configured" with a working key but `enabled=false`/`baseUrl=null`, silently dropping
      "Pending downloads" and PVR polling/calendar. Now resolves the live `PvrClientConfigFull` per
      service (mirroring `QrConfigManager.putPvrFields()`) instead.
- [x] `ProfileMigrationRunner.reconcileAfterExternalRestore()` correctly detected a dangling
      `currentProfileId` pointing at the source device's stale profile id, but persisted the fix via
      `AppPreferences.setValue()`'s async `apply()` - which races the immediate
      `Runtime.getRuntime().exit(0)` process restart right after restore and can be silently
      dropped, leaving `currentProfileId` stale again on the very next cold start. Switched that
      write (and `profilesMigrated`) to a synchronous `commit()`, matching the pattern already used
      elsewhere in this method for the same reason.
- [x] Verified end-to-end on a real device (Zenfone 10, wired adb): configured Sonarr/Radarr/Seerr
      and a custom navbar order against a disposable local Jellyfin fixture, backed up, wiped the
      app (uninstall/reinstall) to simulate a fresh device, restored, and confirmed both "Pending
      downloads" and the Calendar tab reappeared exactly as configured - inspected the on-device
      SQLite DB and SharedPreferences directly to confirm `currentProfileId` now matches the
      recreated main profile.
- [x] Verify formatting, compilation, and relevant tests remotely.
- [x] Bump the patch release from 2.14.7 (64) to 2.14.8 (65), including the English changelog.

Status: **done** (2026-08-21) - remote `ktfmtCheck`, `data`/`core` unit tests, and
`:app:phone:compileLibreDebugKotlin`/`:core:compileLibreDebugKotlin` all passed on rofl-13; verified
live on a Zenfone 10 over wired adb.

## JF-84: Ignore incomplete local downloads during playback

- [x] Ignore `.download` and missing/empty local files when selecting a playback source, so a stale
      local database row falls back to the server stream.
- [x] Add a regression test covering an incomplete local source alongside a valid remote source.
- [x] Verify formatting, compilation, and relevant unit tests remotely.

Status: **done** (2026-08-22) - remote player-module tests, repository `ktfmtCheck`,
`data`/`core` unit tests, and `:app:phone:compileLibreDebugKotlin` all passed on rofl-13.

## JF-85: Move Season delete-download action into the overflow menu

- [x] Remove the primary "Delete download" button from the Season view.
- [x] Add the same action to the Season view's overflow menu.
- [ ] Verify the action and confirmation flow on a real device.

Status: implementation complete; device verification pending (2026-08-22).

## JF-86: Show newest seasons first

- [x] Reverse the season ordering in the Show view so the most recent seasons appear first.
- [ ] Verify the ordering on a real device.

Status: implementation complete; device verification pending (2026-08-22).

## JF-87: Bump the patch release to 2.14.9

- [x] Bump `Versions.kt` from 2.14.8 (65) to 2.14.9 (66).
- [x] Add the English changelog for version code 66.
- [ ] Run CI before tagging and push the `v2.14.9` tag.

Status: waiting for CI before tagging (2026-08-22).
