package com.kafkaasr.gateway;

import com.kafkaasr.gateway.flow.GatewayFlowControlProperties;
import com.kafkaasr.gateway.ingress.GatewayKafkaProperties;
import com.kafkaasr.gateway.session.GatewaySessionControlProperties;
import com.kafkaasr.gateway.ws.downlink.GatewayDownlinkProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        GatewayKafkaProperties.class,
        GatewaySessionControlProperties.class,
        GatewayDownlinkProperties.class,
        GatewayFlowControlProperties.class
})
public class SpeechGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpeechGatewayApplication.class, args);
    }
}
