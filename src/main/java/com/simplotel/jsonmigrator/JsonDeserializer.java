package com.simplotel.jsonmigrator;

/**
 * Functional interface for deserializing a JSON string into a typed POJO.
 *
 * <p>The library is JSON-framework agnostic — it does not depend on Jackson, Gson, or any
 * specific serialization library. You provide the deserialization logic via this interface.</p>
 *
 * <p><b>Jackson example:</b></p>
 * <pre>{@code
 * JsonDeserializer<MyDto> deserializer = json -> objectMapper.readValue(json, MyDto.class);
 * }</pre>
 *
 * <p><b>Gson example:</b></p>
 * <pre>{@code
 * JsonDeserializer<MyDto> deserializer = json -> gson.fromJson(json, MyDto.class);
 * }</pre>
 *
 * @param <T> the target POJO type
 */
@FunctionalInterface
public interface JsonDeserializer<T> {

    /**
     * Deserializes the given JSON string into an object of type {@code T}.
     *
     * @param json the JSON string (never null when called by the framework)
     * @return the deserialized object
     * @throws Exception if deserialization fails (wrapped as RuntimeException by the framework)
     */
    T deserialize(String json) throws Exception;
}
