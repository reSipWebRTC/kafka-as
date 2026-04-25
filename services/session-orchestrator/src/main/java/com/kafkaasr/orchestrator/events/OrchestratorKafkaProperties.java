package com.kafkaasr.orchestrator.events;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "orchestrator.kafka")
public class OrchestratorKafkaProperties {

    @NotBlank
    private String sessionControlTopic = "session.control";

    @NotBlank
    private String asrPartialTopic = "asr.partial";

    @NotBlank
    private String asrFinalTopic = "asr.final";

    @NotBlank
    private String translationResultTopic = "translation.result";

    @NotBlank
    private String ttsReadyTopic = "tts.ready";

    @NotBlank
    private String commandResultTopic = "command.result";

    @NotBlank
    private String compensationTopic = "platform.compensation";

    @NotBlank
    private String auditTopic = "platform.audit";

    @NotBlank
    private String producerId = "session-orchestrator";

    public String getSessionControlTopic() {
        return sessionControlTopic;
    }

    public void setSessionControlTopic(String sessionControlTopic) {
        this.sessionControlTopic = sessionControlTopic;
    }

    public String getAsrPartialTopic() {
        return asrPartialTopic;
    }

    public void setAsrPartialTopic(String asrPartialTopic) {
        this.asrPartialTopic = asrPartialTopic;
    }

    public String getAsrFinalTopic() {
        return asrFinalTopic;
    }

    public void setAsrFinalTopic(String asrFinalTopic) {
        this.asrFinalTopic = asrFinalTopic;
    }

    public String getTranslationResultTopic() {
        return translationResultTopic;
    }

    public void setTranslationResultTopic(String translationResultTopic) {
        this.translationResultTopic = translationResultTopic;
    }

    public String getTtsReadyTopic() {
        return ttsReadyTopic;
    }

    public void setTtsReadyTopic(String ttsReadyTopic) {
        this.ttsReadyTopic = ttsReadyTopic;
    }

    public String getCommandResultTopic() {
        return commandResultTopic;
    }

    public void setCommandResultTopic(String commandResultTopic) {
        this.commandResultTopic = commandResultTopic;
    }

    public String getCompensationTopic() {
        return compensationTopic;
    }

    public void setCompensationTopic(String compensationTopic) {
        this.compensationTopic = compensationTopic;
    }

    public String getAuditTopic() {
        return auditTopic;
    }

    public void setAuditTopic(String auditTopic) {
        this.auditTopic = auditTopic;
    }

    public String getProducerId() {
        return producerId;
    }

    public void setProducerId(String producerId) {
        this.producerId = producerId;
    }
}
