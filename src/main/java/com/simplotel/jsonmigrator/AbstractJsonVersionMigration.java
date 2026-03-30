package com.simplotel.jsonmigrator;

import com.jayway.jsonpath.DocumentContext;

/**
 * Convenience base class for migrations. Handles the {@code $.version} field update automatically.
 *
 * <p>Subclasses only need to implement {@link #applyChanges(DocumentContext)}.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @Component
 * public class BrandingV1ToV2 extends AbstractJsonVersionMigration {
 *     public String jsonType()  { return "BRANDING_CONFIG"; }
 *     public int fromVersion()  { return 1; }
 *     public int toVersion()    { return 2; }
 *
 *     protected void applyChanges(DocumentContext doc) {
 *         doc.put("$", "display_name", "");
 *     }
 * }
 * }</pre>
 */
public abstract class AbstractJsonVersionMigration implements JsonVersionMigration {

    @Override
    public final void migrate(DocumentContext doc) {
        applyChanges(doc);
        // Use put (add-or-replace) instead of set (replace-only) so it works
        // even when the document has no "version" field yet
        doc.put("$", "version", String.valueOf(toVersion()));
    }

    /**
     * Apply the actual schema changes (add / remove / rename fields).
     * The {@code $.version} field is updated automatically after this method returns.
     *
     * @param doc the mutable JSON document
     */
    protected abstract void applyChanges(DocumentContext doc);
}
