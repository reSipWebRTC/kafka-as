from __future__ import annotations

import os
import re
import time
from typing import Any, Dict, List, Tuple

from .contracts import EntityCandidate, IntentJson, PolicyDecision, make_response
from .debug_log import compact, get_logger
from .nlu_canonicalizer import canonicalize_intent
from .entity_alias_store import EntityAliasStore
from .dst_service import DstService
from .entity_name_utils import build_entity_aliases, clean_entity_name, extract_entity_index, normalize_entity_name
from .entity_resolver import DEVICE_DOMAIN_HINT, EntityResolver
from .event_bus import InMemoryEventBus
from .executor import Executor
from .ha_gateway_adapter import HaGatewayAdapter
from .ha_mcp_adapter import HaMcpAdapter
from .hard_example_collector import HardExampleCollector
from .nlu_router import NluRouter
from .observability import Observability
from .policy_engine import PolicyEngine
from .redis_backend import RedisStateBackend
from .response_service import ResponseService
from .utils import new_id


class SmartHomeRuntime:
    _logger = get_logger("api")

    DEFAULT_HA_BUILTIN_ENTITIES = {
        "sun.sun",
        "sensor.date",
        "sensor.time",
        "sensor.time_utc",
        "sensor.date_time",
        "sensor.date_time_utc",
        "sensor.date_time_iso",
        "sensor.time_date",
        "sensor.time_date_utc",
        "binary_sensor.updater",
    }
    DEFAULT_HA_BUILTIN_PREFIXES = (
        "update.home_assistant_",
    )
    _MULTI_DEVICE_INDEX_PATTERNS = (
        (r"第?\s*1(?:路|号|个|位)?", 1),
        (r"第?\s*一(?:路|号|个|位)?", 1),
        (r"第?\s*2(?:路|号|个|位)?", 2),
        (r"第?\s*二(?:路|号|个|位)?", 2),
        (r"第?\s*3(?:路|号|个|位)?", 3),
        (r"第?\s*三(?:路|号|个|位)?", 3),
    )
    _MULTI_ACTION_PATTERN = re.compile(r"(打开|开启|关掉|关闭|关上|调到|调成|调高|调低|设为|调为|查询|解锁|开锁)")
    _MULTI_SEPARATOR_PATTERN = re.compile(r"(；|;|，|,|并且|然后|再|同时|接着|还有|以及)")
    _BA_CLAUSE_PROTECT_PATTERN = re.compile(
        r"(?:把|将|给)"
        r"[^；;,，。]{0,30}?"
        r"(?:调到|调为|设为|设置为|设置成|调成|调高|调低|调亮|调暗|拉开|拉上|打开|关闭|关上|关掉)"
        r"(?:百分之[零〇一二三四五六七八九十百两\d]+|[零〇一二三四五六七八九十百两\d]+(?:\.\d+)?(?:度|%|％|级|档|分|分钟|小时)?|全开|全关|一半)?"
    )
    _SAME_NAME_PENDING_KEY = "same_name_disambiguation"
    _SAME_NAME_PENDING_TTL_MS = 60_000
    _INDEX_ONLY_FOLLOWUP_PATTERN = re.compile(
        r"\s*(?:请\s*)?(?:选|选择|就选|就|我要|我选)?\s*第?\s*(?:1|2|3|一|二|三)(?:路|号|个|位)?\s*(?:吧|呢|呀|啊|就行|即可|好了)?\s*"
    )

    @staticmethod
    def _build_env_adapter() -> Any:
        control_mode = (os.getenv("SMARTHOME_HA_CONTROL_MODE") or "auto").strip().lower()

        gateway_url = (os.getenv("SMARTHOME_HA_GATEWAY_URL") or "").strip()
        gateway_timeout = float(os.getenv("SMARTHOME_HA_GATEWAY_TIMEOUT_SEC") or "8")

        mcp_url = (os.getenv("SMARTHOME_HA_MCP_URL") or "").strip()
        mcp_token = (os.getenv("SMARTHOME_HA_MCP_TOKEN") or "").strip() or None
        mcp_timeout = float(os.getenv("SMARTHOME_HA_MCP_TIMEOUT_SEC") or "8")

        if control_mode in {"ha_gateway", "gateway"}:
            return HaGatewayAdapter(gateway_url=gateway_url or None, timeout_sec=gateway_timeout)
        if control_mode in {"ha_mcp", "mcp"}:
            return HaMcpAdapter(mcp_url=mcp_url or None, mcp_token=mcp_token, timeout_sec=mcp_timeout)

        # auto mode
        if gateway_url:
            return HaGatewayAdapter(gateway_url=gateway_url, timeout_sec=gateway_timeout)
        if mcp_url:
            return HaMcpAdapter(mcp_url=mcp_url, mcp_token=mcp_token, timeout_sec=mcp_timeout)
        return HaGatewayAdapter(timeout_sec=gateway_timeout)

    @staticmethod
    def _capture_route(runtime: "SmartHomeRuntime") -> Tuple[Dict[str, Any], Any]:
        captured: Dict[str, Any] = {}
        original = runtime.router.route

        def _wrapped_route(*args: Any, **kwargs: Any) -> Dict[str, Any]:
            result = original(*args, **kwargs)
            intent_obj = result.get("intent_json")
            if hasattr(intent_obj, "as_dict"):
                intent_dict = intent_obj.as_dict()
            else:
                intent_dict = {}
            captured.update(
                {
                    "route": result.get("route"),
                    "need_clarify": bool(result.get("need_clarify", False)),
                    "intent": intent_dict.get("intent"),
                    "sub_intent": intent_dict.get("sub_intent"),
                    "confidence": intent_dict.get("confidence"),
                    "threshold": result.get("threshold"),
                }
            )
            return result

        runtime.router.route = _wrapped_route  # type: ignore[assignment]
        return captured, original

    @staticmethod
    def _extract_call_chain(runtime: "SmartHomeRuntime") -> List[Dict[str, Any]]:
        events = runtime.event_bus.events("evt.execution.result.v1")
        chain: List[Dict[str, Any]] = []
        for event in events:
            chain.append(
                {
                    "tool_name": event.get("tool_name"),
                    "status": event.get("status"),
                    "error_code": event.get("error_code"),
                    "entity_id": event.get("entity_id"),
                    "latency_ms": event.get("latency_ms"),
                    "attempts": event.get("attempts"),
                    "deduplicated": bool(event.get("deduplicated", False)),
                }
            )
        return chain

    @staticmethod
    def _tool_seq(chain: List[Dict[str, Any]]) -> List[str]:
        return [str(item.get("tool_name", "")) for item in chain if item.get("tool_name")]

    @staticmethod
    def _check_equal(name: str, left: Any, right: Any) -> Dict[str, Any]:
        return {
            "name": name,
            "pass": left == right,
            "left": left,
            "right": right,
        }

    @classmethod
    def _build_consistency(cls, gw: Dict[str, Any], mcp: Dict[str, Any]) -> Dict[str, Any]:
        checks = [
            cls._check_equal("response.code", (gw.get("response") or {}).get("code"), (mcp.get("response") or {}).get("code")),
            cls._check_equal(
                "response.data.status",
                ((gw.get("response") or {}).get("data") or {}).get("status"),
                ((mcp.get("response") or {}).get("data") or {}).get("status"),
            ),
            cls._check_equal("route.route", (gw.get("route") or {}).get("route"), (mcp.get("route") or {}).get("route")),
            cls._check_equal("route.intent", (gw.get("route") or {}).get("intent"), (mcp.get("route") or {}).get("intent")),
            cls._check_equal("route.sub_intent", (gw.get("route") or {}).get("sub_intent"), (mcp.get("route") or {}).get("sub_intent")),
            cls._check_equal(
                "call_chain.tool_name",
                cls._tool_seq(list(gw.get("call_chain") or [])),
                cls._tool_seq(list(mcp.get("call_chain") or [])),
            ),
        ]
        return {"pass": all(item["pass"] for item in checks), "checks": checks}

    def __init__(
        self,
        *,
        redis_url: str | None = None,
        redis_client: Any | None = None,
        adapter: Any | None = None,
    ) -> None:
        self._redis_url = redis_url
        self._redis_client = redis_client
        self.event_bus = InMemoryEventBus()
        self.observability = Observability()
        if adapter is not None:
            self.adapter = adapter
        else:
            self.adapter = self._build_env_adapter()
        self.entity_alias_store = EntityAliasStore()
        self.state_backend = RedisStateBackend(redis_url=redis_url, redis_client=redis_client)
        self.dst = DstService(state_backend=self.state_backend)
        self.router = NluRouter(self.event_bus)
        remote_like_modes = {"ha_gateway", "ha_mcp", "remote_mcp"}
        initial_entities = [] if self.adapter.mode in remote_like_modes else self.adapter.get_all_entities()
        initial_entities = self._apply_alias_overrides(initial_entities)
        self.entity_resolver = EntityResolver(self.event_bus, entities=initial_entities)
        self.policy_engine = PolicyEngine(self.event_bus, state_backend=self.state_backend)
        self.executor = Executor(
            event_bus=self.event_bus,
            adapter=self.adapter,
            observability=self.observability,
            state_backend=self.state_backend,
        )
        self.response_service = ResponseService()
        self.hard_example_collector = HardExampleCollector(self.event_bus)

    @staticmethod
    def _env_float(name: str, default: float) -> float:
        raw = os.getenv(name)
        if raw is None:
            return default
        try:
            return float(raw)
        except ValueError:
            return default

    @staticmethod
    def _env_int(name: str, default: int) -> int:
        raw = os.getenv(name)
        if raw is None:
            return default
        try:
            return int(raw)
        except ValueError:
            return default

    def _apply_alias_overrides(self, entities: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        patched: List[Dict[str, Any]] = []
        for item in entities:
            if not isinstance(item, dict):
                continue
            patched.append(self.entity_alias_store.apply(item))
        return patched

    @classmethod
    def _is_default_ha_entity(cls, entity_id: str) -> bool:
        normalized = str(entity_id or "").strip().lower()
        if not normalized:
            return False
        if normalized in cls.DEFAULT_HA_BUILTIN_ENTITIES:
            return True
        return any(normalized.startswith(prefix) for prefix in cls.DEFAULT_HA_BUILTIN_PREFIXES)

    @staticmethod
    def _normalize_entity_name(value: str) -> str:
        return re.sub(r"\s+", "", str(value or "").strip().lower())

    @classmethod
    def _extract_index_hint(cls, text: str) -> int | None:
        content = str(text or "").lower()
        for pattern, number in cls._MULTI_DEVICE_INDEX_PATTERNS:
            if re.search(pattern, content):
                return number
        return None

    @classmethod
    def _is_index_only_followup(cls, text: str) -> bool:
        content = str(text or "").strip().lower()
        if not content:
            return False
        return bool(cls._INDEX_ONLY_FOLLOWUP_PATTERN.fullmatch(content))

    @classmethod
    def _strip_index_tokens(cls, text: str) -> str:
        cleaned = str(text or "")
        for pattern, _number in cls._MULTI_DEVICE_INDEX_PATTERNS:
            cleaned = re.sub(pattern, " ", cleaned)
        return re.sub(r"\s+", " ", cleaned).strip()

    def _remember_same_name_context(
        self,
        *,
        session_id: str,
        user_id: str,
        base_text: str,
    ) -> None:
        state = self.dst.get_session(session_id, user_id)
        pending_slots = dict(state.pending_slots or {})
        normalized_base = self._strip_index_tokens(base_text)
        if not normalized_base:
            normalized_base = str(base_text or "").strip()
        pending_slots[self._SAME_NAME_PENDING_KEY] = {
            "created_at_ms": int(time.time() * 1000),
            "base_text": normalized_base,
        }
        self.dst.patch_session(
            session_id,
            user_id,
            {"pending_slots": pending_slots},
        )

    def _clear_same_name_context(self, *, session_id: str, user_id: str) -> None:
        state = self.dst.get_session(session_id, user_id)
        pending_slots = dict(state.pending_slots or {})
        if pending_slots.pop(self._SAME_NAME_PENDING_KEY, None) is None:
            return
        self.dst.patch_session(
            session_id,
            user_id,
            {"pending_slots": pending_slots},
        )

    def _maybe_apply_same_name_followup(
        self,
        *,
        session_id: str,
        user_id: str,
        text: str,
        session_state: Any,
    ) -> str:
        pending_slots = dict(getattr(session_state, "pending_slots", {}) or {})
        context = pending_slots.get(self._SAME_NAME_PENDING_KEY)
        if not isinstance(context, dict):
            return text

        created_at_raw = context.get("created_at_ms")
        try:
            created_at_ms = int(created_at_raw)
        except (TypeError, ValueError):
            created_at_ms = 0
        now_ms = int(time.time() * 1000)
        if created_at_ms <= 0 or (now_ms - created_at_ms) > self._SAME_NAME_PENDING_TTL_MS:
            self._clear_same_name_context(session_id=session_id, user_id=user_id)
            return text

        if not self._is_index_only_followup(text):
            return text

        index_hint = self._extract_index_hint(text)
        if index_hint is None:
            return text

        base_text = str(context.get("base_text", "")).strip()
        if not base_text:
            self._clear_same_name_context(session_id=session_id, user_id=user_id)
            return text

        rewritten = f"{base_text} 第{index_hint}路"
        self._logger.info(
            "same-name followup rewritten session_id=%s raw=%s effective=%s",
            session_id,
            text,
            rewritten,
        )
        return rewritten

    @classmethod
    def _split_multi_commands(cls, text: str) -> List[str]:
        raw = str(text or "").strip()
        if not raw:
            return []

        def _split_by_actions(segment: str) -> List[str]:
            payload = str(segment or "").strip()
            if not payload:
                return []

            action_matches = list(cls._MULTI_ACTION_PATTERN.finditer(payload))
            if len(action_matches) < 2:
                return [payload]

            protected_ranges: List[Tuple[int, int]] = [
                (match.start(), match.end())
                for match in cls._BA_CLAUSE_PROTECT_PATTERN.finditer(payload)
            ]

            def _inside_protected(index: int) -> bool:
                return any(start < index < end for start, end in protected_ranges)

            parts: List[str] = []
            split_start = 0
            for match in action_matches[1:]:
                split_index = match.start()
                if _inside_protected(split_index):
                    continue
                chunk = payload[split_start:split_index].strip()
                if chunk:
                    parts.append(chunk)
                    split_start = split_index
            tail = payload[split_start:].strip()
            if tail:
                parts.append(tail)
            return parts or [payload]

        hard_parts = [part.strip() for part in re.split(r"[；;]", raw) if str(part).strip()]
        if not hard_parts:
            return []

        split_parts: List[str] = []
        for hard in hard_parts:
            chunks = cls._MULTI_SEPARATOR_PATTERN.split(hard)
            current = ""
            for chunk in chunks:
                token = str(chunk or "").strip()
                if not token:
                    continue
                if cls._MULTI_SEPARATOR_PATTERN.fullmatch(token):
                    if current.strip():
                        split_parts.extend(_split_by_actions(current.strip()))
                        current = ""
                    continue
                current += token
            if current.strip():
                split_parts.extend(_split_by_actions(current.strip()))

        normalized = [part.strip() for part in split_parts if str(part).strip()]
        if len(normalized) < 2:
            return [raw]
        return normalized

    @staticmethod
    def _build_remote_entity_query(
        *,
        slots: Dict[str, Any],
        text: str,
        automation_cancel: bool = False,
    ) -> str:
        query_seed = str(slots.get("automation_query", "") or "").strip()
        if automation_cancel and query_seed:
            return query_seed

        location = str(slots.get("location", "") or "").strip()
        device_query = str(slots.get("_original_device_type") or slots.get("device_type", "") or "").strip()
        query = f"{location}{device_query}".strip()
        if query:
            return query
        return query_seed or str(text or "").strip()

    @classmethod
    def _looks_like_multi_same_name(cls, candidates: List[EntityCandidate]) -> bool:
        if len(candidates) < 2:
            return False
        name_map: Dict[str, int] = {}
        for item in candidates:
            normalized = cls._normalize_entity_name(item.name or "")
            if not normalized:
                continue
            name_map[normalized] = name_map.get(normalized, 0) + 1
        return any(count >= 2 for count in name_map.values())

    @classmethod
    def _pick_candidate_from_hint(
        cls,
        *,
        candidates: List[EntityCandidate],
        text: str,
    ) -> EntityCandidate | None:
        if not candidates:
            return None

        index_hint = cls._extract_index_hint(text)
        if index_hint is None:
            if cls._looks_like_multi_same_name(candidates):
                return None
            return candidates[0]

        if index_hint == 1:
            for item in candidates:
                eid = str(item.entity_id or "").lower()
                if not re.search(r"_\d+$", eid) or eid.endswith("_1"):
                    return item
        for item in candidates:
            eid = str(item.entity_id or "").lower()
            if eid.endswith(f"_{index_hint}"):
                return item

        return None

    def _expand_same_name_candidates(
        self,
        *,
        candidates: List[EntityCandidate],
        domain_hint: str | None,
    ) -> List[EntityCandidate]:
        if not candidates:
            return []

        primary = candidates[0]
        primary_name_norm = self._normalize_entity_name(primary.name or "")
        if not primary_name_norm:
            return candidates

        merged: Dict[str, EntityCandidate] = {str(item.entity_id): item for item in candidates if item.entity_id}

        def _try_add(entity_id: str, name: str, area: str, score: float) -> None:
            eid = str(entity_id or "").strip()
            if not eid:
                return
            if self._normalize_entity_name(name or "") != primary_name_norm:
                return
            if eid in merged:
                return
            merged[eid] = EntityCandidate(
                entity_id=eid,
                score=float(score),
                name=name or eid,
                area=area or "",
            )

        remote_like_modes = {"ha_gateway", "ha_mcp", "remote_mcp"}
        if self.adapter.mode in remote_like_modes:
            query = str(primary.name or primary.entity_id or "").strip()
            if query:
                try:
                    remote_items = self.adapter.search_entities(query=query, domain=domain_hint, limit=100)
                    remote_items = self._apply_alias_overrides(remote_items)
                except Exception:
                    remote_items = []
                for item in remote_items:
                    _try_add(
                        entity_id=str(item.get("entity_id", "")),
                        name=str(item.get("name", "")),
                        area=str(item.get("area", "")),
                        score=float(item.get("score", 0.0)),
                    )
        else:
            for entity in self.entity_resolver.entities:
                _try_add(
                    entity_id=str(entity.get("entity_id", "")),
                    name=str(entity.get("name", "")),
                    area=str(entity.get("area", "")),
                    score=1.0,
                )

        return list(merged.values())

    def _run_compare_channel(
        self,
        *,
        channel: str,
        adapter: Any,
        payload: Dict[str, Any],
    ) -> Dict[str, Any]:
        runtime = SmartHomeRuntime(
            redis_url=self._redis_url,
            redis_client=self._redis_client,
            adapter=adapter,
        )
        route_capture, original_route = self._capture_route(runtime)
        try:
            response = runtime.post_api_v1_command(dict(payload))
        finally:
            runtime.router.route = original_route  # type: ignore[assignment]

        return {
            "channel": channel,
            "adapter_mode": str(runtime.adapter.mode),
            "payload": dict(payload),
            "route": route_capture,
            "call_chain": self._extract_call_chain(runtime),
            "response": response,
        }

    def _trace_id(self, headers: Dict[str, str] | None) -> str:
        headers = headers or {}
        return headers.get("X-Trace-Id") or new_id("trc")

    def _append_history(
        self,
        *,
        session_id: str,
        user_id: str,
        action: str,
        code: str,
        request_text: str,
        reply_text: str = "",
        intent: str | None = None,
        sub_intent: str | None = None,
        metadata: Dict[str, Any] | None = None,
    ) -> None:
        entry: Dict[str, Any] = {
            "ts": int(time.time() * 1000),
            "action": action,
            "session_id": session_id,
            "user_id": user_id,
            "code": code,
            "request_text": request_text,
            "reply_text": reply_text,
            "intent": intent,
            "sub_intent": sub_intent,
        }
        if metadata:
            entry["metadata"] = dict(metadata)
        self.state_backend.append_history(session_id, entry)

    def _with_nlu(
        self,
        reply: Dict[str, Any],
        route_result: Dict[str, Any],
        intent: Any,
    ) -> Dict[str, Any]:
        intent_payload = {
            "intent": intent.intent,
            "sub_intent": intent.sub_intent,
            "slots": intent.slots,
            "confidence": float(intent.confidence),
        }
        reply["nlu"] = {
            "route": route_result.get("route"),
            "route_stage": route_result.get("route_stage", ""),
            "model_version": route_result.get("model_version", ""),
            "intent": intent_payload["intent"],
            "sub_intent": intent_payload["sub_intent"],
            "slots": intent_payload["slots"],
            "confidence": intent_payload["confidence"],
            "intent_json": intent_payload,
        }
        return reply

    @staticmethod
    def _is_truthy(value: Any) -> bool:
        if isinstance(value, bool):
            return value
        if value is None:
            return False
        return str(value).strip().lower() in {"1", "true", "yes", "on"}

    @classmethod
    def _semantic_confirmation_hint(cls, intent: IntentJson) -> Tuple[bool, str | None]:
        slots = intent.slots if isinstance(intent.slots, dict) else {}
        requires = cls._is_truthy(slots.get("_semantic_requires_confirmation"))
        message_raw = slots.get("_semantic_confirmation_message")
        message = str(message_raw).strip() if message_raw is not None else ""
        return requires, (message or None)

    def _confirm_required_response(
        self,
        *,
        trace_id: str,
        session_id: str,
        user_id: str,
        text: str,
        route_result: Dict[str, Any],
        intent: IntentJson,
        policy: PolicyDecision,
        resolved_entity_id: str | None,
        reason: str,
        reply_text: str,
    ) -> Dict[str, Any]:
        confirm = self.policy_engine.confirm_start(
            idempotency_key=policy.idempotency_key,
            risk_level=policy.risk_level,
        )
        policy.confirm_token = confirm["confirm_token"]
        self.state_backend.set_pending_command(
            confirm["confirm_token"],
            {
                "trace_id": trace_id,
                "session_id": session_id,
                "user_id": user_id,
                "text": text,
                "intent_json": intent.as_dict(),
                "policy": policy.as_dict(),
                "resolved_entity_id": resolved_entity_id,
            },
            ttl_sec=confirm["expires_in_sec"],
        )
        data = {
            "status": "confirm_required",
            "reply_text": reply_text,
            "intent": intent.intent,
            "sub_intent": intent.sub_intent,
            "confirm_reason": reason,
            "confirm_token": confirm["confirm_token"],
            "expires_in_sec": confirm["expires_in_sec"],
        }
        data = self._with_nlu(data, route_result, intent)
        result = make_response(
            trace_id,
            code="POLICY_CONFIRM_REQUIRED",
            message="confirmation required",
            data=data,
        )
        self._append_history(
            session_id=session_id,
            user_id=user_id,
            action="command",
            code="POLICY_CONFIRM_REQUIRED",
            request_text=text,
            reply_text=str(data.get("reply_text", "")),
            intent=intent.intent,
            sub_intent=intent.sub_intent,
            metadata={
                "confirm_token": str(confirm["confirm_token"]),
                "confirm_reason": reason,
            },
        )
        return result

    @staticmethod
    def _describe_sub_command(sub_intent: IntentJson) -> str:
        action_map = {
            "power_on": "打开",
            "power_off": "关闭",
            "set_temperature": "设置温度",
            "adjust_brightness": "调节亮度",
            "query_status": "查询状态",
            "activate_scene": "激活场景",
        }
        action = action_map.get(sub_intent.sub_intent, "")
        location = str(sub_intent.slots.get("location", ""))
        device = str(sub_intent.slots.get("device_type", ""))
        value = sub_intent.slots.get("value")
        parts = [action, location, device]
        if value is not None:
            parts.append(str(value))
            unit = str(sub_intent.slots.get("value_unit", ""))
            if unit:
                parts.append(unit)
        return "".join(p for p in parts if p) or sub_intent.sub_intent

    def _process_multi_commands(
        self,
        *,
        trace_id: str,
        session_id: str,
        user_id: str,
        user_role: str,
        text: str,
        route_result: Dict[str, Any],
        primary_intent: IntentJson,
    ) -> Dict[str, Any]:
        multi_commands = primary_intent.multi_commands or []
        items: List[Dict[str, Any]] = []

        for idx, cmd_dict in enumerate(multi_commands):
            sub = IntentJson(
                intent=cmd_dict.get("intent", "CONTROL"),
                sub_intent=cmd_dict.get("sub_intent", "unknown"),
                slots=cmd_dict.get("slots", {}),
                confidence=cmd_dict.get("confidence", 0.5),
            )
            sub = canonicalize_intent(sub)

            if sub.intent == "CHITCHAT" or sub.sub_intent in {"unknown", "clarify_needed"}:
                items.append({
                    "index": idx,
                    "text": self._describe_sub_command(sub),
                    "code": "OK",
                    "status": "clarify",
                    "entity_id": None,
                    "nlu": {
                        "intent": sub.intent,
                        "sub_intent": sub.sub_intent,
                        "slots": sub.slots,
                        "confidence": float(sub.confidence),
                    },
                })
                continue

            # ── entity resolution ──
            resolved_entity_id = sub.slots.get("entity_id")
            needs_resolution = sub.intent in {"CONTROL", "QUERY", "SCENE"} or (
                sub.intent == "SYSTEM" and sub.sub_intent in {"automation_create", "automation_cancel"}
            )
            if needs_resolution and not resolved_entity_id:
                domain_hint = None
                if sub.intent == "SCENE":
                    domain_hint = "scene"
                elif sub.intent == "SYSTEM" and sub.sub_intent == "automation_cancel":
                    domain_hint = "automation"
                elif sub.intent == "SYSTEM" and sub.sub_intent == "automation_create":
                    domain_hint = DEVICE_DOMAIN_HINT.get(str(sub.slots.get("device_type", "")).strip())
                candidates = self.entity_resolver.resolve(
                    trace_id=trace_id,
                    slots=sub.slots,
                    domain_hint=domain_hint,
                    top_k=3,
                )
                if not candidates and self.adapter.mode in {"ha_gateway", "ha_mcp", "remote_mcp"}:
                    remote_domain_hint = domain_hint or DEVICE_DOMAIN_HINT.get(
                        str(sub.slots.get("device_type", "")).strip()
                    )
                    query = self._build_remote_entity_query(
                        slots=sub.slots,
                        text=text,
                        automation_cancel=bool(sub.intent == "SYSTEM" and sub.sub_intent == "automation_cancel"),
                    )
                    try:
                        remote_entities = self.adapter.search_entities(
                            query=query, domain=remote_domain_hint, limit=3,
                        )
                    except Exception:
                        remote_entities = []
                    remote_entities = self._apply_alias_overrides(remote_entities)
                    candidates = [
                        EntityCandidate(
                            entity_id=str(e.get("entity_id", "")),
                            score=float(e.get("score", 0.0)),
                            name=str(e.get("name", e.get("entity_id", ""))),
                            area=str(e.get("area", "")),
                        )
                        for e in remote_entities
                        if e.get("entity_id")
                    ]
                    if not candidates and remote_domain_hint:
                        try:
                            retry_entities = self.adapter.search_entities(
                                query=query if len(query) >= 2 else text,
                                domain=None, limit=3,
                            )
                        except Exception:
                            retry_entities = []
                        retry_entities = self._apply_alias_overrides(retry_entities)
                        candidates = [
                            EntityCandidate(
                                entity_id=str(e.get("entity_id", "")),
                                score=float(e.get("score", 0.0)),
                                name=str(e.get("name", e.get("entity_id", ""))),
                                area=str(e.get("area", "")),
                            )
                            for e in retry_entities
                            if e.get("entity_id")
                        ]

                if not candidates and (sub.intent != "SYSTEM" or sub.sub_intent in {"automation_create", "automation_cancel"}):
                    items.append({
                        "index": idx,
                        "text": self._describe_sub_command(sub),
                        "code": "ENTITY_NOT_FOUND",
                        "status": "entity_not_found",
                        "entity_id": None,
                        "nlu": {
                            "intent": sub.intent,
                            "sub_intent": sub.sub_intent,
                            "slots": sub.slots,
                            "confidence": float(sub.confidence),
                        },
                    })
                    continue

                if candidates:
                    resolved_entity_id = candidates[0].entity_id
                    sub.slots["entity_id"] = resolved_entity_id

            # ── policy + execute ──
            policy = self.policy_engine.evaluate(
                trace_id=trace_id,
                user_id=user_id,
                user_role=user_role,
                intent_json=sub.as_dict(),
            )

            if policy.decision == "deny":
                items.append({
                    "index": idx,
                    "text": self._describe_sub_command(sub),
                    "code": "FORBIDDEN",
                    "status": "denied",
                    "entity_id": resolved_entity_id,
                    "nlu": {
                        "intent": sub.intent,
                        "sub_intent": sub.sub_intent,
                        "slots": sub.slots,
                        "confidence": float(sub.confidence),
                    },
                })
                continue

            exec_out = self.executor.run(
                trace_id=trace_id,
                session_id=session_id,
                user_id=user_id,
                intent_json=sub.as_dict(),
                policy=policy,
                resolved_entity_id=resolved_entity_id,
                confirmed=True,
            )

            exec_status = exec_out["execution_result"].status
            item_status = "ok" if exec_status == "success" else "failed"
            if exec_status != "success":
                self.hard_example_collector.collect_execution_failure(
                    trace_id=trace_id,
                    session_id=session_id,
                    user_id=user_id,
                    text=text,
                    error_code=str(exec_out["execution_result"].error_code or exec_out["code"]),
                    intent_json=sub.as_dict(),
                    tool_name=exec_out["execution_result"].tool_name,
                    entity_id=resolved_entity_id,
                )

            items.append({
                "index": idx,
                "text": self._describe_sub_command(sub),
                "code": exec_out["code"],
                "status": item_status,
                "entity_id": resolved_entity_id,
                "nlu": {
                    "intent": sub.intent,
                    "sub_intent": sub.sub_intent,
                    "slots": sub.slots,
                    "confidence": float(sub.confidence),
                },
            })

        total = len(items)
        ok = sum(1 for it in items if it.get("status") == "ok")
        if ok == total:
            reply_text = f"已处理{total}条指令，全部成功。"
            overall_status = "ok"
        else:
            reply_text = f"已处理{total}条指令，成功{ok}条。"
            overall_status = "partial"

        reply: Dict[str, Any] = {
            "status": overall_status,
            "reply_text": reply_text,
            "tts_text": reply_text,
            "intent": primary_intent.intent,
            "sub_intent": "multi_command",
            "items": items,
        }
        return reply

    def post_api_v1_command(self, payload: Dict[str, Any], headers: Dict[str, str] | None = None) -> Dict[str, Any]:
        trace_id = self._trace_id(headers)

        required = ("session_id", "user_id", "text")
        if any(not payload.get(field) for field in required):
            return make_response(trace_id, code="BAD_REQUEST", message="missing required fields")

        session_id = str(payload["session_id"])
        user_id = str(payload["user_id"])
        text = str(payload["text"])
        user_role = str(payload.get("user_role", "normal_user"))

        self.event_bus.publish(
            "evt.request.received.v1",
            {
                "trace_id": trace_id,
                "user_id": user_id,
                "session_id": session_id,
                "text": text,
            },
        )

        session_state = self.dst.get_session(session_id, user_id)
        text = self._maybe_apply_same_name_followup(
            session_id=session_id,
            user_id=user_id,
            text=text,
            session_state=session_state,
        )
        route_result = self.router.route(
            trace_id=trace_id,
            text=text,
            context=session_state.as_dict(),
        )

        intent = route_result["intent_json"]
        merged_slots = self.dst.inherit_slots(session_id, user_id, intent.slots)
        intent.slots = merged_slots
        if not intent.multi_commands or len(intent.multi_commands) <= 1:
            segments = self._split_multi_commands(text)
            if len(segments) > 1:
                synthetic_multi: List[Dict[str, Any]] = []
                for segment in segments:
                    seg_route = self.router.route(
                        trace_id=trace_id,
                        text=segment,
                        context=session_state.as_dict(),
                    )
                    seg_intent = seg_route["intent_json"]
                    seg_intent.slots = self.dst.inherit_slots(session_id, user_id, seg_intent.slots)
                    synthetic_multi.append(seg_intent.as_dict())
                if synthetic_multi:
                    intent.multi_commands = synthetic_multi
        threshold = route_result["threshold"]

        self._logger.info(
            "command semantic trace_id=%s route=%s/%s intent=%s/%s conf=%.3f slots=%s entity=%s",
            trace_id,
            route_result.get("route"),
            route_result.get("route_stage", ""),
            intent.intent,
            intent.sub_intent,
            float(intent.confidence),
            compact(intent.slots, max_len=400),
            intent.slots.get("entity_id"),
        )

        if route_result["route"] == "main":
            if threshold["fallback_trigger"] <= intent.confidence < threshold["main_pass"]:
                self.hard_example_collector.collect_low_confidence(
                    trace_id=trace_id,
                    session_id=session_id,
                    user_id=user_id,
                    text=text,
                    route="main",
                    reason="main_low_confidence",
                    intent_json=intent.as_dict(),
                )
        elif intent.confidence < 0.70:
            self.hard_example_collector.collect_low_confidence(
                trace_id=trace_id,
                session_id=session_id,
                user_id=user_id,
                text=text,
                route="fallback",
                reason="fallback_low_confidence",
                intent_json=intent.as_dict(),
            )

        if route_result["need_clarify"]:
            streak = self.dst.mark_low_confidence(session_id, user_id)
            if streak >= 3:
                clarify = "我连续三次没有理解清楚，请换个说法。"
            else:
                clarify = "我还不确定要控制哪个设备，请补充设备名或位置。"
            reply = self.response_service.render(
                intent_json=intent.as_dict(),
                execution_result=None,
                clarify_text=clarify,
            )
            reply = self._with_nlu(reply, route_result, intent)
            result = make_response(trace_id, data=reply)
            self._append_history(
                session_id=session_id,
                user_id=user_id,
                action="command",
                code=str(result["code"]),
                request_text=text,
                reply_text=str(reply.get("reply_text", "")),
                intent=intent.intent,
                sub_intent=intent.sub_intent,
            )
            return result

        # 一旦进入有效语义链路，清理上一次“同名设备补充路号”上下文，
        # 避免后续无关“第X路”短句误命中旧语义。
        self._clear_same_name_context(session_id=session_id, user_id=user_id)

        if intent.intent == "CHITCHAT" and intent.sub_intent in {"chitchat", "unknown"}:
            reply = {
                "status": "ok",
                "reply_text": "我主要负责智能家居控制，你可以让我开灯、调温度或查询设备状态。",
                "tts_text": "我主要负责智能家居控制，你可以让我开灯、调温度或查询设备状态。",
                "intent": intent.intent,
                "sub_intent": intent.sub_intent,
            }
            reply = self._with_nlu(reply, route_result, intent)
            result = make_response(trace_id, data=reply)
            self._append_history(
                session_id=session_id,
                user_id=user_id,
                action="command",
                code=str(result["code"]),
                request_text=text,
                reply_text=str(reply.get("reply_text", "")),
                intent=intent.intent,
                sub_intent=intent.sub_intent,
            )
            return result

        # ── multi-command early return ──
        if intent.multi_commands and len(intent.multi_commands) > 1:
            reply = self._process_multi_commands(
                trace_id=trace_id,
                session_id=session_id,
                user_id=user_id,
                user_role=user_role,
                text=text,
                route_result=route_result,
                primary_intent=intent,
            )
            reply = self._with_nlu(reply, route_result, intent)
            result = make_response(trace_id, data=reply)
            self._append_history(
                session_id=session_id,
                user_id=user_id,
                action="command",
                code=str(result["code"]),
                request_text=text,
                reply_text=str(reply.get("reply_text", "")),
                intent=intent.intent,
                sub_intent="multi_command",
            )
            return result

        resolved_entity_id = intent.slots.get("entity_id")
        needs_resolution = intent.intent in {"CONTROL", "QUERY", "SCENE"} or (
            intent.intent == "SYSTEM" and intent.sub_intent in {"automation_create", "automation_cancel"}
        )
        if needs_resolution and not resolved_entity_id:
            domain_hint = None
            if intent.intent == "SCENE":
                domain_hint = "scene"
            elif intent.intent == "SYSTEM" and intent.sub_intent == "automation_cancel":
                domain_hint = "automation"
            elif intent.intent == "SYSTEM" and intent.sub_intent == "automation_create":
                domain_hint = DEVICE_DOMAIN_HINT.get(str(intent.slots.get("device_type", "")).strip())
            candidates = self.entity_resolver.resolve(
                trace_id=trace_id,
                slots=intent.slots,
                domain_hint=domain_hint,
                top_k=payload.get("top_k", 3),
            )
            if not candidates and self.adapter.mode in {"ha_gateway", "ha_mcp", "remote_mcp"}:
                remote_domain_hint = domain_hint or DEVICE_DOMAIN_HINT.get(str(intent.slots.get("device_type", "")).strip())
                query = self._build_remote_entity_query(
                    slots=intent.slots,
                    text=text,
                    automation_cancel=bool(intent.intent == "SYSTEM" and intent.sub_intent == "automation_cancel"),
                )
                remote_entities = self.adapter.search_entities(
                    query=query,
                    domain=remote_domain_hint,
                    limit=payload.get("top_k", 3),
                )
                remote_entities = self._apply_alias_overrides(remote_entities)
                candidates = [
                    EntityCandidate(
                        entity_id=str(entity.get("entity_id", "")),
                        score=float(entity.get("score", 0.0)),
                        name=str(entity.get("name", entity.get("entity_id", ""))),
                        area=str(entity.get("area", "")),
                    )
                    for entity in remote_entities
                    if entity.get("entity_id")
                ]
                if not candidates and remote_domain_hint:
                    retry_entities = self.adapter.search_entities(
                        query=query if len(query) >= 2 else text,
                        domain=None,
                        limit=payload.get("top_k", 3),
                    )
                    retry_entities = self._apply_alias_overrides(retry_entities)
                    candidates = [
                        EntityCandidate(
                            entity_id=str(entity.get("entity_id", "")),
                            score=float(entity.get("score", 0.0)),
                            name=str(entity.get("name", entity.get("entity_id", ""))),
                            area=str(entity.get("area", "")),
                        )
                        for entity in retry_entities
                        if entity.get("entity_id")
                    ]
            if not candidates and (intent.intent != "SYSTEM" or intent.sub_intent in {"automation_create", "automation_cancel"}):
                self.hard_example_collector.collect_execution_failure(
                    trace_id=trace_id,
                    session_id=session_id,
                    user_id=user_id,
                    text=text,
                    error_code="ENTITY_NOT_FOUND",
                    intent_json=intent.as_dict(),
                    tool_name="ha_search_entities",
                    entity_id=None,
                )
                reply = self.response_service.render(
                    intent_json=intent.as_dict(),
                    execution_result=None,
                    clarify_text=(
                        "没有找到对应自动化，请确认自动化名称。"
                        if intent.intent == "SYSTEM"
                        else "没有找到对应设备，请确认设备名称。"
                    ),
                )
                reply = self._with_nlu(reply, route_result, intent)
                result = make_response(trace_id, code="ENTITY_NOT_FOUND", message="entity not found", data=reply)
                self._append_history(
                    session_id=session_id,
                    user_id=user_id,
                    action="command",
                    code="ENTITY_NOT_FOUND",
                    request_text=text,
                    reply_text=str(reply.get("reply_text", "")),
                    intent=intent.intent,
                    sub_intent=intent.sub_intent,
                )
                return result
            if candidates:
                disambiguation_candidates = self._expand_same_name_candidates(
                    candidates=candidates,
                    domain_hint=domain_hint,
                )
                selected = self._pick_candidate_from_hint(candidates=disambiguation_candidates, text=text)
                if selected is None:
                    self._remember_same_name_context(
                        session_id=session_id,
                        user_id=user_id,
                        base_text=text,
                    )
                    reply = self.response_service.render(
                        intent_json=intent.as_dict(),
                        execution_result=None,
                        clarify_text="检测到多个同名设备，请补充“第1路/第2路/第3路”后重试。",
                    )
                    reply = self._with_nlu(reply, route_result, intent)
                    result = make_response(trace_id, data=reply)
                    self._append_history(
                        session_id=session_id,
                        user_id=user_id,
                        action="command",
                        code=str(result["code"]),
                        request_text=text,
                        reply_text=str(reply.get("reply_text", "")),
                        intent=intent.intent,
                        sub_intent=intent.sub_intent,
                    )
                    return result
                resolved_entity_id = selected.entity_id
                intent.slots["entity_id"] = resolved_entity_id

        policy = self.policy_engine.evaluate(
            trace_id=trace_id,
            user_id=user_id,
            user_role=user_role,
            intent_json=intent.as_dict(),
        )

        if policy.decision == "deny":
            exec_out = self.executor.run(
                trace_id=trace_id,
                session_id=session_id,
                user_id=user_id,
                intent_json=intent.as_dict(),
                policy=policy,
                resolved_entity_id=resolved_entity_id,
                confirmed=False,
            )
            reply = self.response_service.render(intent_json=intent.as_dict(), execution_result=exec_out["execution_result"])
            reply = self._with_nlu(reply, route_result, intent)
            result = make_response(trace_id, code=exec_out["code"], message="permission denied", data=reply)
            self._append_history(
                session_id=session_id,
                user_id=user_id,
                action="command",
                code=str(exec_out["code"]),
                request_text=text,
                reply_text=str(reply.get("reply_text", "")),
                intent=intent.intent,
                sub_intent=intent.sub_intent,
            )
            return result

        semantic_requires_confirmation, semantic_confirm_message = self._semantic_confirmation_hint(intent)
        if semantic_requires_confirmation and not policy.requires_confirmation:
            return self._confirm_required_response(
                trace_id=trace_id,
                session_id=session_id,
                user_id=user_id,
                text=text,
                route_result=route_result,
                intent=intent,
                policy=policy,
                resolved_entity_id=resolved_entity_id,
                reason="semantic_ambiguity",
                reply_text=semantic_confirm_message or "该指令存在语义不确定性，请在确认接口提交确认。",
            )

        if policy.requires_confirmation:
            return self._confirm_required_response(
                trace_id=trace_id,
                session_id=session_id,
                user_id=user_id,
                text=text,
                route_result=route_result,
                intent=intent,
                policy=policy,
                resolved_entity_id=resolved_entity_id,
                reason="policy_risk",
                reply_text="该指令风险较高，请在确认接口提交确认。",
            )

        exec_out = self.executor.run(
            trace_id=trace_id,
            session_id=session_id,
            user_id=user_id,
            intent_json=intent.as_dict(),
            policy=policy,
            resolved_entity_id=resolved_entity_id,
            confirmed=True,
        )
        reply = self.response_service.render(intent_json=intent.as_dict(), execution_result=exec_out["execution_result"])
        reply = self._with_nlu(reply, route_result, intent)

        if exec_out["execution_result"].status == "success":
            self.dst.mark_success_turn(
                session_id,
                user_id,
                intent=intent.intent,
                slots=intent.slots,
                entity_id=resolved_entity_id,
                result="success",
            )
        else:
            self.hard_example_collector.collect_execution_failure(
                trace_id=trace_id,
                session_id=session_id,
                user_id=user_id,
                text=text,
                error_code=str(exec_out["execution_result"].error_code or exec_out["code"]),
                intent_json=intent.as_dict(),
                tool_name=exec_out["execution_result"].tool_name,
                entity_id=resolved_entity_id,
            )

        result = make_response(trace_id, code=exec_out["code"], message="success" if exec_out["code"] == "OK" else "failed", data=reply)
        self._append_history(
            session_id=session_id,
            user_id=user_id,
            action="command",
            code=str(exec_out["code"]),
            request_text=text,
            reply_text=str(reply.get("reply_text", "")),
            intent=intent.intent,
            sub_intent=intent.sub_intent,
        )
        return result

    def post_api_v1_confirm(self, payload: Dict[str, Any], headers: Dict[str, str] | None = None) -> Dict[str, Any]:
        trace_id = self._trace_id(headers)
        token = payload.get("confirm_token")
        accept = bool(payload.get("accept", True))

        if not token:
            return make_response(trace_id, code="BAD_REQUEST", message="missing confirm_token")

        if not accept:
            pending = self.state_backend.get_pending_command(token)
            self.state_backend.delete_pending_command(token)
            result = make_response(
                trace_id,
                data={"status": "cancelled", "reply_text": "已取消本次高风险操作。"},
            )
            if pending:
                intent_json = dict(pending.get("intent_json", {}))
                self._append_history(
                    session_id=str(pending.get("session_id", "")),
                    user_id=str(pending.get("user_id", "")),
                    action="confirm",
                    code=str(result["code"]),
                    request_text=str(pending.get("text", "")),
                    reply_text=str(result.get("data", {}).get("reply_text", "")),
                    intent=str(intent_json.get("intent", "")),
                    sub_intent=str(intent_json.get("sub_intent", "")),
                    metadata={"accept": False},
                )
            return result

        ok, code = self.policy_engine.confirm_commit(token)
        if not ok:
            return make_response(trace_id, code=code, message="confirm failed")

        pending = self.state_backend.get_pending_command(token)
        if not pending:
            return make_response(trace_id, code="NOT_FOUND", message="pending command not found")
        self.state_backend.delete_pending_command(token)

        pending_policy = PolicyDecision(**dict(pending.get("policy", {})))

        exec_out = self.executor.run(
            trace_id=trace_id,
            session_id=pending["session_id"],
            user_id=pending["user_id"],
            intent_json=pending["intent_json"],
            policy=pending_policy,
            resolved_entity_id=pending["resolved_entity_id"],
            confirmed=True,
        )
        reply = self.response_service.render(
            intent_json=pending["intent_json"],
            execution_result=exec_out["execution_result"],
        )

        if exec_out["execution_result"].status == "success":
            self.dst.mark_success_turn(
                pending["session_id"],
                pending["user_id"],
                intent=pending["intent_json"]["intent"],
                slots=pending["intent_json"].get("slots", {}),
                entity_id=pending["resolved_entity_id"],
                result="success",
            )
        else:
            self.hard_example_collector.collect_execution_failure(
                trace_id=trace_id,
                session_id=pending["session_id"],
                user_id=pending["user_id"],
                text=str(pending.get("text", "")),
                error_code=str(exec_out["execution_result"].error_code or exec_out["code"]),
                intent_json=pending["intent_json"],
                tool_name=exec_out["execution_result"].tool_name,
                entity_id=pending["resolved_entity_id"],
            )

        result = make_response(trace_id, code=exec_out["code"], message="success", data=reply)
        self._append_history(
            session_id=str(pending["session_id"]),
            user_id=str(pending["user_id"]),
            action="confirm",
            code=str(exec_out["code"]),
            request_text=str(pending.get("text", "")),
            reply_text=str(reply.get("reply_text", "")),
            intent=str(pending["intent_json"].get("intent", "")),
            sub_intent=str(pending["intent_json"].get("sub_intent", "")),
            metadata={"accept": True},
        )
        return result

    def post_api_v1_compare_channels(self, payload: Dict[str, Any], headers: Dict[str, str] | None = None) -> Dict[str, Any]:
        trace_id = self._trace_id(headers)

        required = ("session_id", "user_id", "text")
        if any(not payload.get(field) for field in required):
            return make_response(trace_id, code="BAD_REQUEST", message="missing required fields")

        session_id = str(payload["session_id"]).strip()
        user_id = str(payload["user_id"]).strip()
        text = str(payload["text"]).strip()
        user_role = str(payload.get("user_role", "normal_user"))
        try:
            top_k_raw = int(payload.get("top_k", 3))
        except (TypeError, ValueError):
            top_k_raw = 3
        top_k = max(1, min(5, top_k_raw))

        isolate_raw = payload.get("isolate_session", True)
        if isinstance(isolate_raw, str):
            isolate_session = isolate_raw.strip().lower() not in {"0", "false", "no", "off"}
        else:
            isolate_session = bool(isolate_raw)

        base = {
            "session_id": session_id,
            "user_id": user_id,
            "text": text,
            "user_role": user_role,
            "top_k": top_k,
        }

        gw_payload = dict(base)
        mcp_payload = dict(base)
        if isolate_session:
            gw_payload["session_id"] = f"{session_id}__cmp_gw"
            mcp_payload["session_id"] = f"{session_id}__cmp_mcp"

        gateway_url = (os.getenv("SMARTHOME_HA_GATEWAY_URL") or "").strip()
        gateway_timeout = self._env_float("SMARTHOME_HA_GATEWAY_TIMEOUT_SEC", 8.0)
        mcp_url = (os.getenv("SMARTHOME_HA_MCP_URL") or "").strip()
        mcp_token = (os.getenv("SMARTHOME_HA_MCP_TOKEN") or "").strip() or None
        mcp_timeout = self._env_float("SMARTHOME_HA_MCP_TIMEOUT_SEC", 8.0)
        mcp_retries = max(0, self._env_int("SMARTHOME_HA_MCP_TIMEOUT_RETRIES", 1))

        gw_adapter = HaGatewayAdapter(gateway_url=gateway_url or None, timeout_sec=gateway_timeout)
        mcp_adapter = HaMcpAdapter(
            mcp_url=mcp_url or None,
            mcp_token=mcp_token,
            timeout_sec=mcp_timeout,
            timeout_retries=mcp_retries,
        )

        gw = self._run_compare_channel(channel="ha_gateway", adapter=gw_adapter, payload=gw_payload)
        mcp = self._run_compare_channel(channel="ha_mcp", adapter=mcp_adapter, payload=mcp_payload)
        consistency = self._build_consistency(gw, mcp)

        return make_response(
            trace_id,
            data={
                "input": base,
                "isolate_session": isolate_session,
                "channels": {
                    "ha_gateway": gw,
                    "ha_mcp": mcp,
                },
                "consistency": consistency,
            },
        )

    def post_api_v1_nlu_parse(self, payload: Dict[str, Any], headers: Dict[str, str] | None = None) -> Dict[str, Any]:
        trace_id = self._trace_id(headers)
        text = str(payload.get("text", "")).strip()
        if not text:
            return make_response(trace_id, code="BAD_REQUEST", message="missing text")

        session_id = str(payload.get("session_id", "sess_nlu_parse_debug")).strip() or "sess_nlu_parse_debug"
        user_id = str(payload.get("user_id", "usr_nlu_parse_debug")).strip() or "usr_nlu_parse_debug"
        threshold_raw = payload.get("threshold")
        threshold = threshold_raw if isinstance(threshold_raw, dict) else None

        session_state = self.dst.get_session(session_id, user_id)
        route_result = self.router.route(
            trace_id=trace_id,
            text=text,
            context=session_state.as_dict(),
            threshold=threshold,
        )
        intent = route_result["intent_json"]
        intent.slots = self.dst.inherit_slots(session_id, user_id, intent.slots)

        return make_response(
            trace_id,
            data={
                "status": "clarify" if route_result["need_clarify"] else "ok",
                "route": route_result["route"],
                "model_version": route_result["model_version"],
                "need_clarify": bool(route_result["need_clarify"]),
                "threshold": route_result["threshold"],
                "intent_json": intent.as_dict(),
                "session_id": session_id,
                "user_id": user_id,
            },
        )

    def get_api_v1_entities(
        self,
        *,
        query: str = "",
        domain: str | None = None,
        limit: int = 50,
        hide_default: bool = True,
        headers: Dict[str, str] | None = None,
    ) -> Dict[str, Any]:
        trace_id = self._trace_id(headers)
        limit = max(1, min(int(limit), 1000))
        query = str(query or "").strip()
        domain = str(domain or "").strip() or None
        hide_default = bool(hide_default)

        if query:
            entities = self.adapter.search_entities(query=query, domain=domain, limit=limit)
        else:
            if self.adapter.mode == "ha_mcp":
                entities = self.adapter.get_all_entities(force_refresh=True)
            else:
                entities = self.adapter.get_all_entities()
            if domain:
                entities = [item for item in entities if str(item.get("entity_id", "")).startswith(f"{domain}.")]
        entities = self._apply_alias_overrides(entities)

        if hide_default:
            entities = [item for item in entities if not self._is_default_ha_entity(str(item.get("entity_id", "")))]

        total = len(entities)
        entities = entities[:limit]

        diagnostics: Dict[str, Any] = {"mode": self.adapter.mode}
        if self.adapter.mode == "stub":
            diagnostics["warning"] = (
                "device discovery is running in stub mode; configure "
                "SMARTHOME_HA_GATEWAY_URL or SMARTHOME_HA_MCP_URL to discover real devices"
            )
        elif self.adapter.mode == "ha_mcp" and total == 0:
            mcp_url = str(getattr(self.adapter, "_mcp_url", "") or "").strip()
            if not mcp_url:
                diagnostics["warning"] = "SMARTHOME_HA_MCP_URL is not configured"
            else:
                try:
                    import mcp  # type: ignore  # noqa: F401
                except Exception:
                    diagnostics["warning"] = "python package 'mcp' is not installed in current runtime environment"
                else:
                    if "/private_" not in mcp_url:
                        diagnostics["warning"] = (
                            "MCP URL may be incomplete; use the full URL including /private_... key from HA-MCP"
                        )
                    else:
                        diagnostics["warning"] = "no entities discovered from upstream Home Assistant channel"
            probe = getattr(self.adapter, "_remote_tool_call", None)
            if callable(probe):
                try:
                    probe_result = probe(
                        "ha_search_entities",
                        {"query": "", "domain_filter": str(domain or "light"), "limit": 1},
                    )
                    if isinstance(probe_result, dict) and not probe_result.get("success"):
                        diagnostics["upstream_error"] = str(probe_result.get("error", "") or "").strip()
                        diagnostics["upstream_error_code"] = str(probe_result.get("error_code", "") or "").strip()
                except Exception:
                    pass
        elif self.adapter.mode == "ha_gateway" and total == 0:
            diagnostics["warning"] = "no entities discovered from upstream Home Assistant channel"
            probe = getattr(self.adapter, "_gateway_call", None)
            if callable(probe):
                try:
                    probe_result = probe("discover", {})
                    if isinstance(probe_result, dict) and not probe_result.get("success"):
                        diagnostics["upstream_error"] = str(probe_result.get("error", "") or "").strip()
                        diagnostics["upstream_error_code"] = str(probe_result.get("error_code", "") or "").strip()
                except Exception:
                    pass
        elif total == 0:
            diagnostics["warning"] = "no entities discovered from upstream Home Assistant channel"

        normalized: List[Dict[str, Any]] = []
        name_groups: Dict[str, List[Dict[str, Any]]] = {}
        for item in entities:
            if not isinstance(item, dict):
                continue
            entity_id = str(item.get("entity_id", ""))
            if not entity_id:
                continue

            clean_name = clean_entity_name(str(item.get("name", "")), entity_id)
            clean_area = clean_entity_name(str(item.get("area", "")))
            aliases_raw = item.get("aliases")
            if isinstance(aliases_raw, list):
                aliases = [clean_entity_name(str(alias)) for alias in aliases_raw if str(alias).strip()]
            else:
                aliases = build_entity_aliases(name=clean_name, entity_id=entity_id, area=clean_area)
            aliases = sorted({alias for alias in aliases if alias and alias != clean_name})

            entry: Dict[str, Any] = {
                "entity_id": entity_id,
                "name": clean_name,
                "area": clean_area,
                "state": item.get("state"),
                "score": item.get("score"),
                "aliases": aliases,
            }
            normalized.append(entry)

            key = normalize_entity_name(clean_name)
            if key:
                name_groups.setdefault(key, []).append(entry)

        for _, group in name_groups.items():
            if len(group) <= 1:
                continue
            for order, entry in enumerate(sorted(group, key=lambda x: str(x.get("entity_id", ""))), start=1):
                base_name = str(entry.get("name", "")).strip()
                if not base_name:
                    continue
                route_index = extract_entity_index(str(entry.get("entity_id", ""))) or order
                entry["name"] = f"{base_name} 第{route_index}路"

                dedupe_aliases = list(entry.get("aliases", []))
                dedupe_aliases.extend([base_name, f"第{route_index}路{base_name}", f"{base_name}第{route_index}路"])
                entry["aliases"] = sorted(
                    {clean_entity_name(str(alias)) for alias in dedupe_aliases if str(alias).strip()}
                )

        return make_response(
            trace_id,
            data={
                "mode": self.adapter.mode,
                "count": len(normalized),
                "total": total,
                "has_more": total > len(normalized),
                "hide_default": hide_default,
                "diagnostics": diagnostics,
                "items": normalized,
            },
        )

    def get_api_v1_history(
        self,
        *,
        session_id: str,
        limit: int = 50,
        headers: Dict[str, str] | None = None,
    ) -> Dict[str, Any]:
        trace_id = self._trace_id(headers)
        session_id = str(session_id or "").strip()
        if not session_id:
            return make_response(trace_id, code="BAD_REQUEST", message="missing session_id")

        items = self.state_backend.get_history(session_id, limit=limit)
        return make_response(
            trace_id,
            data={
                "session_id": session_id,
                "count": len(items),
                "items": items,
            },
        )

    def delete_api_v1_history(
        self,
        *,
        session_id: str,
        headers: Dict[str, str] | None = None,
    ) -> Dict[str, Any]:
        trace_id = self._trace_id(headers)
        session_id = str(session_id or "").strip()
        if not session_id:
            return make_response(trace_id, code="BAD_REQUEST", message="missing session_id")

        self.state_backend.clear_history(session_id)
        return make_response(
            trace_id,
            data={
                "session_id": session_id,
                "cleared": True,
            },
        )

    def get_api_v1_health(self, headers: Dict[str, str] | None = None) -> Dict[str, Any]:
        trace_id = self._trace_id(headers)
        return make_response(
            trace_id,
            data={
                "status": "up",
                "components": {
                    "nlu_router": "up",
                    "entity_resolver": "up",
                    "policy_engine": "up",
                    "executor": "up",
                    "state_store": self.state_backend.health(),
                },
            },
        )
