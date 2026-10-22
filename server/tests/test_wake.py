import asyncio

import pytest
from test_push import FakeSender

from mua_server.app import Device, Registry
from mua_server.push import WakeCoordinator


async def test_cancelled_waiter_does_not_cancel_other_waiters_or_dispatch_calls():
    class Store:
        def push_token(self, device_id):
            return "token"

    class Socket:
        async def close(self, **kwargs):
            pass

    registry = Registry()
    sender = FakeSender()
    wake = WakeCoordinator(registry, Store(), sender, timeout=2)
    first = asyncio.create_task(wake.get_device("phone"))
    second = asyncio.create_task(wake.get_device("phone"))
    assert await asyncio.to_thread(sender.sent.wait, 1)
    first.cancel()
    with pytest.raises(asyncio.CancelledError):
        await first
    device = Device("phone", "Phone", [], Socket())
    await registry.attach(device)
    assert await second is device
    assert sender.tokens == ["token"]
    assert await wake.get_device("phone") is device
    assert sender.tokens == ["token"]
    await wake.close()
    await registry.close()
