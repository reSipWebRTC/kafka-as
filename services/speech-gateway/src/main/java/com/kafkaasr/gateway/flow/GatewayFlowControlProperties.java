package com.kafkaasr.gateway.flow;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "gateway.flow-control")
public class GatewayFlowControlProperties {

    private boolean enabled = true;

    @Min(1)
    private int audioFrameRateLimitPerSecond = 50;

    @Min(1)
    private int audioFrameMaxInflight = 8;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getAudioFrameRateLimitPerSecond() {
        return audioFrameRateLimitPerSecond;
    }

    public void setAudioFrameRateLimitPerSecond(int audioFrameRateLimitPerSecond) {
        this.audioFrameRateLimitPerSecond = audioFrameRateLimitPerSecond;
    }

    public int getAudioFrameMaxInflight() {
        return audioFrameMaxInflight;
    }

    public void setAudioFrameMaxInflight(int audioFrameMaxInflight) {
        this.audioFrameMaxInflight = audioFrameMaxInflight;
    }
}
