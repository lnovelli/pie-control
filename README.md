# PieControl for Android

A modern, fluid Android navigation gesture app. Swipe inward from any screen edge to reveal a customizable semicircle ring of actions organized across up to three concentric layers.

## Features

- **Side Edge Swipe Activation**: Trigger zones positioned on the left, right, or bottom screen edges. Configurable thickness, height, and vertical position. Requires intentional inward swipe movement towards the screen center rather than accidental static taps.
- **Up to 3 Semicircle Layers**:
  - **Inner Ring (Layer 1)**: Up to 5 quick core actions closest to finger origin.
  - **Middle Ring (Layer 2)**: Up to 8 intermediate actions.
  - **Outer Ring (Layer 3)**: Up to 12 extended actions and shortcuts.
- **Rich Action Types**:
  - **Core Android Accessibility Actions**: Back, Home, Recents (App Switcher), Notifications shade, Quick Settings panel, Power Dialog, Lock Screen, and Screenshot.
  - **System Utilities**: Voice Assistant / AI Assistant trigger, Volume Up/Down, Flashlight / Torch toggle.
  - **App Shortcuts**: Direct launch of any installed application with real app icon.
  - **Web Bookmarks**: Direct launch of website URLs in the default browser.
  - **Android Shortcuts**: Custom intent URIs.
- **Visual Customization**:
  - Configurable semicircle background color, selection highlight color, and icon tint.
  - Adjustable size and radius scale (60% to 150%).
  - Adjustable alpha transparency and opacity (30% to 100%).
  - Haptic feedback vibration on hovering over sectors.
- **Backup, Restore & Google Cloud Sync**:
  - One-tap Export and Import of configuration as JSON.
  - Support for Android Auto Backup (`allowBackup="true"` and `dataExtractionRules`) to automatically preserve settings in your linked Google Account across device reinstalls.
- **Boot Autostart & Accessibility Service**:
  - Automatically restarts overlay triggers on device reboot (`RECEIVE_BOOT_COMPLETED`).
  - Native `AccessibilityService` for zero-lag system navigation actions.

## Download & Installation

Direct APK download:
- [https://haven.ipv64.net/download/piecontrol.apk](https://haven.ipv64.net/download/piecontrol.apk)

## Permissions Required

1. **Display over other apps (`SYSTEM_ALERT_WINDOW`)**: Required to display edge trigger zones and the radial pie menu over other applications.
2. **Accessibility Service**: Required to execute system navigation actions (Back, Home, Recents, Notifications, etc.).

## Build from Source

```bash
git clone https://github.com/lnovelli/pie-control.git
cd pie-control
./gradlew assembleDebug
```
The compiled APK will be at `app/build/outputs/apk/debug/app-debug.apk`.
