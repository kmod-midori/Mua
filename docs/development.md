# Build and validation

Run commands from the repository root.

```sh
./gradlew :app:assembleDebug :mua:assembleDebug
./gradlew :app:testDebugUnitTest :mua:testDebugUnitTest :display-control:testDebugUnitTest
./gradlew :display-control:lintDebug :mua:lintDebug
```

Debug APKs are written to each application module’s `build/outputs/apk/debug/`
directory. The mua JVM tests cover tool validation, listener configuration, MCP
transport behavior, authorization rotation, and custom Host/Origin acceptance.

## Device checks

Use an Android 12+ device with Shizuku running. JVM tests cannot validate hidden
Android APIs, display rendering, input routing, or system power behavior.

- Exercise permission grant and denial, Shizuku disconnect/reconnect, and service
  shutdown during an operation. Reconnection requires a new display.
- Create multiple displays with no size arguments and verify their dimensions and
  DPI match the main display. Check independent screenshots, taps, swipes, and key
  presses by ID, plus rejection of unknown IDs and size overrides. Check the
  original assistant’s preview as well.
- Verify launches and moves allow background tasks of the same app on other
  displays, but reject another focused task with that app’s top activity.
  Close a task externally and verify the next launch uses fresh system state.
- List recent tasks, restore an inactive task, and move a live task between virtual
  displays. Verify the task ID stays the same, destination can be queried through Android,
  and unknown tasks and invalid destinations are rejected.
- Stack two tasks of the same app on one display, then move the top task to another
  display. A foreground conflict revealed on the source must restore the moved
  task before returning an error. Check preview colors/crops and live updates;
  unchanged frames should reuse their thumbnail without full-resolution copies.
- Check that an idle MCP server holds no wake lock, an active display holds one,
  stopping one of several preserves it, and stopping the last display or
  disconnecting Shizuku releases it. The main UI and
  notification should report the same state.
- Verify both listener modes, port validation, and rejection of old tokens after
  reset. Network-mode clients connect to the device’s IP, without forwarding.
- Test screen-off rendering separately from Doze. A charging device does not
  establish behavior during long unplugged idle periods. See Android’s
  [Doze testing guide](https://developer.android.com/training/monitoring-device-state/doze-standby#testing_doze).
