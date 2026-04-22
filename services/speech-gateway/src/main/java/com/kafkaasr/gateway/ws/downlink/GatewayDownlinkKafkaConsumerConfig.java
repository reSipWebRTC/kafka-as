package com.kafkaasr.gateway.ws.downlink;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class GatewayDownlinkKafkaConsumerConfig {

    @Bean
    DefaultErrorHandler gatewayDownlinkKafkaErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            GatewayDownlinkProperties downlinkProperties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ignored) -> new TopicPartition(
                        record.topic() + downlinkProperties.getDlqTopicSuffix(),
                        record.partition()));

        FixedBackOff fixedBackOff = new FixedBackOff(
                downlinkProperties.getRetryBackoffMs(),
                Math.max(0L, downlinkProperties.getRetryMaxAttempts() - 1L));

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, fixedBackOff);
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        return errorHandler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler gatewayDownlinkKafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(gatewayDownlinkKafkaErrorHandler);
        return factory;
    }
}
