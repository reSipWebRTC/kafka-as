from __future__ import annotations

import json
import re
import time
from typing import Any, Dict, Tuple

from .contracts import ExecutionResult, PolicyDecision
from .debug_log import compact, get_logger
from .event_bus import InMemoryEventBus
from .observability import Observability
from .redis_backend import RedisStateBackend
from .utils import intent_to_domain, monotonic_ms

RETRYABLE_CODES = {"UPSTREAM_TIMEOUT", "UPSTREAM_ERROR"}


class Executor:
    def __init__(
        self,
        *,
        event_bus: InMemoryEventBus,
        adapter: Any,
        observability: Observability,
        state_backend: RedisStateBackend | None = None,
        dedup_window_sec: int = 30,
    ) -> None:
        self.event_bus = event_bus
        self.adapter = adapter
        self.observability = observability
        self._state_backend = state_backend
        self.dedup_window_sec = dedup_window_sec
        self._dedup_cache: Dict[str, Tuple[float, Dict[str, Any]]] = {}
        self._logger = get_logger("executor")

    def _dedup_get(self, key: str) -> Dict[str, Any] | None:
        if self._state_backend is not None:
            return self._state_backend.get_dedup(key)

        cached = self._dedup_cache.get(key)
        if not cached:
            return None
        expires_at, payload = cached
        if time.time() > expires_at:
            del self._dedup_cache[key]
            return None
        return dict(payload)

    def _dedup_set(self, key: str, payload: Dict[str, Any]) -> None:
        if self._state_backend is not None:
            self._state_backend.set_dedup(key, payload, ttl_sec=self.dedup_window_sec)
            return
        self._dedup_cache[key] = (time.time() + self.dedup_window_sec, dict(payload))

    @staticmethod
    def _parse_cn_num(token: str) -> int | None:
        text = str(token or "").strip()
        if not text:
            return None
        if text.isdigit():
            return int(text)
        mapping = {
            "零": 0,
            "〇": 0,
            "一": 1,
            "二": 2,
            "两": 2,
            "三": 3,
            "四": 4,
            "五": 5,
            "六": 6,
            "七": 7,
            "八": 8,
            "九": 9,
            "十": 10,
        }
        if text in mapping:
            return mapping[text]
        if "十" in text:
            head, _, tail = text.partition("十")
            if head:
                tens = mapping.get(head)
                if tens is None:
                    return None
            else:
                tens = 1
            ones = 0
            if tail:
                ones_val = mapping.get(tail)
                if ones_val is None:
                    return None
                ones = ones_val
            return tens * 10 + ones
        return None

    @classmethod
    def _parse_time_expression(cls, value: Any) -> str | None:
        text = str(value or "").strip()
        if not text:
            return None

        hhmm = re.search(r"(\d{1,2})[:：](\d{1,2})", text)
        if hhmm:
            hour = int(hhmm.group(1))
            minute = int(hhmm.group(2))
            if 0 <= hour <= 23 and 0 <= minute <= 59:
                return f"{hour:02d}:{minute:02d}:00"

        point = re.search(r"([零〇一二两三四五六七八九十\d]{1,3})点(?:([零〇一二两三四五六七八九十\d]{1,3})分?)?", text)
        if point:
            hour = cls._parse_cn_num(point.group(1))
            minute = cls._parse_cn_num(point.group(2) or "0")
            if hour is not None and minute is not None and 0 <= hour <= 23 and 0 <= minute <= 59:
                return f"{hour:02d}:{minute:02d}:00"
        return None

    @staticmethod
    def _invalid_tool_call(*, tool_name: str, service_name: str, error_code: str, error: str, entity_id: str | None) -> Dict[str, Any]:
        return {
            "tool_name": tool_name,
            "service_name": service_name,
            "mode": "invalid",
            "error_code": error_code,
            "error": error,
            "entity_id": entity_id,
        }

    def _build_automation_create_call(self, intent_json: Dict[str, Any], entity_id: str | None) -> Dict[str, Any]:
        slots = intent_json.get("slots", {})
        target_entity = str(entity_id or slots.get("entity_id", "")).strip()
        if not target_entity:
            return self._invalid_tool_call(
                tool_name="ha_create_automation",
                service_name="automation.create",
                error_code="ENTITY_NOT_FOUND",
                error="automation target entity_id is required",
                entity_id=None,
            )

        trigger_data = slots.get("trigger") if isinstance(slots.get("trigger"), dict) else {}
        time_expr = str(
            slots.get("time_expression")
            or trigger_data.get("time_expression")
            or ""
        ).strip()
        at = self._parse_time_expression(time_expr)
        if not at:
            return self._invalid_tool_call(
                tool_name="ha_create_automation",
                service_name="automation.create",
                error_code="BAD_REQUEST",
                error="automation time_expression is required (e.g. 07:30 or 7点30分)",
                entity_id=target_entity,
            )

        action_token = str(slots.get("action", "")).strip().lower()
        attribute = str(slots.get("attribute", "")).strip()
        value = slots.get("value")
        value_unit = str(slots.get("value_unit", "")).strip()

        domain = target_entity.split(".")[0] if "." in target_entity else intent_to_domain("power_on", slots)
        service = "turn_on"
        action_data: Dict[str, Any] = {}
        if any(token in action_token for token in ("关闭", "关掉", "关上", "off", "turn_off", "power_off", "close", "stop")):
            service = "turn_off"
        elif any(token in action_token for token in ("解锁", "开锁", "unlock", "open_lock")):
            domain = "lock"
            service = "unlock"
        elif attribute == "温度" or value_unit == "℃":
            domain = "climate"
            service = "set_temperature"
            if value in (None, ""):
                return self._invalid_tool_call(
                    tool_name="ha_create_automation",
                    service_name="automation.create",
                    error_code="BAD_REQUEST",
                    error="temperature automation requires value",
                    entity_id=target_entity,
                )
            action_data["temperature"] = int(value)
        elif attribute == "亮度" or value_unit == "%":
            domain = "light"
            service = "turn_on"
            if value not in (None, ""):
                action_data["brightness_pct"] = int(value)

        alias_seed = str(
            slots.get("automation_alias")
            or slots.get("automation_query")
            or f"{slots.get('location', '')}{slots.get('device_type', '')}".strip()
            or target_entity
        ).strip()
        alias = f"自动化_{alias_seed}"[:60]

        action: Dict[str, Any] = {
            "service": f"{domain}.{service}",
            "target": {"entity_id": target_entity},
        }
        if action_data:
            action["data"] = action_data

        config = {
            "alias": alias,
            "description": "Runtime generated automation",
            "trigger": [{"platform": "time", "at": at}],
            "action": [action],
            "mode": "single",
        }
        return {
            "tool_name": "ha_create_automation",
            "service_name": "automation.create",
            "mode": "tool",
            "params": {"config": json.dumps(config, ensure_ascii=False)},
            "entity_id": target_entity,
        }

    def _build_automation_remove_call(self, intent_json: Dict[str, Any], entity_id: str | None) -> Dict[str, Any]:
        slots = intent_json.get("slots", {})
        identifier = str(
            slots.get("automation_id")
            or slots.get("entity_id")
            or entity_id
            or ""
        ).strip()
        if not identifier:
            return self._invalid_tool_call(
                tool_name="ha_remove_automation",
                service_name="automation.remove",
                error_code="ENTITY_NOT_FOUND",
                error="automation identifier is required",
                entity_id=None,
            )
        return {
            "tool_name": "ha_remove_automation",
            "service_name": "automation.remove",
            "mode": "tool",
            "params": {"identifier": identifier},
            "entity_id": identifier if "." in identifier else entity_id,
        }

    def _build_call(self, intent_json: Dict[str, Any], entity_id: str | None) -> Dict[str, Any]:
        intent = intent_json["intent"]
        sub_intent = intent_json["sub_intent"]
        slots = intent_json.get("slots", {})

        if intent == "SYSTEM" and sub_intent == "backup":
            return {
                "tool_name": "ha_create_backup",
                "service_name": "system.create_backup",
                "mode": "tool",
                "params": {},
                "entity_id": None,
            }
        if intent == "SYSTEM" and sub_intent == "automation_create":
            return self._build_automation_create_call(intent_json, entity_id)
        if intent == "SYSTEM" and sub_intent == "automation_cancel":
            return self._build_automation_remove_call(intent_json, entity_id)

        if intent == "QUERY":
            return {
                "tool_name": "ha_get_entity",
                "service_name": "entity.get",
                "mode": "tool",
                "params": {"entity_id": entity_id},
                "entity_id": entity_id,
            }

        domain = entity_id.split(".")[0] if entity_id and "." in entity_id else intent_to_domain(sub_intent, slots)
        service = "turn_on"
        params: Dict[str, Any] = {}

        if sub_intent == "adjust_brightness":
            service = "turn_on"
            if "value" in slots:
                params["brightness_pct"] = int(slots["value"])
        elif sub_intent == "power_on":
            service = "turn_on"
        elif sub_intent == "power_off":
            service = "turn_off"
        elif sub_intent == "set_temperature":
            domain = "climate"
            service = "set_temperature"
            if "value" in slots:
                params["temperature"] = int(slots["value"])
        elif sub_intent == "unlock":
            domain = "lock"
            service = "unlock"
        elif sub_intent == "activate_scene":
            domain = "scene"
            service = "turn_on"

        return {
            "tool_name": "ha_call_service",
            "service_name": f"{domain}.{service}",
            "mode": "service",
            "domain": domain,
            "service": service,
            "params": params,
            "entity_id": entity_id,
        }

    def _invoke_adapter(self, call_plan: Dict[str, Any]) -> Dict[str, Any]:
        if call_plan["mode"] == "tool":
            return self.adapter.tool_call(call_plan["tool_name"], call_plan["params"])
        return self.adapter.call_service(
            domain=call_plan["domain"],
            service=call_plan["service"],
            entity_id=call_plan["entity_id"] or "",
            params=call_plan["params"],
        )

    def _execute_with_retry(self, call_plan: Dict[str, Any], retry_policy: Dict[str, Any]) -> Tuple[Dict[str, Any], int]:
        max_retries = int(retry_policy.get("max_retries", 0))
        backoff_ms = list(retry_policy.get("backoff_ms", []))

        attempt = 0
        while True:
            attempt += 1
            raw = self._invoke_adapter(call_plan)
            self._logger.debug(
                "invoke attempt=%d tool=%s service=%s success=%s raw=%s",
                attempt,
                call_plan.get("tool_name"),
                call_plan.get("service_name"),
                bool(raw.get("success")),
                compact(raw),
            )
            if raw.get("success"):
                return raw, attempt

            code = str(raw.get("error_code", "UPSTREAM_ERROR"))
            if code not in RETRYABLE_CODES:
                return raw, attempt
            if attempt > max_retries:
                return raw, attempt

            delay_ms = backoff_ms[attempt - 1] if attempt - 1 < len(backoff_ms) else (backoff_ms[-1] if backoff_ms else 0)
            if delay_ms > 0:
                time.sleep(delay_ms / 1000.0)

    def run(
        self,
        *,
        trace_id: str,
        session_id: str,
        user_id: str,
        intent_json: Dict[str, Any],
        policy: PolicyDecision,
        resolved_entity_id: str | None,
        confirmed: bool,
    ) -> Dict[str, Any]:
        self._logger.debug(
            "run start trace_id=%s session_id=%s user_id=%s policy=%s intent=%s resolved_entity=%s confirmed=%s",
            trace_id,
            session_id,
            user_id,
            policy.decision,
            f"{intent_json.get('intent')}/{intent_json.get('sub_intent')}",
            resolved_entity_id,
            confirmed,
        )
        if policy.decision == "deny":
            result = ExecutionResult(
                status="blocked",
                tool_name=policy.tool_name,
                entity_id=resolved_entity_id,
                latency_ms=0,
                error_code="FORBIDDEN",
                upstream_status_code=403,
            )
            return {"code": "FORBIDDEN", "execution_result": result}

        if policy.requires_confirmation and not confirmed:
            result = ExecutionResult(
                status="blocked",
                tool_name=policy.tool_name,
                entity_id=resolved_entity_id,
                latency_ms=0,
                error_code="POLICY_CONFIRM_REQUIRED",
                upstream_status_code=409,
            )
            return {"code": "POLICY_CONFIRM_REQUIRED", "execution_result": result}

        cached = self._dedup_get(policy.idempotency_key)
        if cached:
            result = ExecutionResult(
                status=cached["status"],
                tool_name=cached["tool_name"],
                entity_id=cached.get("entity_id"),
                latency_ms=0,
                error_code=cached.get("error_code"),
                upstream_status_code=cached.get("upstream_status_code"),
                deduplicated=True,
            )
            self.event_bus.publish(
                "evt.execution.result.v1",
                {
                    "trace_id": trace_id,
                    "status": result.status,
                    "error_code": result.error_code,
                    "latency_ms": result.latency_ms,
                    "tool_name": result.tool_name,
                    "entity_id": result.entity_id,
                    "deduplicated": True,
                },
            )
            return {"code": "OK", "execution_result": result}

        start_ms = monotonic_ms()
        call_plan = self._build_call(intent_json, resolved_entity_id)
        if call_plan.get("mode") == "invalid":
            code = str(call_plan.get("error_code", "BAD_REQUEST"))
            status_code = 404 if code in {"ENTITY_NOT_FOUND", "NOT_FOUND"} else 400
            result = ExecutionResult(
                status="failure",
                tool_name=str(call_plan.get("tool_name", policy.tool_name)),
                entity_id=call_plan.get("entity_id"),
                latency_ms=0,
                error_code=code,
                upstream_status_code=status_code,
            )
            self.event_bus.publish(
                "evt.execution.result.v1",
                {
                    "trace_id": trace_id,
                    "status": result.status,
                    "error_code": result.error_code,
                    "latency_ms": result.latency_ms,
                    "tool_name": result.tool_name,
                    "entity_id": result.entity_id,
                    "upstream_status_code": result.upstream_status_code,
                    "attempts": 0,
                },
            )
            return {"code": code, "execution_result": result}
        raw, attempts = self._execute_with_retry(call_plan, policy.retry_policy)

        success = bool(raw.get("success"))
        code = "OK" if success else str(raw.get("error_code", "UPSTREAM_ERROR"))
        latency_ms = max(1, monotonic_ms() - start_ms)

        result = ExecutionResult(
            status="success" if success else "failure",
            tool_name=call_plan["tool_name"],
            entity_id=call_plan.get("entity_id"),
            latency_ms=latency_ms,
            error_code=None if success else code,
            upstream_status_code=raw.get("status_code"),
        )
        self._logger.info(
            "run result trace_id=%s code=%s status=%s tool=%s entity_id=%s latency_ms=%s attempts=%s",
            trace_id,
            code,
            result.status,
            result.tool_name,
            result.entity_id,
            result.latency_ms,
            attempts,
        )

        self.event_bus.publish(
            "evt.execution.result.v1",
            {
                "trace_id": trace_id,
                "status": result.status,
                "error_code": result.error_code,
                "latency_ms": result.latency_ms,
                "tool_name": result.tool_name,
                "entity_id": result.entity_id,
                "upstream_status_code": result.upstream_status_code,
                "attempts": attempts,
            },
        )

        self.observability.write_audit(
            user_id=user_id,
            session_id=session_id,
            tool_name=result.tool_name,
            entity_id=result.entity_id or "",
            service=call_plan["service_name"],
            params=call_plan.get("params", {}),
            nlu_intent=f"{intent_json['intent']}/{intent_json['sub_intent']}",
            nlu_confidence=float(intent_json.get("confidence", 0.0)),
            result=result.status,
            latency_ms=result.latency_ms,
            idempotency_key=policy.idempotency_key,
        )

        if success:
            self._dedup_set(
                policy.idempotency_key,
                {
                    "status": result.status,
                    "tool_name": result.tool_name,
                    "entity_id": result.entity_id,
                    "error_code": result.error_code,
                    "upstream_status_code": result.upstream_status_code,
                },
            )

        return {"code": code, "execution_result": result}
