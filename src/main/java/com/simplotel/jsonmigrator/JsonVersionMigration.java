package com.simplotel.jsonmigrator;

import com.jayway.jsonpath.DocumentContext;

/**
 * A single version migration step that transforms a JSON document from one version to the next.
 *
 * <p>Implementations handle exactly one transition (e.g., version 1 → 2).
 * The {@link JsonMigrationRegistry} chains these steps to migrate across multiple versions.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @Component
 * public class CredentialV1ToV2 extends AbstractJsonVersionMigration {
 *     public String jsonType()  { return "CREDENTIAL"; }
 *     public int fromVersion()  { return 1; }
 *     public int toVersion()    { return 2; }
 *
 *     protected void applyChanges(DocumentContext doc) {
 *         doc.put("$.fields", "gateway_mode", "live");
 *     }
 * }
 * }</pre>
 *
 * @see AbstractJsonVersionMigration
 * @see JsonMigrationService
 */
public interface JsonVersionMigration {

    /**
     * The document type this migration applies to (e.g., "CREDENTIAL", "BRANDING_CONFIG").
     * Consumers define their own type strings; the framework uses them as grouping keys.
     *
     * @return a non-null, non-blank type identifier
     */
    String jsonType();

    /**
     * The source version this migration reads.
     *
     * @return a positive integer
     */
    int fromVersion();

    /**
     * The target version this migration produces. Must equal {@code fromVersion() + 1}.
     *
     * @return {@code fromVersion() + 1}
     */
    int toVersion();

    /**
     * Mutates the document in-place to migrate it from {@link #fromVersion()} to {@link #toVersion()}.
     * Must update the {@code "version"} field in the document as part of the migration.
     *
     * @param doc the parsed JSON document (JsonPath DocumentContext)
     */
    void migrate(DocumentContext doc);
}
