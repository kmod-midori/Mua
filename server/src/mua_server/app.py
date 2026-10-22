"""Authenticated MCP gateway; each device owns its execution and display resources."""

import asyncio
import hmac
import json
import logging
import os
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import UTC, datetime
from importlib.metadata import version
from importlib.resources import files
from pathlib import Path
from uuid import uuid4

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from mcp import types
from mcp.server.lowlevel import Server
from mcp.server.streamable_http_manager import StreamableHTTPSessionManager
from mcp.server.transport_security import TransportSecuritySettings
from pydantic import BaseModel, Field, ValidationError
from starlette.responses import JSONResponse
from starlette.routing import Route

from mua_server.push import FcmSender, WakeCoordinator
from mua_server.storage import DeviceStore


@dataclass(frozen=True)
class Settings:
    client_token: str
    device_token: str
    data_dir: str
    firebase_project_id: str = ""

    def __post_init__(self):
        if not isinstance(self.client_token, str) or not self.client_token.strip():
            raise ValueError("MUA_CLIENT_TOKEN must be nonempty")
        if not isinstance(self.device_token, str) or not self.device_token.strip():
            raise ValueError("MUA_DEVICE_TOKEN must be nonempty")
        if not isinstance(self.data_dir, str) or not self.data_dir.strip():
            raise ValueError("MUA_DATA_DIR must be nonempty")

    @classmethod
    def from_env(cls):
        return cls(
            os.environ["MUA_CLIENT_TOKEN"],
            os.environ["MUA_DEVICE_TOKEN"],
            os.environ["MUA_DATA_DIR"],
            os.environ.get("MUA_FIREBASE_PROJECT_ID", ""),
        )


class PushRegistration(BaseModel):
    name: str = Field(min_length=1, max_length=256)
    token: str | None = Field(min_length=1, max_length=4096, pattern=r"^\S+$")


class Hello(BaseModel):
    type: str
    version: int
    name: str = Field(min_length=1)
    tools: list[types.Tool]


@dataclass(eq=False)
class Device:
    id: str
    name: str
    tools: list[types.Tool]
    socket: WebSocket
    connected_at: str = field(default_factory=lambda: datetime.now(UTC).isoformat())
    pending: dict[str, asyncio.Future] = field(default_factory=dict)
    send_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    online: bool = True

    def disconnect(self):
        self.online = False
        for future in self.pending.values():
            if not future.done():
                future.set_exception(
                    ConnectionError(
                        "Device disconnected; operation outcome is unknown. Calls are not replayed."
                    )
                )
        self.pending.clear()

    async def call(self, name: str, arguments: dict) -> types.CallToolResult:
        if not any(tool.name == name for tool in self.tools):
            raise ValueError(f"Device {self.id} does not support tool: {name}")
        request_id = uuid4().hex
        future = asyncio.get_running_loop().create_future()
        try:
            async with self.send_lock:
                if not self.online:
                    raise ConnectionError("Device is offline")
                self.pending[request_id] = future
                await self.socket.send_json(
                    {
                        "type": "call",
                        "id": request_id,
                        "name": name,
                        "arguments": arguments,
                    }
                )
            return await future
        finally:
            self.pending.pop(request_id, None)
            # Consume a disconnect exception even if sending failed or the caller was cancelled.
            if not future.done():
                future.cancel()
            elif not future.cancelled():
                future.exception()


class Registry:
    def __init__(self):
        self.devices: dict[str, Device] = {}
        self.changed = asyncio.Condition()

    def get(self, device_id: str) -> Device:
        device = self.devices.get(device_id)
        if device is None or not device.online:
            raise ValueError(f"Device {device_id} is offline. Use devices_list for connected IDs.")
        return device

    async def attach(self, device: Device):
        previous = self.devices.get(device.id)
        self.devices[device.id] = device
        if previous:
            previous.disconnect()
            try:
                await previous.socket.close(code=1012, reason="Connection replaced")
            except (RuntimeError, OSError):
                pass  # The old socket may have closed before its receive loop detached it.

        async with self.changed:
            self.changed.notify_all()

    async def wait_online(self, device_id: str):
        async with self.changed:
            await self.changed.wait_for(
                lambda: device_id in self.devices and self.devices[device_id].online
            )
            return self.get(device_id)

    def detach(self, device: Device):
        device.disconnect()
        if self.devices.get(device.id) is device:
            del self.devices[device.id]

    async def close(self):
        for device in list(self.devices.values()):
            self.detach(device)
            try:
                await device.socket.close(code=1001, reason="Server stopping")
            except (RuntimeError, OSError):
                pass


def authorized(header: str | None, token: str) -> bool:
    return hmac.compare_digest((header or "").encode(), f"Bearer {token}".encode())


