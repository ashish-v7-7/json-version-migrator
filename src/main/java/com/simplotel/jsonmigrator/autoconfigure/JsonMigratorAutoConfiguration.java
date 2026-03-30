package com.simplotel.jsonmigrator.autoconfigure;

import com.simplotel.jsonmigrator.JsonMigrationRegistry;
import com.simplotel.jsonmigrator.JsonMigrationService;
import com.simplotel.jsonmigrator.JsonVersionMigration;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.Collections;
import java.util.List;

/**
 * Spring Boot auto-configuration for the JSON Version Migrator.
 *
 * <p>Automatically creates {@link JsonMigrationRegistry} and {@link JsonMigrationService} beans.
 * All {@link JsonVersionMigration} beans in the application context are auto-discovered.</p>
 *
 * <p>To override, define your own beans of these types.</p>
 */
@AutoConfiguration
public class JsonMigratorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JsonMigrationRegistry jsonMigrationRegistry(List<JsonVersionMigration> migrations) {
        // Spring injects empty list if no migrations found (not null)
        JsonMigrationRegistry registry = new JsonMigrationRegistry(
                migrations != null ? migrations : Collections.emptyList());
        registry.init();
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonMigrationService jsonMigrationService(JsonMigrationRegistry registry) {
        return new JsonMigrationService(registry);
    }
}
