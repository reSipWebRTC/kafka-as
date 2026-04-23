package com.kafkaasr.control.auth;

class ExternalIamTokenDecoderException extends RuntimeException {

    enum Category {
        INVALID_TOKEN,
        UNAVAILABLE
    }

    private final Category category;

    ExternalIamTokenDecoderException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    Category category() {
        return category;
    }
}
