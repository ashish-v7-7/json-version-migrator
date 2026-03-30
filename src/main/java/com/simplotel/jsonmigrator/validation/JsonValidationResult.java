package com.simplotel.jsonmigrator.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The result of validating a JSON document against its schema definition.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * JsonValidationResult result = validationService.validate("CREDENTIAL", json);
 * if (!result.isValid()) {
 *     log.error("Validation failed: {}", result.getErrorSummary());
 *     // or throw
 *     result.throwIfInvalid("CREDENTIAL");
 * }
 * }</pre>
 */
public class JsonValidationResult {

    private static final JsonValidationResult VALID = new JsonValidationResult(Collections.emptyList());

    private final List<JsonValidationError> errors;

    public JsonValidationResult(List<JsonValidationError> errors) {
        this.errors = errors != null ? Collections.unmodifiableList(errors) : Collections.emptyList();
    }

    /** Returns a valid (no errors) result. */
    public static JsonValidationResult valid() {
        return VALID;
    }

    /** Returns true if no validation errors were found. */
    public boolean isValid() {
        return errors.isEmpty();
    }

    /** Returns the list of validation errors (empty if valid). */
    public List<JsonValidationError> getErrors() {
        return errors;
    }

    /** Returns the number of errors. */
    public int getErrorCount() {
        return errors.size();
    }

    /** Returns a human-readable summary of all errors, one per line. */
    public String getErrorSummary() {
        if (errors.isEmpty()) return "No errors";
        return errors.stream()
                .map(JsonValidationError::toString)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Throws {@link JsonValidationException} if there are any errors.
     *
     * @param context a label for the error message (e.g., type name or ID)
     */
    public void throwIfInvalid(String context) {
        if (!errors.isEmpty()) {
            throw new JsonValidationException(context, this);
        }
    }

    // ── Builder for accumulating errors ──

    /** Mutable builder for collecting errors during validation. */
    public static class Builder {
        private final List<JsonValidationError> errors = new ArrayList<>();

        public Builder addError(JsonValidationError error) {
            errors.add(error);
            return this;
        }

        public Builder addError(JsonValidationError.ErrorCode code, String jsonPath, String message) {
            errors.add(new JsonValidationError(code, jsonPath, message));
            return this;
        }

        public JsonValidationResult build() {
            return errors.isEmpty() ? JsonValidationResult.valid() : new JsonValidationResult(errors);
        }
    }
}
