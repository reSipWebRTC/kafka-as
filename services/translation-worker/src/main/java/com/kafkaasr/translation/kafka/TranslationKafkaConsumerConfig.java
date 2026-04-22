package com.kafkaasr.translation.kafka;

import com.kafkaasr.translation.events.TranslationKafkaProperties;
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
public class TranslationKafkaConsumerConfig {

    @Bean
    DefaultErrorHandler translationKafkaErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            TranslationKafkaProperties kafkaProperties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ignored) -> new TopicPartition(
                        record.topic() + kafkaProperties.getDlqTopicSuffix(),
                        record.partition()));

        FixedBackOff fixedBackOff = new FixedBackOff(
                kafkaProperties.getRetryBackoffMs(),
                Math.max(0L, kafkaProperties.getRetryMaxAttempts() - 1L));

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, fixedBackOff);
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        return errorHandler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler translationKafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(translationKafkaErrorHandler);
        return factory;
    }
}
