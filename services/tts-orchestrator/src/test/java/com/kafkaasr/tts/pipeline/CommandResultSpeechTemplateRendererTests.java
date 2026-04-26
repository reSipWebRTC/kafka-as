package com.kafkaasr.tts.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kafkaasr.tts.events.CommandResultEvent;
import com.kafkaasr.tts.events.CommandResultPayload;
import org.junit.jupiter.api.Test;

class CommandResultSpeechTemplateRendererTests {

    private final CommandResultSpeechTemplateRenderer renderer = new CommandResultSpeechTemplateRenderer();

    @Test
    void returnsReplyTextWhenProvided() {
        CommandResultEvent event = event("ok", "OK", "客厅灯已打开", null, null);
        assertEquals("客厅灯已打开", renderer.render(event));
    }

    @Test
    void rendersTimeoutTemplateWhenReplyTextMissing() {
        CommandResultEvent event = event("timeout", "UPSTREAM_TIMEOUT", "", null, "NLU_TIMEOUT");
        assertEquals("设备或服务响应超时，请稍后重试。", renderer.render(event));
    }

    @Test
    void rendersCancelledTemplateWhenConfirmTimeout() {
        CommandResultEvent event = event("cancelled", "COMMAND_CONFIRM_TIMEOUT", "", "NO_INPUT_TIMEOUT", null);
        assertEquals("确认超时，操作已取消。", renderer.render(event));
    }

    @Test
    void rendersFailureTemplateWhenDeviceOffline() {
        CommandResultEvent event = event("failed", "INTERNAL_ERROR", "", null, "DEVICE_OFFLINE");
        assertEquals("目标设备当前离线，请稍后重试。", renderer.render(event));
    }

    private CommandResultEvent event(
            String status,
            String code,
            String replyText,
            String rejectReason,
            String errorCode) {
        return new CommandResultEvent(
                "evt-1",
                "command.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "command-worker",
                1L,
                1713744000000L,
                "sess-1:command.result:1",
                new CommandResultPayload(
                        "exec-1",
                        "CLIENT_BRIDGE",
                        status,
                        code,
                        replyText,
                        false,
                        1,
                        3,
                        "cfm-1",
                        rejectReason,
                        errorCode,
                        30,
                        "CONTROL",
                        "power_on"));
    }
}
