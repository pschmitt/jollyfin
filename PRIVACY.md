# JollyFin Privacy Policy

**Effective date:** 2026-08-09

JollyFin is a local-first Android and Android TV client for Jellyfin. This policy describes the
information handled by JollyFin (`dev.pschmitt.jollyfin`).

## Information we collect

JollyFin does not collect personal information for the developer, and does not sell or share your
information with the developer or unrelated third parties. The app has no advertising, analytics,
tracking, or automatic crash-reporting service.

JollyFin connects directly to the Jellyfin server and any optional Sonarr, Radarr, or Seerr server
you configure. Those services receive the requests and data needed to provide the features you use,
such as authentication, library and media requests, playback state, downloads, and API requests.
JollyFin does not send this information to the developer. The services you configure have their own
privacy policies and terms.

## Information stored on your device

JollyFin stores connection details, saved accounts, preferences, cached metadata, playback state,
download records, auto-download rules, and downloaded media on your device. This information stays
on the device unless you use a JollyFin feature to connect to a configured service, export a backup,
or share it.

API keys for optional Sonarr, Radarr, and Seerr integrations are stored using Android Keystore-backed
encrypted preferences. JollyFin's Android system backup is enabled, so your device or backup provider
may back up app data according to its own settings.

JollyFin can create manual or automatic backups in a folder you choose. Backups may contain saved
servers, logins, settings, auto-download rules, downloaded-item metadata, and optional integration
API keys. You can protect a backup with a password; you are responsible for the destination and any
copies of the backup.

The camera is used only when you explicitly use QR-code provisioning. Files and folders are read only
when you explicitly choose them for import, backup, restore, or sharing.

## External links

The app may open GitHub, this privacy policy, and other external links when you explicitly tap them.
Those services may collect information under their own privacy policies. JollyFin does not control or
receive information collected by those services.

## Changes and contact

We may update this policy when the app's behavior changes. The current version is always available
in the [JollyFin repository](https://github.com/pschmitt/jollyfin). Questions or privacy concerns
can be reported through the [GitHub issue tracker](https://github.com/pschmitt/jollyfin/issues).
