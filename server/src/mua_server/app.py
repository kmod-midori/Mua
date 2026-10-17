"""Authenticated MCP gateway; each device owns its execution and display resources."""

import asyncio
import hmac
import json
import logging
import os
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import UTC, datetime
from importlib.resources import files
from uuid import uuid4

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from mcp import types
from mcp.server.lowlevel import Server
from mcp.server.streamable_http_manager import StreamableHTTPSessionManager
from mcp.server.transport_security import TransportSecuritySettings
from pydantic import BaseModel, Field, ValidationError
from starlette.responses import JSONResponse
from starlette.routing import Route


@dataclass(frozen=True)
class Settings:
    client_token: str
    device_token: str

    def __post_init__(self):
        if not isinstance(self.client_token, str) or not self.client_token.strip():
            raise ValueError("MUA_CLIENT_TOKEN must be nonempty")
        if not isinstance(self.device_token, str) or not self.device_token.strip():
            raise ValueError("MUA_DEVICE_TOKEN must be nonempty")

    @classmethod
    def from_env(cls):
        return cls(os.environ["MUA_CLIENT_TOKEN"], os.environ["MUA_DEVICE_TOKEN"])


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
    mcp = Server("mua-central")
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
                description="List currently connected mua devices.",
                annotations=types.ToolAnnotations(readOnlyHint=True),
                inputSchema={"type": "object", "properties": {}},
            ),
            *device_tools.values(),
        ]

    @mcp.call_tool()
    async def call_tool(name: str, arguments: dict):
        if name == "devices_list":
            return text_result(
                [
                    {"deviceId": d.id, "name": d.name, "connectedAt": d.connected_at}
                    for d in registry.devices.values()
                    if d.online
                ]
            )
        if name not in device_tools:
            raise ValueError(f"Unknown tool: {name}")
        device = registry.get(arguments["deviceId"])
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
        async with manager.run():
            try:
                yield
            finally:
                await registry.close()

    app = FastAPI(title="mua central server", lifespan=lifespan)
    app.state.registry = registry

    @app.middleware("http")
    async def client_auth(request, call_next):
        if not authorized(request.headers.get("authorization"), settings.client_token):
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
