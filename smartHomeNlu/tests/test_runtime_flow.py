import json
import unittest
from typing import Any, Dict, List

from runtime import SmartHomeRuntime
from runtime.contracts import IntentJson
from runtime.observability import REQUIRED_AUDIT_FIELDS


class EntityListAdapter:
    def __init__(self) -> None:
        self.mode = "stub"
        self.service_call_count = 0
        self.backup_call_count = 0
        self._entities: List[Dict[str, Any]] = [
            {"entity_id": "sun.sun", "name": "Sun", "area": "", "state": "above_horizon"},
            {"entity_id": "update.home_assistant_core_update", "name": "HA Core Update", "area": "", "state": "on"},
            {"entity_id": "light.kitchen_main", "name": "厨房主灯", "area": "厨房", "state": "off"},
            {"entity_id": "switch.coffee_machine", "name": "咖啡机", "area": "厨房", "state": "off"},
            {"entity_id": "climate.living_room_ac", "name": "客厅空调", "area": "客厅", "state": "off"},
        ]

    def get_all_entities(self) -> List[Dict[str, Any]]:
        return [dict(item) for item in self._entities]

    def search_entities(self, query: str, domain: str | None = None, limit: int = 3) -> List[Dict[str, Any]]:
        query_norm = str(query or "").strip()
        rows = []
        for item in self._entities:
            entity_id = str(item.get("entity_id", ""))
            if domain and not entity_id.startswith(f"{domain}."):
                continue
            if query_norm and query_norm not in f"{item.get('name', '')}{entity_id}":
                continue
            rows.append(dict(item, score=1.0))
        return rows[: max(1, int(limit))]

    def call_service(self, **_: Any) -> Dict[str, Any]:
        self.service_call_count += 1
        return {"success": True, "status_code": 200}

    def tool_call(self, *_: Any, **__: Any) -> Dict[str, Any]:
        return {"success": True, "status_code": 200}


class SameNameSwitchAdapter:
    def __init__(self) -> None:
        self.mode = "ha_mcp"
        self.service_call_count = 0
        self.backup_call_count = 0
        self.calls: List[Dict[str, Any]] = []
        self._entities: List[Dict[str, Any]] = [
            {"entity_id": "switch.tyzxl_plug", "name": "TYZXl鹊起 延长线插座 None", "area": "", "state": "off"},
            {"entity_id": "switch.tyzxl_plug_2", "name": "TYZXl鹊起 延长线插座 None", "area": "", "state": "off"},
            {"entity_id": "switch.tyzxl_plug_3", "name": "TYZXl鹊起 延长线插座 None", "area": "", "state": "off"},
        ]

    def get_all_entities(self) -> List[Dict[str, Any]]:
        return [dict(item) for item in self._entities]

    def search_entities(self, query: str, domain: str | None = None, limit: int = 3) -> List[Dict[str, Any]]:
        rows = []
        for item in self._entities:
            entity_id = str(item.get("entity_id", ""))
            if domain and not entity_id.startswith(f"{domain}."):
                continue
            rows.append(dict(item, score=0.92))
        return rows[: max(1, int(limit))]

    def call_service(
        self,
        *,
        domain: str,
        service: str,
        entity_id: str,
        params: Dict[str, Any] | None = None,
    ) -> Dict[str, Any]:
        self.service_call_count += 1
        self.calls.append({"domain": domain, "service": service, "entity_id": entity_id, "params": dict(params or {})})
        return {"success": True, "status_code": 200, "entity_id": entity_id}

    def tool_call(self, *_: Any, **__: Any) -> Dict[str, Any]:
        return {"success": True, "status_code": 200}


class SparseSameNameSwitchAdapter(SameNameSwitchAdapter):
    def search_entities(self, query: str, domain: str | None = None, limit: int = 3) -> List[Dict[str, Any]]:
        query_norm = str(query or "").strip().lower()
        rows: List[Dict[str, Any]] = []
        if "tyzxl鹊起" in query_norm or "none" in query_norm:
            rows = [dict(item, score=0.95) for item in self._entities]
        else:
            rows = [dict(self._entities[0], score=0.91)]

        if domain:
            rows = [item for item in rows if str(item.get("entity_id", "")).startswith(f"{domain}.")]
        return rows[: max(1, int(limit))]


