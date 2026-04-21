package com.kafkaasr.gateway;

import com.kafkaasr.gateway.ingress.GatewayKafkaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GatewayKafkaProperties.class)
public class SpeechGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpeechGatewayApplication.class, args);
    }
}
