package com.kafkaasr.control.auth;

public interface AuthBackend {

    AuthDecision authorize(AuthRequest request);
}
