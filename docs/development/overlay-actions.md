# Overlay and action safety

## Ownership and permissions

- `TriggerEdgeView` detects a deliberate inward swipe from the left, right, or bottom; `PieOverlayService` owns trigger, pie, folder and search windows; `PieMenuView` draws and hit-tests the radial menu. `ActionExecutor` dispatches non-overlay actions and `PieAccessibilityService` performs user-selected global actions.
- Check `Settings.canDrawOverlays` before attaching triggers. Idle edge indicators must remain transparent; show configuration preview only while `MainActivity` is resumed. Use `TYPE_APPLICATION_OVERLAY` and keep gesture windows non-focusable; use non-touch-modal flags where appropriate. A keyboard picker may temporarily take focus without making the gesture overlay focusable.
- Accessibility is for the configured cross-app action, not inspecting unrelated screen contents. Keep window-content retrieval disabled unless a separately justified feature requires it; never read or log unrelated screen content. If the accessibility service is unavailable, explain that the user must enable it rather than silently claiming the action ran. Guard API-dependent global actions.

## Gesture contract

- Forward the complete gesture after activation, including MOVE, UP and CANCEL. Translate screen coordinates to the pie view's local coordinates for both anchor and pointer; drawing and hit-testing must share geometry, including display cutouts and edge orientation.
- UP may execute one hovered action; CANCEL must only dismiss. Keep a usable neutral center, stable visible sector boundaries, and clear hover beyond the outer drawn ring. Do not select invisible rings. Keep exposed deadzone, geometry, timing and icon settings independent; invalidate icon caches when visual settings change.
- A secondary action fires once at its configured hold timeout, dismisses immediately, and latches the gesture so its later release cannot also execute the primary action. Cancel the hold on meaningful movement beyond touch slop or a selection change; reset the latch only for a new gesture.
- Bound the lifetime of the full-screen menu and clean up timers and windows on UP, CANCEL, timeout, screen-off, service shutdown, and exceptions; restore parent touch interception if used. Perform window operations on the main thread. Avoid treating menu dismissal as folder deletion.
- Settings/search controls must use the same centers for drawing and hit-testing, remain on screen opposite the active edge, and be excluded from both when disabled. Preserve existing defaults when adding persisted visibility settings.
- Recent app controls appear between search and settings, with the same drawing and hit-test geometry. Exclude apps already reachable from rings, secondary actions and nested folders using the same mapping as App Suggestions; invalidate stale results when mappings change. Load usage and icons off the UI thread, retain only an in-memory snapshot, and do not change a control under an active selection. Zero disables them; Usage Access is required.

## Pickers and configuration

- Folder selections resolve stable IDs; show a distinct folder indicator and lightweight picker. App search uses launcher activities (`ACTION_MAIN`/`CATEGORY_LAUNCHER`) and the corresponding manifest `<queries>` declaration. Filter as input changes; launch only after explicit selection and handle removal between listing and launch.
- Load app metadata and icons off the UI thread, use bounded caches, and cancel recycled-row and dismissed-picker work. Only the temporary search window needs keyboard focus: request the IME after window focus and dismiss on screen-off and service teardown. Do not assume keyboard, resizing, Back or focus restoration works on a device without testing.
- `PreferencesManager` persists configuration; keep missing-setting defaults behavior-preserving and update the service promptly after configuration changes. Usage suggestions are opt-in and on-device; do not broaden accessibility collection to implement them.

## Verification and known source gaps

- Prefer `./gradlew assembleDebug`; skip lint and device/emulator testing unless specifically requested. If device acceptance is authorized, check each edge and cutouts, drawn-vs-hit geometry, center cancellation, UP versus CANCEL, single secondary execution, folder/search selection, focus/IME restoration and cleanup after screen-off. Report what was not exercised.
- Current `PieMenuView` cancels the hold on hover/selection change, not on all movement beyond touch slop; its selection tolerance and ring fallback can extend beyond strictly drawn sectors. Treat the contract above as a safety target, not a claim that every edge case is already implemented. Current worktree may contain in-progress search/control changes; inspect it before changing or describing behavior.
