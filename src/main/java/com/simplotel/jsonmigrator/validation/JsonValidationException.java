package com.simplotel.jsonmigrator.validation;

/**
 * Thrown when JSON validation fails and the caller uses {@link JsonValidationResult#throwIfInvalid(String)}.
 */
public class JsonValidationException extends RuntimeException {

    private final JsonValidationResult result;

    public JsonValidationException(String context, JsonValidationResult result) {
        super(String.format("JSON validation failed for '%s' (%d error%s):\n%s",
                context,
                result.getErrorCount(),
                result.getErrorCount() == 1 ? "" : "s",
                result.getErrorSummary()));
        this.result = result;
    }

    /** Returns the full validation result with individual errors. */
    public JsonValidationResult getResult() {
        return result;
    }
}
