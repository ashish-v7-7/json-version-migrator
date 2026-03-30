package com.simplotel.jsonmigrator.validation;

/**
 * Describes validation rules for a single field in a JSON document.
 *
 * <p>Example:</p>
 * <pre>{@code
 * JsonFieldRule.required("$.fields.key_id", FieldType.STRING)
 * JsonFieldRule.optional("$.fields.gateway_mode", FieldType.STRING, "live")
 * JsonFieldRule.forbidden("$.fields.legacy_token")
 * }</pre>
 */
public class JsonFieldRule {

    private final String jsonPath;
    private final Presence presence;
    private final FieldType expectedType;
    private final Object defaultValue;

    public enum Presence {
        /** Field must exist and be non-null */
        REQUIRED,
        /** Field may or may not exist */
        OPTIONAL,
        /** Field must NOT exist (deprecated/removed) */
        FORBIDDEN
    }

    private JsonFieldRule(String jsonPath, Presence presence, FieldType expectedType, Object defaultValue) {
        this.jsonPath = jsonPath;
        this.presence = presence;
        this.expectedType = expectedType;
        this.defaultValue = defaultValue;
    }

    // ── Factory methods ──

    /** Field must exist and be non-null with the expected type. */
    public static JsonFieldRule required(String jsonPath, FieldType type) {
        return new JsonFieldRule(jsonPath, Presence.REQUIRED, type, null);
    }

    /** Field may or may not exist. If present, must match the expected type. */
    public static JsonFieldRule optional(String jsonPath, FieldType type) {
        return new JsonFieldRule(jsonPath, Presence.OPTIONAL, type, null);
    }

    /** Field may or may not exist. If missing, the default value is noted in error context. */
    public static JsonFieldRule optional(String jsonPath, FieldType type, Object defaultValue) {
        return new JsonFieldRule(jsonPath, Presence.OPTIONAL, type, defaultValue);
    }

    /** Field must NOT exist. Used to verify deprecated fields were actually removed. */
    public static JsonFieldRule forbidden(String jsonPath) {
        return new JsonFieldRule(jsonPath, Presence.FORBIDDEN, FieldType.ANY, null);
    }

    // ── Getters ──

    public String getJsonPath()        { return jsonPath; }
    public Presence getPresence()      { return presence; }
    public FieldType getExpectedType() { return expectedType; }
    public Object getDefaultValue()    { return defaultValue; }

    @Override
    public String toString() {
        return String.format("%s(%s, %s)", presence, jsonPath, expectedType);
    }
}
