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

## Release signing

Both Android apps read the root `signing.properties` file, using the same format
as ANCSReceiver:

```properties
KEYSTORE_FILE=../keystore.jks
KEYSTORE_PASSWORD=your-store-password
SIGNING_KEY_ALIAS=your-key-alias
SIGNING_KEY_PASSWORD=your-key-password
```

Relative keystore paths are resolved from each application module, so
`../keystore.jks` refers to the repository root. The root `signing.properties`
and `keystore.jks` are ignored by Git. Keep signing credentials private.

```sh
./gradlew :app:assembleRelease :mua:assembleRelease
```

Signed APKs are written to each application module’s `build/outputs/apk/release/`
directory. Debug builds do not require release credentials.

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

## GitHub Actions

Branch pushes, except those changing only Markdown (`*.md`) files, run
`.github/workflows/debug.yml`: both Android debug APKs are
uploaded as workflow artifacts, JVM tests run, and the server is tested, linted,
and built as a container without publishing.

Push a release tag to run its corresponding workflow in `.github/workflows/`:
`release-na.yml`, `release-mua.yml`, or `release-mua-server.yml`. Each workflow
only accepts its own tag prefix; tag pushes do not run the debug workflow.

| Tag | Published output |
| --- | --- |
| `na-1.2.3` | Signed Neko Assistant APK attached to a draft GitHub release |
| `mua-1.2.3` | Signed mua APK attached to a draft GitHub release |
| `mua-server-1.2.3` | `ghcr.io/<owner>/<repository>/mua-server:1.2.3` and a draft GitHub release linking the image |

Versions must have three numeric components. Only the selected component is
released. Tags name the release and artifacts; they do not change Android
version codes/names or the Python package version. Update those separately when
needed. Server images target the runner's Linux amd64 architecture.

The newest numeric `mua-server-x.x.x` Git tag also publishes the same image digest
as `latest`. Older releases keep their version-specific tag without moving
`latest` backward. Server release jobs are serialized and check remote tags
after the image build. GitHub releases remain drafts.

For Android releases, configure these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded contents of the release keystore.
- `ANDROID_SIGNING_PROPERTIES`: the full `signing.properties` contents shown
  above, with `KEYSTORE_FILE=../keystore.jks` and the actual passwords and alias.

Signing files are restored only for the release job and removed afterward.
Debug builds and server releases do not need signing secrets. GHCR publishing
uses the built-in `GITHUB_TOKEN` with `packages: write`; no registry password is
needed. The workflows create draft GitHub releases with `contents: write`; publish them
manually after review. Container images are still pushed to GHCR automatically. Package
visibility is configured separately in GitHub's package settings.
