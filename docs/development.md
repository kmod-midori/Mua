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
- Create multiple displays with no size arguments and verify their dimensions and
  DPI match the main display. Check independent screenshots, taps, swipes, and key
  presses by ID, plus rejection of unknown IDs and size overrides. Check the
  original assistant’s preview as well.
- Verify duplicate app launches are rejected across displays, including physical
  and background tasks. Close a task externally and verify the next launch uses
  fresh system state. Exercise display count and total-pixel limits.
- Check that an idle MCP server holds no wake lock, an active display holds one,
  stopping one of several preserves it, and stopping the last display or
  disconnecting Shizuku releases it. The main UI and
  notification should report the same state.
- Verify both listener modes, port validation, and rejection of old tokens after
  reset. Network-mode clients connect to the device’s IP, without forwarding.
- Test screen-off rendering separately from Doze. A charging device does not
  establish behavior during long unplugged idle periods. See Android’s
  [Doze testing guide](https://developer.android.com/training/monitoring-device-state/doze-standby#testing_doze).
