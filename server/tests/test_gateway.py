import asyncio
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient
from mcp import types
from starlette.websockets import WebSocketDisconnect

from mua_server.app import Device, Registry, Settings, create_app


@pytest.fixture
def client():
    with TestClient(create_app(Settings("client-secret", "shared-secret"))) as client:
        yield client


def call(client, name, arguments=None, token="client-secret"):
    response = client.post(
        "/mcp",
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/json, text/event-stream",
        },
        json={
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/call",
            "params": {"name": name, "arguments": arguments or {}},
        },
    )
    assert response.status_code == 200, response.text
    return response.json()["result"]


def hello(socket, name="Phone"):
    socket.send_json(
        {
            "type": "hello",
            "version": 1,
            "name": name,
            "tools": [
                {
                    "name": "screenshot",
                    "description": "Device screenshot",
                    "inputSchema": {
                        "type": "object",
                        "properties": {"displayId": {"type": "integer"}},
                        "required": ["displayId"],
                    },
                }
            ],
        }
    )
    assert socket.receive_json() == {"type": "ready", "version": 1}


def test_auth_and_mcp_discovery(client):
    assert client.post("/mcp").status_code == 401
    assert client.post("/mcp", headers={"Authorization": "Bearer shared-secret"}).status_code == 401
    response = client.post(
        "/mcp",
        headers={
            "Authorization": "Bearer client-secret",
            "Accept": "application/json, text/event-stream",
        },
        json={
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-11-25",
                "capabilities": {},
                "clientInfo": {"name": "test", "version": "1"},
            },
        },
    )
    assert response.status_code == 200
    assert response.json()["result"]["serverInfo"]["name"] == "mua-central"
    response = client.post(
        "/mcp",
        headers={
            "Authorization": "Bearer client-secret",
            "Accept": "application/json, text/event-stream",
        },
        json={"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
    )
    assert {tool["name"] for tool in response.json()["result"]["tools"]} == {
        "devices_list",
        "display_start",
        "displays_list",
        "recent_tasks_list",
        "recent_task_move",
        "display_stop",
        "screenshot",
        "apps_list",
        "app_launch",
        "focused_app",
        "tap",
        "swipe",
        "key_press",
        "notifications_list",
        "apps_search",
        "notification_clear",
        "notification_click",
        "notification_action",
        "notification_reply",
        "text_input",
        "long_press",
        "double_tap",
    }
    for device, token in [
        ("phone-a", "wrong-secret"),
        ("unknown", "wrong-secret"),
        ("phone-a", "client-secret"),
    ]:
        with (
            pytest.raises(WebSocketDisconnect),
            client.websocket_connect(
                f"/devices/{device}/ws", headers={"Authorization": f"Bearer {token}"}
            ),
        ):
            pass


def test_two_devices_routing_and_image_result(client):
    with (
        client.websocket_connect(
            "/devices/phone-a/ws", headers={"Authorization": "Bearer shared-secret"}
        ) as a,
        client.websocket_connect(
            "/devices/phone-b/ws", headers={"Authorization": "Bearer shared-secret"}
        ) as b,
    ):
        hello(a, "A")
        hello(b, "B")
        import json

        devices = json.loads(call(client, "devices_list")["content"][0]["text"])
        assert {d["deviceId"]: d["name"] for d in devices} == {"phone-a": "A", "phone-b": "B"}
        with ThreadPoolExecutor() as pool:
            requests = {
                device: pool.submit(
                    call,
                    client,
                    "screenshot",
                    {
                        "deviceId": device,
                        "displayId": display,
                    },
                )
                for device, display in [("phone-a", 7), ("phone-b", 8)]
            }
            first, second = a.receive_json(), b.receive_json()
            assert first["arguments"] == {"displayId": 7}
            assert second["arguments"] == {"displayId": 8}
            assert first["id"] != second["id"]
            b.send_json(
                {
                    "type": "result",
                    "id": second["id"],
                    "result": {
                        "content": [{"type": "text", "text": "No frame yet"}],
                        "isError": True,
                    },
                }
            )
            a.send_json(
                {
                    "type": "result",
                    "id": first["id"],
                    "result": {
                        "content": [{"type": "image", "mimeType": "image/png", "data": "cG5n"}]
                    },
                }
            )
            assert requests["phone-a"].result()["content"][0]["data"] == "cG5n"
            assert requests["phone-b"].result()["isError"] is True


def test_disconnect_fails_pending_and_reconnect_does_not_replay(client):
    with ThreadPoolExecutor() as pool:
        with client.websocket_connect(
            "/devices/phone-a/ws", headers={"Authorization": "Bearer shared-secret"}
        ) as socket:
            hello(socket)
            result = pool.submit(
                call,
                client,
                "screenshot",
                {"deviceId": "phone-a", "displayId": 7},
            )
            socket.receive_json()
        failed = result.result(timeout=5)
        assert failed["isError"]
        assert "outcome is unknown" in failed["content"][0]["text"]
        assert call(client, "displays_list", {"deviceId": "phone-a"})["isError"]
        with client.websocket_connect(
            "/devices/phone-a/ws", headers={"Authorization": "Bearer shared-secret"}
        ) as socket:
            hello(socket)
            result = pool.submit(
                call,
                client,
                "screenshot",
                {"deviceId": "phone-a", "displayId": 99},
            )
            request = socket.receive_json()
            assert request["arguments"]["displayId"] == 99
            socket.send_json({"type": "result", "id": request["id"], "result": {"content": []}})
            assert not result.result(timeout=5)["isError"]


def test_bad_arguments_and_unknown_tools(client):
    for arguments in (
        {"deviceId": "phone-a"},
        {"displayId": 7},
        {"deviceId": 1, "displayId": 7},
        {"deviceId": "phone-a", "displayId": "7"},
    ):
        assert call(client, "screenshot", arguments)["isError"]
    for name in ("unknown", "device_tools_list", "device_tool_call"):
        assert call(client, name, {"deviceId": "phone-a"})["isError"]
    with client.websocket_connect(
        "/devices/phone-a/ws", headers={"Authorization": "Bearer shared-secret"}
    ) as socket:
        hello(socket)
        result = call(client, "display_start", {"deviceId": "phone-a"})
        assert result["isError"]
        assert "does not support tool: display_start" in result["content"][0]["text"]


def test_replacement_does_not_remove_new_connection(client):
    with client.websocket_connect(
        "/devices/phone-a/ws", headers={"Authorization": "Bearer shared-secret"}
    ) as old:
        hello(old)
        with client.websocket_connect(
            "/devices/phone-a/ws", headers={"Authorization": "Bearer shared-secret"}
        ) as new:
            hello(new, "Replacement")
            with pytest.raises(WebSocketDisconnect):
                old.receive_json()
            assert "Replacement" in call(client, "devices_list")["content"][0]["text"]


async def test_cancelled_call_cleans_pending():
    class Socket:
        async def send_json(self, value):
            pass

    device = Device("a", "A", [types.Tool(name="tap", inputSchema={})], Socket())
    task = asyncio.create_task(device.call("tap", {}))
    await asyncio.sleep(0)
    assert len(device.pending) == 1
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert not device.pending
    registry = Registry()
    registry.devices["a"] = device
    registry.detach(device)
    assert not registry.devices


def test_invalid_result_disconnects_and_fails_pending(client):
    with (
        ThreadPoolExecutor() as pool,
        client.websocket_connect(
            "/devices/phone-a/ws", headers={"Authorization": "Bearer shared-secret"}
        ) as socket,
    ):
        hello(socket)
        result = pool.submit(
            call,
            client,
            "screenshot",
            {"deviceId": "phone-a", "displayId": 7},
        )
        request = socket.receive_json()
        socket.send_json({"type": "result", "id": request["id"], "result": {"content": "bad"}})
        with pytest.raises(WebSocketDisconnect):
            socket.receive_json()
        assert result.result(timeout=5)["isError"]


def test_replacement_fails_old_pending_call(client):
    with (
        ThreadPoolExecutor() as pool,
        client.websocket_connect(
            "/devices/phone-a/ws", headers={"Authorization": "Bearer shared-secret"}
        ) as old,
    ):
        hello(old)
        result = pool.submit(
            call,
            client,
            "screenshot",
            {"deviceId": "phone-a", "displayId": 7},
        )
        old.receive_json()
        with client.websocket_connect(
            "/devices/phone-a/ws", headers={"Authorization": "Bearer shared-secret"}
        ) as new:
            hello(new, "Replacement")
            assert result.result(timeout=5)["isError"]
            assert "Replacement" in call(client, "devices_list")["content"][0]["text"]


async def test_replacement_survives_an_already_closed_socket():
    class ClosedSocket:
        async def close(self, **kwargs):
            raise RuntimeError("Already closed")

    registry = Registry()
    old = Device("a", "Old", [], ClosedSocket())
    new = Device("a", "New", [], ClosedSocket())
    await registry.attach(old)
    await registry.attach(new)
    registry.detach(old)
    assert registry.get("a") is new
    await registry.close()
    assert not registry.devices


def tool_list(client):
    response = client.post(
        "/mcp",
        headers={
            "Authorization": "Bearer client-secret",
            "Accept": "application/json, text/event-stream",
        },
        json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
    )
    assert response.status_code == 200
    return response.json()["result"]["tools"]


def test_direct_schemas_stay_stable_across_device_connections(client):
    before = tool_list(client)
    tools = {tool["name"]: tool for tool in before}
    assert len(tools) == 22
    for name, tool in tools.items():
        if name == "devices_list":
            continue
        assert tool["inputSchema"]["properties"]["deviceId"]["type"] == "string"
        assert "deviceId" in tool["inputSchema"]["required"]
    assert tools["screenshot"]["inputSchema"]["required"] == ["deviceId", "displayId"]
    assert tools["display_start"]["inputSchema"]["required"] == ["deviceId"]
    assert tools["tap"]["inputSchema"]["properties"]["x"] == {"type": "number"}
    assert tools["swipe"]["inputSchema"]["properties"]["durationMs"] == {
        "type": "integer",
        "minimum": 0,
    }
    with client.websocket_connect(
        "/devices/phone-a/ws", headers={"Authorization": "Bearer shared-secret"}
    ) as socket:
        hello(socket)
        assert tool_list(client) == before
    assert tool_list(client) == before


def test_direct_dispatch_preserves_extra_arguments(client):
    with (
        ThreadPoolExecutor() as pool,
        client.websocket_connect(
            "/devices/phone-a/ws", headers={"Authorization": "Bearer shared-secret"}
        ) as socket,
    ):
        hello(socket)
        response = pool.submit(
            call,
            client,
            "screenshot",
            {"deviceId": "phone-a", "displayId": 0, "extra": "unchanged"},
        )
        request = socket.receive_json()
        assert request["name"] == "screenshot"
        assert request["arguments"] == {"displayId": 0, "extra": "unchanged"}
        socket.send_json({"type": "result", "id": request["id"], "result": {"content": []}})
        assert not response.result(timeout=5)["isError"]


def test_read_only_hints(client):
    read_only = {
        "devices_list",
        "apps_list",
        "displays_list",
        "recent_tasks_list",
        "focused_app",
        "screenshot",
        "notifications_list",
        "apps_search",
    }
    for tool in tool_list(client):
        assert (tool.get("annotations") or {}).get("readOnlyHint", False) == (
            tool["name"] in read_only
        )


@pytest.mark.parametrize(
    ("name", "arguments"),
    [
        ("notifications_list", {}),
        ("notification_clear", {"key": "fixture-key"}),
        ("notification_click", {"key": "fixture-key", "displayId": 7}),
        ("notification_action", {"key": "fixture-key", "actionIndex": 0, "displayId": 7}),
        (
            "notification_reply",
            {
                "key": "fixture-key",
                "actionIndex": 1,
                "displayId": 7,
                "resultKey": "reply",
                "text": "测试 reply",
            },
        ),
    ],
)
def test_notification_tools_forward_original_arguments(client, name, arguments):
    with (
        ThreadPoolExecutor() as pool,
        client.websocket_connect(
            "/devices/phone-a/ws", headers={"Authorization": "Bearer shared-secret"}
        ) as socket,
    ):
        socket.send_json(
            {
                "type": "hello",
                "version": 1,
                "name": "Phone",
                "tools": [{"name": name, "inputSchema": {"type": "object"}}],
            }
        )
        assert socket.receive_json()["type"] == "ready"
        result = pool.submit(call, client, name, {"deviceId": "phone-a", **arguments})
        request = socket.receive_json()
        assert request["name"] == name
        assert request["arguments"] == arguments
        socket.send_json(
            {
                "type": "result",
                "id": request["id"],
                "result": {"content": [{"type": "text", "text": "fixture result"}]},
            }
        )
        assert result.result(timeout=5)["content"][0]["text"] == "fixture result"


def test_catalog_matches_shared_library_export():
    from pathlib import Path

    root = Path(__file__).resolve().parents[2]
    shared = root / "display-control/src/main/resources/device_tools.json"
    if shared.exists():
        assert (
            shared.read_bytes()
            == (root / "server/src/mua_server/catalog/device_tools.json").read_bytes()
        ), "Run python scripts/sync-tool-catalog.py"
