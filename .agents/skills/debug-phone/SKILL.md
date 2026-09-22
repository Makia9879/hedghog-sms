---
name: debug-phone
description: >
  Debug the hedgehog-sms Android app on a USB phone. Use when the user says
  调试, 真机, USB, adb, 覆盖安装, install the debug APK, logcat, or asks to
  exercise com.makia.hedgehogsms on a device. Cover-install the debug build,
  keep granted permissions, and do not read SMS content unless the user asks
  to verify an export.
---

# Debug the phone app

Package `com.makia.hedgehogsms`. Launcher activity `.MainActivity`. Debug APK `app/build/outputs/apk/debug/app-debug.apk`.

## Install

1. `adb devices -l` shows the phone as `device`. `unauthorized` means the phone still needs the debugging prompt.
2. Build with `./gradlew :app:assembleDebug`. Use the SDK already configured on this machine. The README override that points `ANDROID_HOME` at `/Users/makia/Library/Android/sdk` is only for a conflicting SDK setup.
3. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
4. Done when adb prints `Success`.

Cover-install. `adb install -r` keeps the app data and the runtime grants for `READ_SMS`, `RECEIVE_SMS`, and `READ_PHONE_STATE`. Uninstall only after the user explicitly says to uninstall. A new permission still prompts once; `READ_PHONE_NUMBERS` is requested from the export screen, not from the first-run sequence (`READ_SMS`, then `READ_PHONE_STATE`, then `RECEIVE_SMS`).

Use the debug APK. This repo has no release signing config, and the phone build is the debug-signed one. `assembleRelease` is unsigned. A release APK signed with another key cannot replace the installed debug build.

If install prints `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, stop and tell the user. The installed signature does not match this debug keystore. Do not uninstall to force it.

## Launch and crashes

```sh
adb shell am start -n com.makia.hedgehogsms/.MainActivity
adb shell pidof com.makia.hedgehogsms
```

Done when `pidof` prints a pid and that pid's logcat has no `FATAL` / `AndroidRuntime` crash. Read logcat with `--pid` of that process. A debuggable process accepts `adb shell run-as com.makia.hedgehogsms`.

Debuggable launches honor the intent extra `debug_scan_action` (`restart`, `pause`, `resume`). Read `MainActivity` before sending it; it starts or pauses the history scan.

## Privacy

Debug through the UI, permission flags, and crash logs. Permission lines from `dumpsys package com.makia.hedgehogsms` are enough to see what was granted.

Read message text only when the user asks to verify an export. Then compare counts and checksums, and do not paste message bodies, phone numbers, or sender addresses into the reply. A pulled zip goes in `device-export/`, which is gitignored. Do not commit it.

## Driving the UI

This phone class is Xiaomi HyperOS and can expose a second, smaller display. `input tap` goes to display 0 by default, which is the main screen. `getprop persist.security.adbinput` must be `1`; otherwise injected taps are discarded.

`uiautomator dump` bounds are in the same pixels as `input tap`. On Compose screens the clickable node is the parent view, not the `TextView` that holds the label. Tap the center of the clickable bounds.

The export button label returns to `开始导出` after a finished export. Success is the status line `已保存到…` plus the zip name. The chosen folder is `shared_prefs/sms_export.xml` key `tree_uri` inside the app data; absent means Downloads.
