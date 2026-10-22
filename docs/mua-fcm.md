# FCM wake-up

For end-to-end setup, see [Building and self-hosting Mua](building-and-self-hosting.md).

Mua has two flavors, `foss` and `google`, sharing the same application ID (install one at a time):

- `./gradlew :mua:assembleFossDebug`: no Firebase or Google Play services SDK.
- `./gradlew :mua:assembleGoogleDebug`: optional Firebase Cloud Messaging wake-up.

APKs are under `mua/build/outputs/apk/{foss,google}/debug/`. Replace `Debug`
with `Release` for signed release builds using the release signing configuration.

## Firebase configuration

Register the Android app `moe.reimu.mua` in your Firebase project, download
**google-services.json**, and save it as `mua/src/google/google-services.json`.
The standard Google Services Gradle plugin selects the matching Android client
and generates the Firebase resources. `mua/google-services.json` and standard
build-type-specific locations are also supported, but the flavor-specific path
is recommended. This Android configuration is ignored by Git and contains no
service-account private key.

Only FCM variants process the JSON; the Google-free APK contains neither Firebase
configuration nor Firebase/Play services SDKs. FCM variants are skipped when no matching file exists. Aggregate build and test
tasks still run for Google-free variants. Explicit FCM task names are unavailable
until configuration is supplied; malformed configuration fails the FCM build.

The Android `google-services.json` is **not** a server credential. Server-side
sending uses a separate service account or Application Default Credentials, as
described below. Never embed a service-account private key in an APK.

Install on a device with Google Play services, open Mua, grant Shizuku and
notification permissions, configure a local listener or central connection, and
turn on **Wake via FCM**. With the central connection enabled, Mua automatically
registers its current token with the server. Opening settings, starting the
service, changing connection settings, and Firebase's `onNewToken` callback
schedule durable synchronization. Each enabled upload retrieves the token with
Firebase's `getToken()` API rather than trusting the preference cache, and checks
settings again after retrieval before sending it.

While wake-up is enabled, one network-constrained WorkManager job runs every
30 days, surviving normal process death and device reboots. Reopening the UI
keeps its original schedule. It refreshes local-only installations too, and sends
central registrations through the same serialized upload queue. Disabling
wake-up cancels periodic refresh and queues server unregistration. The MCP
service does not need to run for token maintenance.

Uploads include unchanged tokens so SQLite records a fresh server timestamp.
Network failures, HTTP 408/429, and server failures retry with exponential
backoff. Other HTTP errors are logged by status only; correcting settings or
reopening the screen schedules another attempt. WorkManager scheduling is
inexact and can be delayed by Android restrictions; force-stopped apps must be
opened again.

Changing server or device ID queues cleanup of the old registration. Cleanup
requires the old server and credentials to remain accessible. The device rejects
pushes immediately when disabled, even while server cleanup waits for a network.

Registration uses `PUT /devices/{deviceId}/push` with the shared device bearer
credential and JSON `{"name":"Phone","token":"FCM_TOKEN"}`. Sending `token:null`
clears the registration. The server stores the current token and update timestamp
in SQLite; tokens are never returned by `devices_list`. The server retains
offline devices and reports `online`, `lastSeenAt`, and `wakeEnabled`.

Use the copy-token button to configure an external push sender.

GitHub Actions restores `MUA_GOOGLE_SERVICES_JSON`, when supplied, to
`mua/src/google/google-services.json`. Debug and release CI build the available
variants: Google-free always, and FCM when configuration exists. Releases upload
only the APKs built.

## Automatic server wake-up

Enable the Firebase Cloud Messaging API for the project matching the Android
configuration. Grant the server identity permission to send FCM messages (for example,
the Firebase Cloud Messaging API Admin role). Supply Application Default
Credentials by saving the service-account JSON as
`server/firebase-service-account.json` (ignored by `server/.gitignore`).
The source-checkout launcher `server/start.sh` sets `GOOGLE_APPLICATION_CREDENTIALS`
to that file automatically when it exists. The Android `google-services.json` cannot authorize
server sends. The project ID defaults to the service-account JSON; set
`MUA_FIREBASE_PROJECT_ID` only to override it. Supplying either credentials via
`GOOGLE_APPLICATION_CREDENTIALS` or a project override enables server wake-up.
To disable it, remove the credential file from the launcher location and leave
both variables unset.


In Docker, mount `server/firebase-service-account.json` read-only at
`/run/secrets/firebase-service-account.json`, set `GOOGLE_APPLICATION_CREDENTIALS`
to that container path, and ensure UID 10001 can read it. Keep the SQLite `/data` volume mounted as described in the
[server setup](mua-remote.md#docker).

When an MCP tool targets an offline registered device, the gateway sends a
high-priority `action:wake` data message and waits up to 30 seconds for its
WebSocket connection. Concurrent requests share that wake-up attempt. Once
connected, each waiting call is forwarded exactly once; already dispatched calls
are never replayed after disconnect. A timeout or send failure returns a tool
error without forwarding the operation. Cancelling one waiting request does not
cancel wake-up for other callers, and its operation is not dispatched later.
Online calls and `devices_list` do not send pushes.

An FCM `UNREGISTERED` response or FCM-specific `INVALID_ARGUMENT` response to our
fixed valid wake payload clears only the rejected token, preserving a token that
refreshed while the push was in flight. Generic bad requests and other
provider/network failures retain the registration. Before listing devices or
sending a wake-up, the server clears registrations that have not synced for
60 days. Device metadata is preserved; a later upload restores wake-up. Delivery
depends on Android/FCM policies, Mua's
opt-in setting, and Shizuku availability.

## Sending a wake-up

Use the Firebase Admin SDK or authenticated FCM HTTP v1 API from your trusted
sender. Send a **data-only**, high-priority message (no `notification` payload):

```json
{
  "message": {
    "token": "DEVICE_FCM_REGISTRATION_TOKEN",
    "android": { "priority": "high", "ttl": "60s" },
    "data": { "action": "wake" }
  }
}
```

The push starts Mua's foreground service with its saved transport settings and
visible service notification. Clients must authenticate normally. Unknown
actions, normal/downgraded priority messages, disabled wake-up, and configurations
with both transports disabled do not start it. Android may reject background
starts; the receiver catches and logs that denial. FCM delivery and Shizuku
availability are required, and force-stopped apps must be opened again first.
See [Firebase message priority](https://firebase.google.com/docs/cloud-messaging/android-message-priority)
for delivery and deprioritization behavior.

Only **FCM-started sessions** stop automatically after 10 minutes without calls.
Authenticated local `/mcp` requests and central bridge tool calls refresh the
idle deadline. Tool operations already queued or executing finish before the
idle countdown resumes. WebSocket keepalives, reconnects and UI previews do not
keep a session alive. Shutdown closes both transports, releases displays and
wake locks, and disconnects Shizuku.
A manual start disables the idle timeout for that service lifetime; later pushes
cannot turn it back on. A new wake after an idle shutdown starts a fresh session.

## Validation

Run `./gradlew :mua:test :mua:assembleDebug` to test and build available variants. On a configured device, test a push with the app backgrounded, an idle
shutdown, a call shortly before the deadline, a manual start that remains alive
past 10 minutes, a downgraded message, and a subsequent wake after shutdown.

The 30-day refresh and 60-day staleness window follow the token-refresh strategy
in [Firebase's registration guidance](https://firebase.google.com/docs/cloud-messaging/manage-tokens)
and its [WorkManager example](https://firebase.blog/posts/2023/04/managing-cloud-messaging-tokens/).
