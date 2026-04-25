package com.kafkaasr.gateway.ingress;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "gateway.kafka")
public class GatewayKafkaProperties {

    @NotBlank
    private String audioIngressTopic = "audio.ingress.raw";

    @NotBlank
    private String commandConfirmRequestTopic = "command.confirm.request";

    @NotBlank
    private String tenantId = "tenant-default";

    @NotBlank
    private String producerId = "speech-gateway";

    @Min(1)
    @Max(8)
    private int channels = 1;

    public String getAudioIngressTopic() {
        return audioIngressTopic;
    }

    public void setAudioIngressTopic(String audioIngressTopic) {
        this.audioIngressTopic = audioIngressTopic;
    }

    public String getCommandConfirmRequestTopic() {
        return commandConfirmRequestTopic;
    }

    public void setCommandConfirmRequestTopic(String commandConfirmRequestTopic) {
        this.commandConfirmRequestTopic = commandConfirmRequestTopic;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getProducerId() {
        return producerId;
    }

    public void setProducerId(String producerId) {
        this.producerId = producerId;
    }

    public int getChannels() {
        return channels;
    }

    public void setChannels(int channels) {
        this.channels = channels;
    }
}
