package com.kafkaasr.tts.kafka;

import com.kafkaasr.tts.events.TtsChunkEvent;
import reactor.core.publisher.Mono;

public interface TtsChunkPublisher {

    Mono<Void> publish(TtsChunkEvent event);
}
