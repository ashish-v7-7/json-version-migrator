package com.simplotel.jsonmigrator.validation;

/**
 * A single validation error found when checking a JSON document against its schema.
 */
public class JsonValidationError {

    public enum ErrorCode {
        MISSING_REQUIRED_FIELD,
        TYPE_MISMATCH,
        FORBIDDEN_FIELD_PRESENT,
        NO_SCHEMA_FOUND
    }

    private final ErrorCode code;
    private final String jsonPath;
    private final String message;

    public JsonValidationError(ErrorCode code, String jsonPath, String message) {
        this.code = code;
        this.jsonPath = jsonPath;
        this.message = message;
    }

    public ErrorCode getCode()  { return code; }
    public String getJsonPath() { return jsonPath; }
    public String getMessage()  { return message; }

    @Override
    public String toString() {
        return String.format("[%s] %s — %s", code, jsonPath, message);
    }
}
