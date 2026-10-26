# Privacy and permissions

App Delay has no account, analytics service or app-operated backend. The main manifest declares no `INTERNET` permission. This does **not** mean all data is excluded from OS-managed cloud services.

## What the app uses

- **Accessibility:** window-state events and foreground package information determine which app is active. The service declares `canRetrieveWindowContent`, but does not request all-interactive-window tracking; current enforcement reads foreground package information, including from the active window root. Android grants a broader sensitive capability than a simple app-usage counter. The app is not designed to record message text or screen contents.
- **Display over other apps:** lets the pause/block screen open over covered apps and shows the countdown bubble.
- **Countdown bubble:** optional draggable overlay on covered foreground apps with a timed interruption or opening limit. No notification permission is requested.
- **Installed app queries:** launchable apps are queried for the app picker, rather than requesting unrestricted package visibility.
- **Local storage:** Room holds configuration, runtime usage state and 14 days of per-rule daily statistics (screen time, openings, extensions, "Not now" choices); preferences include warning settings. No account is required.

Implementation references: [`AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml), [accessibility configuration](../../app/src/main/res/xml/accessibility_service_config.xml), and [`PauseAccessibilityService.kt`](../../app/src/main/kotlin/com/androidpause/enforcement/PauseAccessibilityService.kt).

## Backup, transfer and export

[Android extraction rules](../../app/src/main/res/xml/data_extraction_rules.xml) allow cloud backup and device transfer, including configuration and runtime database state. Whether backup or restoration occurs depends on your OS, device and backup settings. Review your Android/Google/Samsung backup settings if you do not want this data backed up. No promise of device-only storage is made.

Manual JSON exports include profile names, selected package names, windows, allowances, messages and warning preferences. They exclude runtime usage, database identities and system permission grants. The selected document provider may itself be cloud-backed. Exports are plaintext personal configuration, not encrypted backups: choose a trusted location and do not attach them to public issues without reviewing/redacting them.

Import replaces configuration after confirmation and resets usage. Uninstalling removes app-local data, but does not remove exported files or necessarily erase OS backup copies. Manage those separately through their storage/backup provider.

Debug builds and diagnostic logs should also be treated as potentially sensitive; inspect and redact logs before sharing them.
