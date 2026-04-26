from __future__ import annotations

from runtime.nlu_main import NluMain


def test_noisy_multi_command_recovers_fragment_devices() -> None:
    nlu = NluMain()
    text = (
        "啊啊哦打开啊啊射灯打开他妈的的的二楼的啊窗连额并且把tmd的小孩房间的温度调为26度"
        "打开啊啊客厅的森环系统打开二楼的社等"
    )

    out = nlu.predict(text)
    assert out.multi_commands is not None

    confirmation = str(out.slots.get("_semantic_confirmation_message") or "")
    assert "打开二楼窗帘" in confirmation
    assert "打开二楼射灯" in confirmation
    assert "打开二楼灯" not in confirmation

    normalized_devices = [
        str((item.get("slots") or {}).get("device_type") or "")
        for item in (out.multi_commands or [])
    ]
    assert "窗帘" in normalized_devices
    assert normalized_devices.count("射灯") >= 2
