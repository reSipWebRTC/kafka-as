package com.kafkaasr.tts.kafka;

import com.kafkaasr.tts.events.TtsRequestEvent;
import reactor.core.publisher.Mono;

public interface TtsRequestPublisher {

    Mono<Void> publish(TtsRequestEvent event);
}
