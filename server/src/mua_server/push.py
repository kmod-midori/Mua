"""FCM sending and coalesced wake/reconnect waits; never replay dispatched calls."""

import asyncio
import threading
import time

import google.auth
from google.auth.exceptions import GoogleAuthError
from google.auth.transport.requests import AuthorizedSession
from requests.exceptions import RequestException


class InvalidRegistration(Exception):
    pass


class FcmSender:
    def __init__(self, project_id: str = ""):
        credentials, detected_project_id = google.auth.default(
            scopes=["https://www.googleapis.com/auth/firebase.messaging"]
        )
        project_id = project_id or getattr(credentials, "project_id", None) or detected_project_id
        if not project_id:
            raise ValueError("Set MUA_FIREBASE_PROJECT_ID when credentials have no project ID")
        self.session = AuthorizedSession(credentials, refresh_timeout=10, max_refresh_attempts=1)
        self.endpoint = f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"
        self.lock = threading.Lock()

    def send(self, token: str, deadline: float | None = None):
        # Credentials and the requests session are shared; refresh/use them serially.
        with self.lock:
            remaining = deadline - time.monotonic() if deadline is not None else 30
            if remaining <= 0:
                raise TimeoutError("Wake-up deadline expired before sending")
            response = self.session.post(
                self.endpoint,
                json={
                    "message": {
                        "token": token,
                        "android": {"priority": "high", "ttl": "30s"},
                        "data": {"action": "wake"},
                    }
                },
                timeout=min(10, remaining),
                max_allowed_time=remaining,
            )
        if 200 <= response.status_code < 300:
            return
        # This sender constructs a fixed, valid payload. Only the FCM-specific
        # error detail identifies invalid registration; generic HTTP 400s do not.
        try:
            details = response.json().get("error", {}).get("details", [])
            if any(
                d.get("@type") == "type.googleapis.com/google.firebase.fcm.v1.FcmError"
                and d.get("errorCode") in {"UNREGISTERED", "INVALID_ARGUMENT"}
                for d in details
            ):
                raise InvalidRegistration()
        except (ValueError, TypeError, AttributeError):
            pass
        # Never include provider bodies or registration tokens in MCP errors.
        raise RuntimeError(f"FCM rejected wake-up (HTTP {response.status_code})")

    def close(self):
        with self.lock:
            self.session.close()


class WakeCoordinator:
    def __init__(self, registry, store, sender=None, timeout=30):
        self.registry = registry
        self.store = store
        self.sender = sender
        self.timeout = timeout
        self.pending = {}

    async def get_device(self, device_id):
        try:
            return self.registry.get(device_id)
        except ValueError:
            pass
        task = self.pending.get(device_id)
        if task is None:
            task = asyncio.create_task(self._wake(device_id))
            self.pending[device_id] = task

            def finished(done):
                if self.pending.get(device_id) is done:
                    self.pending.pop(device_id, None)
                if not done.cancelled():
                    done.exception()  # Also consume failures after every waiting caller cancels.

            task.add_done_callback(finished)
        return await asyncio.shield(task)

    async def _wake(self, device_id):
        token = await asyncio.to_thread(self.store.push_token, device_id)
        if not token or self.sender is None:
            raise ValueError(f"Device {device_id} is offline; FCM wake-up is not configured.")
        try:
            async with asyncio.timeout(self.timeout):
                await asyncio.to_thread(self.sender.send, token, time.monotonic() + self.timeout)
                return await self.registry.wait_online(device_id)
        except InvalidRegistration:
            await asyncio.to_thread(self.store.clear_token, device_id, token)
            raise ValueError("FCM registration expired; reopen Mua to register again.") from None
        except TimeoutError:
            raise ValueError(
                "Device did not reconnect before the wake-up timeout; no call was sent."
            ) from None
        except (GoogleAuthError, RequestException, RuntimeError):
            raise ValueError(
                "FCM wake-up failed; no call was sent. Check server credentials and connectivity."
            ) from None

    async def close(self):
        tasks = list(self.pending.values())
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        if self.sender is not None:
            await asyncio.to_thread(self.sender.close)
