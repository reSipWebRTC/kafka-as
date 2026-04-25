package com.kafkaasr.command.kafka;

import com.kafkaasr.command.events.CommandKafkaProperties;
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
public class CommandKafkaConsumerConfig {

    @Bean
    DefaultErrorHandler commandKafkaErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            CommandKafkaProperties kafkaProperties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(
                        record.topic() + resolveDlqTopicSuffix(exception, kafkaProperties.getDlqTopicSuffix()),
                        record.partition()));

        FixedBackOff fixedBackOff = new FixedBackOff(0L, 0L);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, fixedBackOff);
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        errorHandler.addNotRetryableExceptions(TenantAwareDlqException.class);
        return errorHandler;
    }

    private String resolveDlqTopicSuffix(Throwable throwable, String fallbackSuffix) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TenantAwareDlqException tenantAware
                    && tenantAware.dlqTopicSuffix() != null
                    && !tenantAware.dlqTopicSuffix().isBlank()) {
                return tenantAware.dlqTopicSuffix();
            }
            current = current.getCause();
        }
        return fallbackSuffix;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler commandKafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(commandKafkaErrorHandler);
        return factory;
    }
}