def text_result(value) -> types.CallToolResult:
    return types.CallToolResult(content=[types.TextContent(type="text", text=json.dumps(value))])


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    registry = Registry()
    store = DeviceStore(str(Path(settings.data_dir) / "mua.sqlite3"))
    wake = WakeCoordinator(registry, store)
    mcp = Server("mua-central", version=version("mua-server"))
    catalog = json.loads(files("mua_server").joinpath("catalog/device_tools.json").read_text())
    device_tools = {}
    for definition in catalog:
        tool = types.Tool.model_validate(definition)
        tool.inputSchema["properties"] = {
            "deviceId": {"type": "string", "minLength": 1, "description": "ID from devices_list"},
            **tool.inputSchema["properties"],
        }
        tool.inputSchema["required"] = ["deviceId", *tool.inputSchema["required"]]
        device_tools[tool.name] = tool

    @mcp.list_tools()
    async def list_tools():
        return [
            types.Tool(
                name="devices_list",
                description="List known mua devices, including offline devices and wake-up registration status.",
                annotations=types.ToolAnnotations(readOnlyHint=True),
                inputSchema={"type": "object", "properties": {}},
            ),
            *device_tools.values(),
        ]

    @mcp.call_tool()
    async def call_tool(name: str, arguments: dict):
        if name == "devices_list":
            rows = await asyncio.to_thread(store.list_devices)
            return text_result(
                [
                    {
                        "deviceId": row["device_id"],
                        "name": row["name"],
                        "online": row["device_id"] in registry.devices,
                        "connectedAt": registry.devices[row["device_id"]].connected_at
                        if row["device_id"] in registry.devices
                        else None,
                        "lastSeenAt": row["last_seen_at"],
                        "wakeEnabled": row["fcm_token"] is not None,
                    }
                    for row in rows
                ]
            )
        if name not in device_tools:
            raise ValueError(f"Unknown tool: {name}")
        device = await wake.get_device(arguments["deviceId"])
        return await device.call(
            name, {key: value for key, value in arguments.items() if key != "deviceId"}
        )

    manager = StreamableHTTPSessionManager(
        mcp,
        stateless=True,
        json_response=True,
        security_settings=TransportSecuritySettings(enable_dns_rebinding_protection=False),
    )

    @asynccontextmanager
    async def lifespan(app):
        await asyncio.to_thread(store.initialize)
        if settings.firebase_project_id or os.environ.get("GOOGLE_APPLICATION_CREDENTIALS"):
            wake.sender = await asyncio.to_thread(FcmSender, settings.firebase_project_id)
        async with manager.run():
            try:
                yield
            finally:
                try:
                    await wake.close()
                finally:
                    await registry.close()

    app = FastAPI(title="mua central server", version=version("mua-server"), lifespan=lifespan)
    app.state.registry = registry
    app.state.store = store
    app.state.wake = wake

    @app.middleware("http")
    async def client_auth(request, call_next):
        route_path = request.scope["path"].removeprefix(request.scope.get("root_path", ""))
        token = (
            settings.device_token if route_path.startswith("/devices/") else settings.client_token
        )
        if not authorized(request.headers.get("authorization"), token):
            return JSONResponse(
                {"detail": "Unauthorized"}, status_code=401, headers={"WWW-Authenticate": "Bearer"}
            )
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        return response

    # Route an ASGI callable directly so the SDK owns the full MCP HTTP exchange at /mcp.
    class McpEndpoint:
        async def __call__(self, scope, receive, send):
            await manager.handle_request(scope, receive, send)

    app.router.routes.append(
        Route("/mcp", endpoint=McpEndpoint(), methods=["GET", "POST", "DELETE"])
    )

    @app.put("/devices/{device_id}/push")
    def register_push(device_id: str, registration: PushRegistration):
        if not device_id.strip() or len(device_id) > 256:
            raise HTTPException(422, "Invalid device ID")
        store.register_push(device_id, registration.name, registration.token)
        return {"registered": registration.token is not None}

    @app.websocket("/devices/{device_id}/ws")
    async def device_socket(socket: WebSocket, device_id: str):
        if not authorized(socket.headers.get("authorization"), settings.device_token):
            await socket.close(code=1008)
            return
        await socket.accept()
        device = None
        try:
            hello = Hello.model_validate(await socket.receive_json())
            if hello.type != "hello" or hello.version != 1:
                raise ValueError("Unsupported protocol")
            if len({tool.name for tool in hello.tools}) != len(hello.tools):
                raise ValueError("Duplicate tool names")
            await asyncio.to_thread(store.seen, device_id, hello.name)
            device = Device(device_id, hello.name, hello.tools, socket)
            # Publish registration and acknowledge it before any concurrent MCP call is sent.
            async with device.send_lock:
                await registry.attach(device)
                await socket.send_json({"type": "ready", "version": 1})
            while True:
                message = await socket.receive_json()
                if not isinstance(message, dict) or message.get("type") != "result":
                    raise ValueError("Expected result")
                request_id = message.get("id")
                if not isinstance(request_id, str):
                    raise TypeError("Expected request ID")
                result = types.CallToolResult.model_validate(message.get("result"))
                future = device.pending.get(request_id)
                if future is not None and not future.done():
                    future.set_result(result)
        except (WebSocketDisconnect, OSError):
            pass
        except (ValueError, TypeError, ValidationError) as error:
            # Log the error type only: validation payloads may contain device content.
            logging.getLogger(__name__).warning("Invalid bridge message (%s)", type(error).__name__)
            await socket.close(code=1008, reason="Invalid bridge message")
        finally:
            if device:
                registry.detach(device)

    return app
