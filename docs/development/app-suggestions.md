# App Suggestions: usage analysis

Read this before changing usage access, app suggestion analysis, the App Suggestions tab, scheduling, notifications or backup rules. Current implementation lives in `app/src/main/java/com/ludo/piecontrol/usage/`; inspect the current code and platform behavior before changing it.

## Behavior and privacy

- This opt-in, on-device feature suggests launchable apps not already reachable from ring actions, nested folders, shortcuts or secondary actions. Exclude PieControl and the home launcher. Recheck current mappings before rendering, cap and deterministically sort results, and never modify a ring automatically.
- Count foreground *entries*, not time spent: `ACTIVITY_RESUMED` on Android Q+ and `MOVE_TO_FOREGROUND` on older versions. A package transition counts; an activity transition within the same package does not. Reset the prior package at screen-off/keyguard events when available and group events by local calendar day.
- Check Usage Access with `AppOpsManager.OPSTR_GET_USAGE_STATS` before scheduling and querying, expose a settings link, and handle revocation between check and query. Use narrowly scoped launcher package queries rather than `QUERY_ALL_PACKAGES` when sufficient.
- Preserve user-configurable defaults: feature and alerts off, daily scan, seven-day lookback, five visits across three distinct days. Interval choices are daily, two days, three days and weekly; lookback choices are 3, 7, 14 and 30 days. Clamp the active-day minimum to the window and display actual criteria.
- Keep results and required state on-device; exclude usage preferences from cloud backup and device transfer. Do not send usage data elsewhere. Suggestions remain visible when muted; muting only suppresses that app's notification. Migrate legacy dismissed IDs to mutes instead of hiding entries.
- The dedicated App Suggestions tab precedes More. A suggestion tap launches only that exact package; handle disabled/missing apps. The separate accessible mute control must not also trigger the row click.

## Scheduling and notifications

- Use a unique one-time immediate scan on enable and meaningful criteria/window changes, and unique periodic work with an initial delay when an immediate scan is scheduled. Update/cancel work on settings changes, disablement and permission revocation; periodic cadence is best effort, not an exact time.
- Notifications are opt-in. Request permission when alerts are enabled, check system/channel availability, use generic text, deep-link to the tab, and record a candidate as notified only after a successful post. Muted candidates remain in the UI.
- Source gap: the inspected worker checks app-wide notification enablement and runtime permission, but not whether the specific channel is blocked; `notify()` may return without an exception and candidates may then be marked notified even though the channel suppressed display. Treat channel-aware notification accounting as a requirement to verify, not an established implementation guarantee.

## Verification

Check manifest permissions and launcher queries, backup exclusions, access-revocation handling, work cancellation/criteria propagation, notification permissions and channel, and the deep-link target. Use a focused app build and `git diff --check` as appropriate; a build does not verify runtime or device behavior.