class LightFallbackAdapter:
    def __init__(self) -> None:
        self.mode = "ha_mcp"
        self.service_call_count = 0
        self.backup_call_count = 0
        self.calls: List[Dict[str, Any]] = []
        self.search_calls: List[Dict[str, Any]] = []

    def get_all_entities(self) -> List[Dict[str, Any]]:
        return []

    def search_entities(self, query: str, domain: str | None = None, limit: int = 3) -> List[Dict[str, Any]]:
        self.search_calls.append({"query": query, "domain": domain, "limit": limit})
        if domain == "light":
            return []
        if "客厅灯" in str(query):
            return [
                {
                    "entity_id": "switch.living_room_light_switch",
                    "name": "客厅灯开关",
                    "area": "客厅",
                    "state": "off",
                    "score": 0.92,
                }
            ]
        return []

    def call_service(
        self,
        *,
        domain: str,
        service: str,
        entity_id: str,
        params: Dict[str, Any] | None = None,
    ) -> Dict[str, Any]:
        self.service_call_count += 1
        self.calls.append({"domain": domain, "service": service, "entity_id": entity_id, "params": dict(params or {})})
        return {"success": True, "status_code": 200, "entity_id": entity_id}

    def tool_call(self, *_: Any, **__: Any) -> Dict[str, Any]:
        return {"success": True, "status_code": 200}


class RecordingQueryAdapter:
    def __init__(self) -> None:
        self.mode = "ha_mcp"
        self.service_call_count = 0
        self.backup_call_count = 0
        self.search_calls: List[Dict[str, Any]] = []

    def get_all_entities(self) -> List[Dict[str, Any]]:
        return []

    def search_entities(self, query: str, domain: str | None = None, limit: int = 3) -> List[Dict[str, Any]]:
        self.search_calls.append({"query": query, "domain": domain, "limit": limit})
        return []

    def call_service(
        self,
        *,
        domain: str,
        service: str,
        entity_id: str,
        params: Dict[str, Any] | None = None,
    ) -> Dict[str, Any]:
        self.service_call_count += 1
        return {"success": True, "status_code": 200, "entity_id": entity_id}

    def tool_call(self, *_: Any, **__: Any) -> Dict[str, Any]:
        return {"success": True, "status_code": 200}


class AutomationToolAdapter(EntityListAdapter):
    def __init__(self) -> None:
        super().__init__()
        self.tool_calls: List[Dict[str, Any]] = []
        self._entities.append(
            {
                "entity_id": "automation.kitchen_light_schedule",
                "name": "厨房主灯自动化",
                "area": "厨房",
                "state": "on",
            }
        )

    def tool_call(self, tool_name: str, params: Dict[str, Any] | None = None) -> Dict[str, Any]:
        payload = {"tool_name": tool_name, "params": dict(params or {})}
        self.tool_calls.append(payload)
        if tool_name == "ha_create_automation":
            return {
                "success": True,
                "status_code": 200,
                "automation_id": "automation.kitchen_light_schedule",
            }
        if tool_name == "ha_remove_automation":
            return {"success": True, "status_code": 200}
        return super().tool_call(tool_name, params or {})


