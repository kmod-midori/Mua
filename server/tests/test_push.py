import json
import sqlite3
from concurrent.futures import ThreadPoolExecutor

from fastapi.testclient import TestClient
from test_gateway import call, hello

from mua_server.app import Settings, create_app
from mua_server.storage import DeviceStore


def register(client, token="fcm-first", credential="device-secret", device="phone"):
    return client.put(
        f"/devices/{device}/push",
        headers={"Authorization": f"Bearer {credential}"},
        json={"name": "Phone", "token": token},
    )


def test_push_auth_validation_and_restart(tmp_path):
    path = tmp_path / "mua.sqlite3"
    settings = Settings("client-secret", "device-secret", str(tmp_path))
    with TestClient(create_app(settings)) as client:
        for credential in ("", "wrong", "client-secret"):
            assert register(client, credential=credential).status_code == 401
        for token in ("", " ", "x" * 4097):
            assert register(client, token=token).status_code == 422
        assert register(client).status_code == 200
        assert register(client, token="fcm-rotated").status_code == 200
        assert register(client, token="fcm-rotated").status_code == 200
        devices = json.loads(call(client, "devices_list")["content"][0]["text"])
        assert len(devices) == 1
        assert devices[0]["online"] is False
        assert devices[0]["wakeEnabled"] is True
        assert "fcm-rotated" not in json.dumps(devices)
    with sqlite3.connect(path) as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 1
        assert db.execute("SELECT fcm_token FROM devices").fetchone()[0] == "fcm-rotated"
    with TestClient(create_app(settings)) as client:
        assert "Phone" in call(client, "devices_list")["content"][0]["text"]
        with client.websocket_connect(
            "/devices/phone/ws", headers={"Authorization": "Bearer device-secret"}
        ) as socket:
            hello(socket, "Renamed")
            row = client.app.state.store.list_devices()[0]
            assert row["name"] == "Renamed"
            assert row["fcm_token"] == "fcm-rotated"
            assert row["last_seen_at"]
        assert register(client, token=None).json() == {"registered": False}
        assert client.app.state.store.list_devices()[0]["fcm_token"] is None


def test_token_reassignment_removes_previous_device_registration(tmp_path):
    store = DeviceStore(str(tmp_path / "mua.db"))
    store.initialize()
    store.register_push("old", "Old", "same-token")
    store.register_push("new", "New", "same-token")
    rows = {r["device_id"]: r for r in store.list_devices()}
    assert rows["old"]["fcm_token"] is None
    assert rows["new"]["fcm_token"] == "same-token"


def test_missing_token_does_not_clear_registration(tmp_path):
    settings = Settings("client-secret", "device-secret", str(tmp_path))
    with TestClient(create_app(settings)) as client:
        register(client)
        response = client.put(
            "/devices/phone/push",
            headers={"Authorization": "Bearer device-secret"},
            json={"name": "Phone"},
        )
        assert response.status_code == 422
        assert client.app.state.store.list_devices()[0]["fcm_token"] == "fcm-first"


class FakeSender:
    def __init__(self):
        import threading

        self.sent = threading.Event()
        self.tokens = []

    def send(self, token, deadline=None):
        self.tokens.append(token)
        self.sent.set()

    def close(self):
        pass


def test_offline_call_wakes_reconnects_and_forwards_once(tmp_path):
    settings = Settings("client-secret", "device-secret", str(tmp_path))
    with TestClient(create_app(settings)) as client, ThreadPoolExecutor() as pool:
        sender = FakeSender()
        client.app.state.wake.sender = sender
        register(client)
        first = pool.submit(call, client, "screenshot", {"deviceId": "phone", "displayId": 7})
        second = pool.submit(call, client, "screenshot", {"deviceId": "phone", "displayId": 8})
        assert sender.sent.wait(3)
        with client.websocket_connect(
            "/devices/phone/ws", headers={"Authorization": "Bearer device-secret"}
        ) as socket:
            hello(socket)
            requests = [socket.receive_json(), socket.receive_json()]
            assert {r["arguments"]["displayId"] for r in requests} == {7, 8}
            assert requests[0]["id"] != requests[1]["id"]
            for request in requests:
                socket.send_json({"type": "result", "id": request["id"], "result": {"content": []}})
            assert not first.result(timeout=3)["isError"]
            assert not second.result(timeout=3)["isError"]
        assert sender.tokens == ["fcm-first"]


def test_wake_timeout_and_invalid_token_cleanup(tmp_path):
    from mua_server.push import InvalidRegistration

    settings = Settings("client-secret", "device-secret", str(tmp_path))
    with TestClient(create_app(settings)) as client:
        sender = FakeSender()
        client.app.state.wake.sender = sender
        client.app.state.wake.timeout = 0.05
        register(client)
        result = call(client, "screenshot", {"deviceId": "phone", "displayId": 7})
        assert result["isError"]
        assert "no call was sent" in result["content"][0]["text"]
        assert client.app.state.store.push_token("phone") == "fcm-first"

        class InvalidSender(FakeSender):
            def send(self, token, deadline=None):
                raise InvalidRegistration()

        client.app.state.wake.sender = InvalidSender()
        assert call(client, "screenshot", {"deviceId": "phone", "displayId": 7})["isError"]
        assert client.app.state.store.push_token("phone") is None
        register(client)

        class RotatingSender(FakeSender):
            def send(self, token, deadline=None):
                client.app.state.store.register_push("phone", "Phone", "new-token")
                raise InvalidRegistration()

        client.app.state.wake.sender = RotatingSender()
        assert call(client, "screenshot", {"deviceId": "phone", "displayId": 7})["isError"]
        assert client.app.state.store.push_token("phone") == "new-token"


