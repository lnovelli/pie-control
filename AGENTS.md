# PieControl agent guide

- Inspect the working tree and current source first; preserve unrelated user changes. `README.md` covers features and the debug build.
- For edge triggers, radial geometry, gestures, action execution, accessibility, or overlay pickers, read [overlay and action safety](docs/development/overlay-actions.md).
- For usage access, app recommendations, scheduling, notifications, or backup rules, read [App Suggestions](docs/development/app-suggestions.md).
- For APK builds, updates or releases, read [build and release](docs/development/build-release.md) and the generic `android-release-automation` skill.
- For code changes, prefer a focused `./gradlew assembleDebug`. Do not run lint, unit suites, emulator tests, or on-device behavioral probes by default; only run device behavior tests when explicitly requested. A build does not verify gestures or device behavior.
