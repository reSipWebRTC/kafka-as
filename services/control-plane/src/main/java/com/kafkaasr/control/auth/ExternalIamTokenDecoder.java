package com.kafkaasr.control.auth;

import java.util.Map;

interface ExternalIamTokenDecoder {

    Map<String, Object> decode(String token);
}
