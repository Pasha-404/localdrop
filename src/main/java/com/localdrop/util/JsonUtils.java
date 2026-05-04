package com.localdrop.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class JsonUtils {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonUtils() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static <T> T read(Path path, Class<T> type) throws IOException {
        return MAPPER.readValue(Files.readAllBytes(path), type);
    }

    public static void writePretty(Path path, Object value) throws IOException {
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    public static String toJson(Object value) throws JsonProcessingException {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T fromJson(String json, Class<T> type) throws JsonProcessingException {
        return MAPPER.readValue(json, type);
    }
}
