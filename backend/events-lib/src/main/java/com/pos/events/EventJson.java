package com.pos.events;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single JSON configuration used for events, so a producer and a consumer can never disagree
 * about how a payload is encoded.
 *
 * <p>Unknown properties are ignored on read. That is deliberate and is what makes additive schema
 * evolution safe: a consumer on an older build keeps working when a producer adds a field.
 */
public final class EventJson {

    private EventJson() {}

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    /** Reads an envelope whose payload is of the given type. */
    public static <T> EventEnvelope<T> readEnvelope(String json, Class<T> payloadType) {
        JavaType type =
                MAPPER.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
        return MAPPER.readValue(json, type);
    }
}
