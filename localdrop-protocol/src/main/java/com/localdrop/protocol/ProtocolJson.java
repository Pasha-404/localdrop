package com.localdrop.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class ProtocolJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private ProtocolJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object value) throws JsonProcessingException {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T fromJson(String json, Class<T> type) throws JsonProcessingException {
        return MAPPER.readValue(json, type);
    }
}