def test_fcm_payload_and_rejected_token(monkeypatch):
    import pytest

    from mua_server import push

    requests = []
    status = 200
    error_code = "UNREGISTERED"
    detail_type = "type.googleapis.com/google.firebase.fcm.v1.FcmError"

    class Session:
        def __init__(self, *args, **kwargs):
            pass

        def post(self, endpoint, **kwargs):
            requests.append((endpoint, kwargs))

            class Response:
                status_code = status

                def json(self):
                    return {
                        "error": {
                            "details": [
                                {
                                    "@type": detail_type,
                                    "errorCode": error_code,
                                }
                            ]
                        }
                    }

            return Response()

        def close(self):
            pass

    monkeypatch.setattr(push.google.auth, "default", lambda **kwargs: (object(), "project"))
    monkeypatch.setattr(push, "AuthorizedSession", Session)
    sender = push.FcmSender("project")
    sender.send("token")
    endpoint, options = requests[0]
    assert endpoint == "https://fcm.googleapis.com/v1/projects/project/messages:send"
    assert options["json"] == {
        "message": {
            "token": "token",
            "android": {"priority": "high", "ttl": "30s"},
            "data": {"action": "wake"},
        }
    }
    # An expired job must never send after waiting in the sender/executor queue.
    with pytest.raises(TimeoutError):
        sender.send("token", deadline=0)
    assert len(requests) == 1
    status = 404
    with pytest.raises(push.InvalidRegistration):
        sender.send("token")
    status = 400
    error_code = "INVALID_ARGUMENT"
    with pytest.raises(push.InvalidRegistration):
        sender.send("token")
    # A generic bad-request response is not evidence that the token is invalid.
    detail_type = "type.googleapis.com/google.rpc.BadRequest"
    with pytest.raises(RuntimeError):
        sender.send("token")


def test_data_directory_is_required(tmp_path, monkeypatch):
    import pytest

    monkeypatch.setenv("MUA_CLIENT_TOKEN", "client-secret")
    monkeypatch.setenv("MUA_DEVICE_TOKEN", "device-secret")
    monkeypatch.delenv("MUA_DATA_DIR", raising=False)
    with pytest.raises(KeyError, match="MUA_DATA_DIR"):
        Settings.from_env()
    for value in ("", "   "):
        monkeypatch.setenv("MUA_DATA_DIR", value)
        with pytest.raises(ValueError, match="MUA_DATA_DIR must be nonempty"):
            Settings.from_env()
    monkeypatch.setenv("MUA_DATA_DIR", str(tmp_path))
    assert Settings.from_env().data_dir == str(tmp_path)


def test_device_auth_works_behind_proxy_prefix(tmp_path):
    settings = Settings("client-secret", "device-secret", str(tmp_path))
    with TestClient(create_app(settings), root_path="/gateway") as client:
        for token, expected in [("device-secret", 200), ("client-secret", 401), ("wrong", 401)]:
            response = client.put(
                "/gateway/devices/phone/push",
                headers={"Authorization": f"Bearer {token}"},
                json={"name": "Phone", "token": "fcm-token"},
            )
            assert response.status_code == expected
        assert client.get("/gateway/devices/unknown").status_code == 401


def test_stale_registrations_are_pruned_and_can_register_again(tmp_path):
    from datetime import UTC, datetime, timedelta

    store = DeviceStore(str(tmp_path / "mua.db"))
    store.initialize()
    store.register_push("old", "Old", "old-token")
    store.register_push("fresh", "Fresh", "fresh-token")
    stale = (datetime.now(UTC) - timedelta(days=61)).isoformat()
    with sqlite3.connect(store.path) as db:
        db.execute("UPDATE devices SET push_updated_at=? WHERE device_id='old'", (stale,))
    assert store.push_token("old") is None
    assert store.push_token("fresh") == "fresh-token"
    rows = {row["device_id"]: row for row in store.list_devices()}
    assert rows["old"]["name"] == "Old"
    assert rows["old"]["fcm_token"] is None
    # An unchanged token is still an affirmative refresh from the client.
    store.register_push("old", "Old", "old-token")
    assert store.push_token("old") == "old-token"
    assert {r["device_id"]: r for r in store.list_devices()}["old"]["push_updated_at"] > stale


def test_service_account_enables_wake_without_project_override(tmp_path, monkeypatch):
    from types import SimpleNamespace

    from mua_server import push

    monkeypatch.setenv("GOOGLE_APPLICATION_CREDENTIALS", "/configured/service-account.json")
    monkeypatch.setattr(
        push.google.auth,
        "default",
        lambda **kwargs: (SimpleNamespace(project_id="account-project"), "detected-project"),
    )
    monkeypatch.setattr(
        push,
        "AuthorizedSession",
        lambda *args, **kwargs: SimpleNamespace(close=lambda: None),
    )
    for override, expected in [("", "account-project"), ("override-project", "override-project")]:
        settings = Settings("client-secret", "device-secret", str(tmp_path), override)
        with TestClient(create_app(settings)) as client:
            assert client.app.state.wake.sender.endpoint == (
                f"https://fcm.googleapis.com/v1/projects/{expected}/messages:send"
            )


def test_credentials_without_project_require_override(monkeypatch):
    import pytest

    from mua_server import push

    monkeypatch.setattr(push.google.auth, "default", lambda **kwargs: (object(), None))
    with pytest.raises(ValueError, match="MUA_FIREBASE_PROJECT_ID"):
        push.FcmSender()
