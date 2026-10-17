# Build and validation

Run commands from the repository root.

```sh
./gradlew :app:assembleDebug :mua:assembleDebug
./gradlew :app:testDebugUnitTest :mua:testDebugUnitTest
./gradlew :display-control:lintDebug :mua:lintDebug
```

Debug APKs are written to each application module’s `build/outputs/apk/debug/`
directory. The mua JVM tests cover tool validation, listener configuration, MCP
transport behavior, authorization rotation, and Host/Origin checks.

## Device checks

Use an Android 12+ device with Shizuku running. JVM tests cannot validate hidden
Android APIs, display rendering, input routing, or system power behavior.

- Exercise permission grant and denial, Shizuku disconnect/reconnect, and service
  shutdown during an operation. Reconnection requires a new display.
- Create and replace a display, launch an app, and verify screenshot changes after
  taps, swipes, and key presses. Check the original assistant’s preview as well.
- Check that an idle MCP server holds no wake lock, an active display holds one,
  and stopping the display or disconnecting Shizuku releases it. The main UI and
  notification should report the same state.
- Verify both listener modes, port validation, and rejection of old tokens after
  reset. Network-mode clients connect to the device’s IP, without forwarding.
- Test screen-off rendering separately from Doze. A charging device does not
  establish behavior during long unplugged idle periods. See Android’s
  [Doze testing guide](https://developer.android.com/training/monitoring-device-state/doze-standby#testing_doze).