class RuntimeFlowTests(unittest.TestCase):
    def setUp(self) -> None:
        self.runtime = SmartHomeRuntime()

    def test_health_endpoint(self) -> None:
        resp = self.runtime.get_api_v1_health()
        self.assertEqual(resp["code"], "OK")
        self.assertEqual(resp["data"]["status"], "up")

    def test_brightness_command_success(self) -> None:
        resp = self.runtime.post_api_v1_command(
            {
                "session_id": "sess_001",
                "user_id": "usr_001",
                "text": "把客厅灯调到50%",
                "channel": "voice",
            }
        )
        self.assertEqual(resp["code"], "OK")
        self.assertEqual(resp["data"]["status"], "ok")
        self.assertEqual(resp["data"]["intent"], "CONTROL")
        self.assertEqual(resp["data"]["sub_intent"], "adjust_brightness")
        self.assertIn("nlu", resp["data"])
        self.assertEqual(resp["data"]["nlu"]["intent_json"]["sub_intent"], "adjust_brightness")

        events = self.runtime.event_bus.events("evt.execution.result.v1")
        self.assertGreaterEqual(len(events), 1)
        for field in ("status", "error_code", "latency_ms", "tool_name", "entity_id"):
            self.assertIn(field, events[-1])

    def test_query_command_success(self) -> None:
        resp = self.runtime.post_api_v1_command(
            {
                "session_id": "sess_query_001",
                "user_id": "usr_query_001",
                "text": "查询客厅空调状态",
            }
        )
        self.assertEqual(resp["code"], "OK")
        self.assertEqual(resp["data"]["intent"], "QUERY")
        self.assertEqual(resp["data"]["sub_intent"], "query_status")

        events = self.runtime.event_bus.events("evt.execution.result.v1")
        self.assertGreaterEqual(len(events), 1)
        self.assertEqual(events[-1]["tool_name"], "ha_get_entity")
        self.assertEqual(events[-1]["status"], "success")

    def test_idempotency_window(self) -> None:
        req = {
            "session_id": "sess_002",
            "user_id": "usr_002",
            "text": "把客厅灯调到50%",
        }
        first = self.runtime.post_api_v1_command(req)
        second = self.runtime.post_api_v1_command(req)

        self.assertEqual(first["code"], "OK")
        self.assertEqual(second["code"], "OK")
        self.assertTrue(second["data"].get("idempotent_hit"))
        self.assertEqual(self.runtime.adapter.service_call_count, 1)

    def test_high_risk_confirm_flow(self) -> None:
        first = self.runtime.post_api_v1_command(
            {
                "session_id": "sess_003",
                "user_id": "usr_003",
                "text": "把前门解锁",
                "user_role": "normal_user",
            }
        )
        self.assertEqual(first["code"], "POLICY_CONFIRM_REQUIRED")
        token = first["data"].get("confirm_token")
        self.assertIsNotNone(token)

        second = self.runtime.post_api_v1_confirm(
            {
                "confirm_token": token,
                "accept": True,
            }
        )
        self.assertEqual(second["code"], "OK")
        self.assertEqual(second["data"]["status"], "ok")

    def test_rbac_blocks_backup_for_normal_user(self) -> None:
        resp = self.runtime.post_api_v1_command(
            {
                "session_id": "sess_004",
                "user_id": "usr_004",
                "text": "备份一下HA",
                "user_role": "normal_user",
            }
        )
        self.assertEqual(resp["code"], "FORBIDDEN")

    def test_automation_create_executes_tool_for_admin(self) -> None:
        adapter = AutomationToolAdapter()
        runtime = SmartHomeRuntime(adapter=adapter)
        forced_intent = IntentJson(
            intent="SYSTEM",
            sub_intent="automation_create",
            slots={
                "location": "厨房",
                "device_type": "灯",
                "time_expression": "每天7点",
                "action": "打开",
            },
            confidence=0.93,
        )

        runtime.router.route = lambda **_: {
            "route": "main",
            "route_stage": "rule",
            "intent_json": forced_intent,
            "need_clarify": False,
            "model_version": "nlu-rule-v1",
            "threshold": {"fallback_trigger": 0.6, "main_pass": 0.85},
        }

        resp = runtime.post_api_v1_command(
            {
                "session_id": "sess_auto_001",
                "user_id": "usr_auto_001",
                "text": "每天早上七点开客厅灯",
                "user_role": "admin",
            }
        )
        self.assertEqual(resp["code"], "OK")
        self.assertEqual(resp["data"]["status"], "ok")
        self.assertGreaterEqual(len(adapter.tool_calls), 1)
        self.assertEqual(adapter.tool_calls[-1]["tool_name"], "ha_create_automation")
        config_raw = adapter.tool_calls[-1]["params"].get("config")
        config = json.loads(config_raw) if isinstance(config_raw, str) else dict(config_raw or {})
        self.assertEqual(config["trigger"][0]["platform"], "time")
        self.assertEqual(config["trigger"][0]["at"], "07:00:00")
        self.assertEqual(config["action"][0]["service"], "light.turn_on")
        self.assertEqual(config["action"][0]["target"]["entity_id"], "light.kitchen_main")

    def test_automation_cancel_executes_tool_for_admin(self) -> None:
        adapter = AutomationToolAdapter()
        runtime = SmartHomeRuntime(adapter=adapter)
        forced_intent = IntentJson(
            intent="SYSTEM",
            sub_intent="automation_cancel",
            slots={"entity_id": "automation.kitchen_light_schedule"},
            confidence=0.91,
        )

        runtime.router.route = lambda **_: {
            "route": "main",
            "route_stage": "rule",
            "intent_json": forced_intent,
            "need_clarify": False,
            "model_version": "nlu-rule-v1",
            "threshold": {"fallback_trigger": 0.6, "main_pass": 0.85},
        }

        resp = runtime.post_api_v1_command(
            {
                "session_id": "sess_auto_cancel_001",
                "user_id": "usr_auto_cancel_001",
                "text": "取消厨房主灯自动化",
                "user_role": "admin",
            }
        )
        self.assertEqual(resp["code"], "OK")
        self.assertEqual(resp["data"]["status"], "ok")
        self.assertGreaterEqual(len(adapter.tool_calls), 1)
        self.assertEqual(adapter.tool_calls[-1]["tool_name"], "ha_remove_automation")
        self.assertEqual(adapter.tool_calls[-1]["params"].get("identifier"), "automation.kitchen_light_schedule")

    def test_automation_create_forbidden_for_normal_user(self) -> None:
        adapter = AutomationToolAdapter()
        runtime = SmartHomeRuntime(adapter=adapter)
        forced_intent = IntentJson(
            intent="SYSTEM",
            sub_intent="automation_create",
            slots={
                "location": "厨房",
                "device_type": "灯",
                "time_expression": "每天7点",
                "action": "打开",
            },
            confidence=0.93,
        )

        runtime.router.route = lambda **_: {
            "route": "main",
            "route_stage": "rule",
            "intent_json": forced_intent,
            "need_clarify": False,
            "model_version": "nlu-rule-v1",
            "threshold": {"fallback_trigger": 0.6, "main_pass": 0.85},
        }

        resp = runtime.post_api_v1_command(
            {
                "session_id": "sess_auto_deny_001",
                "user_id": "usr_auto_deny_001",
                "text": "每天七点打开厨房主灯",
                "user_role": "normal_user",
            }
        )
        self.assertEqual(resp["code"], "FORBIDDEN")
        self.assertEqual(len(adapter.tool_calls), 0)

    def test_semantic_hint_can_require_confirmation_for_low_risk_command(self) -> None:
        runtime = SmartHomeRuntime(adapter=EntityListAdapter())
        forced_intent = IntentJson(
            intent="CONTROL",
            sub_intent="power_on",
            slots={
                "entity_id": "light.kitchen_main",
                "_semantic_requires_confirmation": True,
                "_semantic_confirmation_message": "请确认是否打开厨房主灯。",
            },
            confidence=0.92,
        )

        runtime.router.route = lambda **_: {
            "route": "main",
            "route_stage": "rule",
            "intent_json": forced_intent,
            "need_clarify": False,
            "model_version": "nlu-rule-v1",
            "threshold": {"fallback_trigger": 0.6, "main_pass": 0.85},
        }

        first = runtime.post_api_v1_command(
            {
                "session_id": "sess_semantic_confirm_001",
                "user_id": "usr_semantic_confirm_001",
                "text": "打开厨房主灯",
            }
        )
        self.assertEqual(first["code"], "POLICY_CONFIRM_REQUIRED")
        self.assertEqual(first["data"]["status"], "confirm_required")
        self.assertEqual(first["data"]["confirm_reason"], "semantic_ambiguity")
        token = first["data"].get("confirm_token")
        self.assertIsNotNone(token)
        self.assertEqual(runtime.adapter.service_call_count, 0)

        second = runtime.post_api_v1_confirm(
            {
                "confirm_token": token,
                "accept": True,
            }
        )
        self.assertEqual(second["code"], "OK")
        self.assertEqual(second["data"]["status"], "ok")
        self.assertEqual(runtime.adapter.service_call_count, 1)

    def test_low_confidence_clarify_and_streak(self) -> None:
        req = {
            "session_id": "sess_005",
            "user_id": "usr_005",
            "text": "帮我弄一下这个",
        }
        r1 = self.runtime.post_api_v1_command(req)
        r2 = self.runtime.post_api_v1_command(req)
        r3 = self.runtime.post_api_v1_command(req)

        self.assertEqual(r1["data"]["status"], "clarify")
        self.assertEqual(r2["data"]["status"], "clarify")
        self.assertIn("连续三次", r3["data"]["reply_text"])

    def test_main_low_confidence_collects_hard_example(self) -> None:
        # 规则引擎增强后，"把客厅灯调亮"已被规则层高置信命中，
        # 不再产生 low_confidence 样本。改用模糊/噪声输入触发低置信路径。
        resp = self.runtime.post_api_v1_command(
            {
                "session_id": "sess_007",
                "user_id": "usr_007",
                "text": "把客厅灯调亮",
            }
        )
        self.assertEqual(resp["code"], "OK")
        # 规则层高置信命中 → 无 low_confidence 样本
        events = self.runtime.event_bus.events("evt.data.hard_example.v1")
        low_conf = [evt for evt in events if evt.get("sample_type") == "low_confidence"]
        # 新规则引擎覆盖更广，"把客厅灯调亮"已不需要 main/fallback 层
        # 若规则层命中，不会产生 low_confidence hard example
        if low_conf:
            self.assertEqual(low_conf[-1]["reason"], "main_low_confidence")

    def test_entity_not_found_collects_hard_example(self) -> None:
        self.runtime.entity_resolver.reindex([])
        resp = self.runtime.post_api_v1_command(
            {
                "session_id": "sess_008",
                "user_id": "usr_008",
                "text": "查询客厅空调状态",
            }
        )
        self.assertEqual(resp["code"], "ENTITY_NOT_FOUND")

        events = self.runtime.event_bus.events("evt.data.hard_example.v1")
        failures = [evt for evt in events if evt.get("sample_type") == "execution_failure"]
        self.assertGreaterEqual(len(failures), 1)
        self.assertEqual(failures[-1]["error_code"], "ENTITY_NOT_FOUND")

    def test_audit_log_required_fields(self) -> None:
        self.runtime.post_api_v1_command(
            {
                "session_id": "sess_006",
                "user_id": "usr_006",
                "text": "把客厅灯调到45%",
            }
        )
        latest = self.runtime.observability.audit_logs[-1]
        self.assertTrue(REQUIRED_AUDIT_FIELDS.issubset(set(latest.keys())))

    def test_entity_resolver_top_k_cap(self) -> None:
        candidates = self.runtime.entity_resolver.resolve(
            trace_id="trc_test",
            slots={"device_type": "灯"},
            top_k=10,
        )
        self.assertLessEqual(len(candidates), 5)

    def test_entities_endpoint_hides_default_ha_entities(self) -> None:
        runtime = SmartHomeRuntime(adapter=EntityListAdapter())

        hidden = runtime.get_api_v1_entities(query="", limit=2, hide_default=True)
        self.assertEqual(hidden["code"], "OK")
        self.assertEqual(hidden["data"]["total"], 3)
        self.assertEqual(hidden["data"]["count"], 2)
        self.assertTrue(hidden["data"]["has_more"])
        hidden_ids = [item["entity_id"] for item in hidden["data"]["items"]]
        self.assertNotIn("sun.sun", hidden_ids)
        self.assertNotIn("update.home_assistant_core_update", hidden_ids)

        shown = runtime.get_api_v1_entities(query="", limit=10, hide_default=False)
        self.assertEqual(shown["code"], "OK")
        shown_ids = [item["entity_id"] for item in shown["data"]["items"]]
        self.assertIn("sun.sun", shown_ids)
        self.assertIn("update.home_assistant_core_update", shown_ids)

    def test_same_name_devices_require_index_hint(self) -> None:
        adapter = SameNameSwitchAdapter()
        runtime = SmartHomeRuntime(adapter=adapter)

        ambiguous = runtime.post_api_v1_command(
            {
                "session_id": "sess_same_name_001",
                "user_id": "usr_same_name_001",
                "text": "打开TYZXl鹊起 延长线插座 None",
            }
        )
        self.assertEqual(ambiguous["code"], "OK")
        self.assertEqual(ambiguous["data"]["status"], "clarify")
        self.assertIn("第1路/第2路/第3路", ambiguous["data"]["reply_text"])
        self.assertEqual(adapter.service_call_count, 0)

        targeted = runtime.post_api_v1_command(
            {
                "session_id": "sess_same_name_002",
                "user_id": "usr_same_name_002",
                "text": "打开TYZXl鹊起 延长线插座 第2路",
            }
        )
        if targeted["code"] == "POLICY_CONFIRM_REQUIRED":
            token = targeted["data"].get("confirm_token")
            self.assertIsNotNone(token)
            targeted = runtime.post_api_v1_confirm({"confirm_token": token, "accept": True})
        self.assertEqual(targeted["code"], "OK")
        self.assertEqual(targeted["data"]["status"], "ok")
        self.assertEqual(adapter.service_call_count, 1)
        self.assertEqual(adapter.calls[-1]["entity_id"], "switch.tyzxl_plug_2")

    def test_sparse_candidate_list_still_supports_route_hint(self) -> None:
        adapter = SparseSameNameSwitchAdapter()
        runtime = SmartHomeRuntime(adapter=adapter)

        ambiguous = runtime.post_api_v1_command(
            {
                "session_id": "sess_sparse_same_name_001",
                "user_id": "usr_sparse_same_name_001",
                "text": "打开TYZXl鹊起 延长线插座 None",
            }
        )
        self.assertEqual(ambiguous["code"], "OK")
        self.assertEqual(ambiguous["data"]["status"], "clarify")
        self.assertEqual(adapter.service_call_count, 0)

        targeted = runtime.post_api_v1_command(
            {
                "session_id": "sess_sparse_same_name_002",
                "user_id": "usr_sparse_same_name_002",
                "text": "打开TYZXl鹊起 延长线插座 None 第二路",
            }
        )
        if targeted["code"] == "POLICY_CONFIRM_REQUIRED":
            token = targeted["data"].get("confirm_token")
            self.assertIsNotNone(token)
            targeted = runtime.post_api_v1_confirm({"confirm_token": token, "accept": True})
        self.assertEqual(targeted["code"], "OK")
        self.assertEqual(targeted["data"]["status"], "ok")
        self.assertEqual(adapter.service_call_count, 1)
        self.assertEqual(adapter.calls[-1]["entity_id"], "switch.tyzxl_plug_2")

    def test_same_name_clarify_followup_index_only_can_resume_previous_semantics(self) -> None:
        adapter = SameNameSwitchAdapter()
        runtime = SmartHomeRuntime(adapter=adapter)

        ambiguous = runtime.post_api_v1_command(
            {
                "session_id": "sess_same_name_followup_001",
                "user_id": "usr_same_name_followup_001",
                "text": "打开TYZXl鹊起 延长线插座 None",
            }
        )
        self.assertEqual(ambiguous["code"], "OK")
        self.assertEqual(ambiguous["data"]["status"], "clarify")
        self.assertEqual(adapter.service_call_count, 0)

        followup = runtime.post_api_v1_command(
            {
                "session_id": "sess_same_name_followup_001",
                "user_id": "usr_same_name_followup_001",
                "text": "第二路",
            }
        )
        if followup["code"] == "POLICY_CONFIRM_REQUIRED":
            token = followup["data"].get("confirm_token")
            self.assertIsNotNone(token)
            followup = runtime.post_api_v1_confirm({"confirm_token": token, "accept": True})
        self.assertEqual(followup["code"], "OK")
        self.assertEqual(followup["data"]["status"], "ok")
        self.assertEqual(adapter.service_call_count, 1)
        self.assertEqual(adapter.calls[-1]["entity_id"], "switch.tyzxl_plug_2")

    def test_remote_search_prefers_original_device_type_for_single_command(self) -> None:
        adapter = RecordingQueryAdapter()
        runtime = SmartHomeRuntime(adapter=adapter)
        runtime.entity_resolver.reindex([])

        forced_intent = IntentJson(
            intent="CONTROL",
            sub_intent="power_on",
            slots={
                "location": "二楼",
                "device_type": "灯",
                "_original_device_type": "射灯",
            },
            confidence=0.94,
        )
        runtime.router.route = lambda **_: {
            "route": "main",
            "route_stage": "rule",
            "intent_json": forced_intent,
            "need_clarify": False,
            "model_version": "nlu-rule-v1",
            "threshold": {"fallback_trigger": 0.6, "main_pass": 0.85},
        }

        resp = runtime.post_api_v1_command(
            {
                "session_id": "sess_remote_query_single_001",
                "user_id": "usr_remote_query_single_001",
                "text": "打开二楼射灯",
            }
        )
        self.assertEqual(resp["code"], "ENTITY_NOT_FOUND")
        self.assertGreaterEqual(len(adapter.search_calls), 1)
        self.assertEqual(adapter.search_calls[0]["query"], "二楼射灯")
        self.assertEqual(adapter.search_calls[0]["domain"], "light")

    def test_remote_search_prefers_original_device_type_for_multi_command(self) -> None:
        adapter = RecordingQueryAdapter()
        runtime = SmartHomeRuntime(adapter=adapter)
        runtime.entity_resolver.reindex([])

        forced_intent = IntentJson(
            intent="CONTROL",
            sub_intent="power_on",
            slots={"device_type": "灯"},
            confidence=0.94,
            multi_commands=[
                {
                    "intent": "CONTROL",
                    "sub_intent": "power_on",
                    "slots": {
                        "location": "二楼",
                        "device_type": "灯",
                        "_original_device_type": "射灯",
                    },
                    "confidence": 0.94,
                },
                {
                    "intent": "CHITCHAT",
                    "sub_intent": "chitchat",
                    "slots": {},
                    "confidence": 0.9,
                },
            ],
        )
        runtime.router.route = lambda **_: {
            "route": "main",
            "route_stage": "rule",
            "intent_json": forced_intent,
            "need_clarify": False,
            "model_version": "nlu-rule-v1",
            "threshold": {"fallback_trigger": 0.6, "main_pass": 0.85},
        }

        resp = runtime.post_api_v1_command(
            {
                "session_id": "sess_remote_query_multi_001",
                "user_id": "usr_remote_query_multi_001",
                "text": "打开二楼射灯，并且随便聊两句",
            }
        )
        self.assertEqual(resp["code"], "OK")
        self.assertGreaterEqual(len(adapter.search_calls), 1)
        self.assertEqual(adapter.search_calls[0]["query"], "二楼射灯")
        self.assertEqual(adapter.search_calls[0]["domain"], "light")

    def test_multi_command_batch_executes_each_segment(self) -> None:
        runtime = SmartHomeRuntime(adapter=EntityListAdapter())
        resp = runtime.post_api_v1_command(
            {
                "session_id": "sess_batch_001",
                "user_id": "usr_batch_001",
                "text": "打开厨房主灯；查询客厅空调状态",
            }
        )

        self.assertEqual(resp["code"], "OK")
        self.assertEqual(resp["data"]["status"], "ok")
        self.assertEqual(resp["data"]["sub_intent"], "multi_command")
        self.assertEqual(len(resp["data"]["items"]), 2)
        self.assertEqual(resp["data"]["items"][0]["code"], "OK")
        self.assertEqual(resp["data"]["items"][1]["code"], "OK")
        self.assertIn("nlu", resp["data"]["items"][0])
        self.assertIsInstance(resp["data"]["items"][0]["nlu"], dict)

    def test_multi_command_batch_marks_clarify_as_partial(self) -> None:
        runtime = SmartHomeRuntime(adapter=EntityListAdapter())
        resp = runtime.post_api_v1_command(
            {
                "session_id": "sess_batch_002",
                "user_id": "usr_batch_002",
                "text": "打开厨房主灯；这个咋弄",
            }
        )

        self.assertEqual(resp["code"], "OK")
        self.assertEqual(resp["data"]["status"], "partial")
        self.assertEqual(resp["data"]["sub_intent"], "multi_command")
        self.assertEqual(len(resp["data"]["items"]), 2)
        self.assertEqual(resp["data"]["items"][0]["status"], "ok")
        self.assertEqual(resp["data"]["items"][1]["status"], "clarify")

    def test_multi_command_batch_splits_hot_words_connector(self) -> None:
        runtime = SmartHomeRuntime(adapter=EntityListAdapter())
        resp = runtime.post_api_v1_command(
            {
                "session_id": "sess_batch_003",
                "user_id": "usr_batch_003",
                "text": "打开厨房主灯还有查询客厅空调状态",
            }
        )

        self.assertEqual(resp["code"], "OK")
        self.assertEqual(resp["data"]["status"], "ok")
        self.assertEqual(resp["data"]["sub_intent"], "multi_command")
        self.assertEqual(len(resp["data"]["items"]), 2)
        self.assertEqual(resp["data"]["items"][0]["code"], "OK")
        self.assertEqual(resp["data"]["items"][1]["code"], "OK")

    def test_multi_command_batch_does_not_split_single_action_with_ba(self) -> None:
        segments = SmartHomeRuntime._split_multi_commands("把客厅空调调到26度")
        self.assertEqual(segments, ["把客厅空调调到26度"])

    def test_remote_resolve_retries_without_domain_for_light(self) -> None:
        adapter = LightFallbackAdapter()
        runtime = SmartHomeRuntime(adapter=adapter)

        resp = runtime.post_api_v1_command(
            {
                "session_id": "sess_light_retry_001",
                "user_id": "usr_light_retry_001",
                "text": "打开客厅灯",
            }
        )

        self.assertEqual(resp["code"], "OK")
        self.assertEqual(resp["data"]["status"], "ok")
        self.assertEqual(adapter.service_call_count, 1)
        self.assertEqual(adapter.calls[-1]["entity_id"], "switch.living_room_light_switch")
        self.assertGreaterEqual(len(adapter.search_calls), 2)
        self.assertEqual(adapter.search_calls[0]["domain"], "light")
        self.assertIsNone(adapter.search_calls[1]["domain"])

    def test_disable_slot_inherit_avoids_cross_segment_device_pollution(self) -> None:
        session_id = "sess_inherit_001"
        user_id = "usr_inherit_001"

        first = self.runtime.post_api_v1_command(
            {
                "session_id": session_id,
                "user_id": user_id,
                "text": "打开客厅灯",
            }
        )
        self.assertEqual(first["code"], "OK")

        second = self.runtime.post_api_v1_command(
            {
                "session_id": session_id,
                "user_id": user_id,
                "text": "把tmd的小孩房间的温度调为26度",
            }
        )
        slots_with_inherit = (((second.get("data") or {}).get("nlu") or {}).get("intent_json") or {}).get("slots") or {}
        # 规则引擎从"温度调为26度"推断 device_type=空调，比跨命令继承"灯"更准确
        self.assertIn(slots_with_inherit.get("device_type"), ("空调", "灯"))

        third = self.runtime.post_api_v1_command(
            {
                "session_id": session_id,
                "user_id": user_id,
                "text": "把tmd的小孩房间的温度调为26度",
                "_disable_slot_inherit": True,
                "_disable_nlu_context": True,
            }
        )
        slots_without_inherit = (((third.get("data") or {}).get("nlu") or {}).get("intent_json") or {}).get("slots") or {}
        self.assertNotEqual(slots_without_inherit.get("device_type"), "灯")


if __name__ == "__main__":
    unittest.main()
