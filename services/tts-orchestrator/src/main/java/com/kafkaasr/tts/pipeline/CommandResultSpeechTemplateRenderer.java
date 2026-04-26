package com.kafkaasr.tts.pipeline;

import com.kafkaasr.tts.events.CommandResultEvent;
import com.kafkaasr.tts.events.CommandResultPayload;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class CommandResultSpeechTemplateRenderer {

    public String render(CommandResultEvent event) {
        if (event == null || event.payload() == null) {
            return "指令执行结果不可用，请稍后重试。";
        }

        CommandResultPayload payload = event.payload();
        String status = normalizeLower(payload.status());
        String code = normalizeUpper(payload.code());
        String replyText = trimToEmpty(payload.replyText());
        if (!replyText.isBlank()) {
            return replyText;
        }

        return switch (status) {
            case "ok" -> templateForSuccess(code, payload);
            case "confirm_required" -> templateForConfirmRequired(code, payload);
            case "cancelled" -> templateForCancelled(code, payload);
            case "timeout" -> templateForTimeout(code, payload);
            case "failed" -> templateForFailure(code, payload);
            default -> templateForFailure(code, payload);
        };
    }

    private String templateForSuccess(String code, CommandResultPayload payload) {
        if ("OK".equals(code)) {
            return "操作执行成功。";
        }
        if ("PARTIAL".equals(code)) {
            return "操作已部分完成，请检查设备状态。";
        }
        return defaultByIntent(payload, "操作已完成。");
    }

    private String templateForConfirmRequired(String code, CommandResultPayload payload) {
        if ("POLICY_CONFIRM_REQUIRED".equals(code)) {
            return "该操作需要确认，请在客户端确认后继续。";
        }
        return "请确认是否继续执行该操作。";
    }

    private String templateForCancelled(String code, CommandResultPayload payload) {
        String rejectReason = normalizeUpper(payload.rejectReason());
        if ("USER_REJECTED".equals(rejectReason)) {
            return "已取消本次操作。";
        }
        if ("NO_INPUT_TIMEOUT".equals(rejectReason) || "COMMAND_CONFIRM_TIMEOUT".equals(code)) {
            return "确认超时，操作已取消。";
        }
        return "操作已取消。";
    }

    private String templateForTimeout(String code, CommandResultPayload payload) {
        if ("UPSTREAM_TIMEOUT".equals(code) || "NLU_TIMEOUT".equals(normalizeUpper(payload.errorCode()))) {
            return "设备或服务响应超时，请稍后重试。";
        }
        return "执行超时，请稍后重试。";
    }

    private String templateForFailure(String code, CommandResultPayload payload) {
        String errorCode = normalizeUpper(payload.errorCode());
        if ("FORBIDDEN".equals(code) || "POLICY_DENY".equals(normalizeUpper(payload.rejectReason()))) {
            return "当前权限不允许执行该操作。";
        }
        if ("ENTITY_NOT_FOUND".equals(code) || "NOT_FOUND".equals(code)) {
            return "未找到目标设备，请检查设备名称或位置。";
        }
        if ("DEVICE_OFFLINE".equals(errorCode)) {
            return "目标设备当前离线，请稍后重试。";
        }
        if ("DEVICE_EXECUTION_FAILED".equals(errorCode)) {
            return "设备执行失败，请检查设备状态。";
        }
        if ("CLIENT_NETWORK_ERROR".equals(errorCode)) {
            return "网络异常，操作执行失败，请重试。";
        }
        return defaultByIntent(payload, "操作执行失败，请稍后重试。");
    }

    private String defaultByIntent(CommandResultPayload payload, String fallback) {
        String intent = normalizeLower(payload.intent());
        if ("query".equals(intent) || "query_status".equals(normalizeLower(payload.subIntent()))) {
            return "查询失败，请稍后再试。";
        }
        return fallback;
    }

    private String normalizeLower(String value) {
        return trimToEmpty(value).toLowerCase(Locale.ROOT);
    }

    private String normalizeUpper(String value) {
        return trimToEmpty(value).toUpperCase(Locale.ROOT);
    }

    private String trimToEmpty(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}
