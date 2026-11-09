# Build, updates and releases

Read this before building, delivering or publishing PieControl. Inspect the live worktree, `app/build.gradle.kts`, manifest, updater and current release; preserve unrelated changes. Use the generic `android-release-automation` skill where its contract fits, or the generic GitHub binary-release workflow when it does not. Do not invent a manifest or private-site profile for this app.

## Updater contract

The currently inspected `AppUpdateManager.kt` queries the latest GitHub release and expects an APK asset named exactly `piecontrol.apk`. It extracts the version code from a standalone release-body line of the form:

```text
<!-- piecontrol-version-code:N -->
```

Replace `N` with the actual new integer version code. The marker must match the packaged APK's Gradle version; missing or malformed markers and absent assets can prevent update discovery. Verify the current updater implementation and live release rather than assuming this contract remains unchanged.

## Build and verification

- A focused build is `./gradlew assembleDebug`; inspect the final named APK with `aapt` and `apksigner` for package, version, ABI where relevant and signing-certificate continuity against the intended prior APK. Debug signing alone does not establish debuggability. Verify exact artifact readability and digest after copy or rename.
- An update cannot be assumed installable if the signing identity changes. Do not inspect, copy or request private keystores. Preserve the user's data and never uninstall merely to bypass a signer mismatch.
- A publish request requires the reviewed source and version, an appropriate exact tag and release notes containing the marker, and the intended APK asset. After authorized publication verify the exact release metadata and full public APK download against the built hash. Never repeat an ambiguous upload or publication without first inspecting remote state.
- Device behavior is not established by a build, APK metadata or a release listing. Perform real device behavior tests only when explicitly authorized; installation authorization permits only minimal install read-back. State gaps separately from publication or installation results.
