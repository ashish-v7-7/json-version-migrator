package com.simplotel.jsonmigrator;

/**
 * Factory for creating {@link JsonDeserializer} instances backed by Jackson's {@code ObjectMapper}.
 *
 * <p>This class uses reflection to avoid a compile-time dependency on Jackson.
 * It works when {@code com.fasterxml.jackson.databind.ObjectMapper} is on the classpath
 * (which it always is in Spring Boot).</p>
 *
 * <p><b>Usage:</b></p>
 * <pre>{@code
 * // Create once and reuse
 * JsonDeserializer<GatewayCredential> credDeserializer =
 *     JacksonDeserializer.create(objectMapper, GatewayCredential.class);
 *
 * // Use with migration service
 * GatewayCredential cred = migrationService.migrateToLatest("CREDENTIAL", json, credDeserializer);
 * }</pre>
 *
 * <p>Or inline:</p>
 * <pre>{@code
 * GatewayCredential cred = migrationService.migrateValidateOrThrow(
 *     "CREDENTIAL", json,
 *     JacksonDeserializer.create(objectMapper, GatewayCredential.class));
 * }</pre>
 */
public final class JacksonDeserializer {

    private JacksonDeserializer() {}

    /**
     * Creates a {@link JsonDeserializer} that uses the given Jackson ObjectMapper.
     *
     * @param objectMapper a Jackson {@code ObjectMapper} instance
     * @param type         the target class
     * @param <T>          the target type
     * @return a deserializer that delegates to {@code objectMapper.readValue(json, type)}
     */
    public static <T> JsonDeserializer<T> create(Object objectMapper, Class<T> type) {
        // Use reflection to avoid compile-time Jackson dependency
        try {
            var readValueMethod = objectMapper.getClass()
                    .getMethod("readValue", String.class, Class.class);
            return json -> {
                @SuppressWarnings("unchecked")
                T result = (T) readValueMethod.invoke(objectMapper, json, type);
                return result;
            };
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "The provided objectMapper does not have a readValue(String, Class) method. " +
                    "Expected a Jackson ObjectMapper instance.", e);
        }
    }
}
