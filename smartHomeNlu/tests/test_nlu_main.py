from __future__ import annotations

from runtime.nlu_main import _decision_to_intent_json
from runtime.nlu_rule_engine import SemanticCommand, SemanticDecision, SlotValue


def test_decision_maps_automation_without_backup_sub_intent() -> None:
    decision = SemanticDecision(
        commands=[
            SemanticCommand(
                intent="automation_create",
                action=SlotValue(raw="打开", normalized="打开"),
                device=SlotValue(raw="灯", normalized="灯"),
                confidence=0.94,
            )
        ],
        overall_confidence=0.94,
    )

    out = _decision_to_intent_json(decision)

    assert out.intent == "SYSTEM"
    assert out.sub_intent == "automation_create"


def test_decision_embeds_semantic_confirmation_hints_into_slots() -> None:
    decision = SemanticDecision(
        commands=[
            SemanticCommand(
                intent="device_control",
                action=SlotValue(raw="打开", normalized="打开"),
                device=SlotValue(raw="灯", normalized="灯"),
                location=SlotValue(raw="客厅", normalized="客厅"),
                confidence=0.92,
            )
        ],
        requires_confirmation=True,
        confirmation_message="请确认目标设备后再执行。",
        overall_confidence=0.92,
    )

    out = _decision_to_intent_json(decision)

    assert out.slots.get("_semantic_requires_confirmation") is True
    assert out.slots.get("_semantic_confirmation_message") == "请确认目标设备后再执行。"
