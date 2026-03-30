package com.simplotel.jsonmigrator.autoconfigure;

import com.simplotel.jsonmigrator.JsonMigrationRegistry;
import com.simplotel.jsonmigrator.JsonMigrationService;
import com.simplotel.jsonmigrator.JsonVersionMigration;
import com.simplotel.jsonmigrator.validation.JsonSchemaDefinition;
import com.simplotel.jsonmigrator.validation.JsonSchemaRegistry;
import com.simplotel.jsonmigrator.validation.JsonValidationService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.Collections;
import java.util.List;

/**
 * Spring Boot auto-configuration for the JSON Version Migrator.
 *
 * <p>Automatically creates migration and validation beans.
 * All {@link JsonVersionMigration} and {@link JsonSchemaDefinition}
 * {@code @Component} beans are auto-discovered.</p>
 */
@AutoConfiguration
public class JsonMigratorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JsonMigrationRegistry jsonMigrationRegistry(List<JsonVersionMigration> migrations) {
        JsonMigrationRegistry registry = new JsonMigrationRegistry(
                migrations != null ? migrations : Collections.emptyList());
        registry.init();
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonSchemaRegistry jsonSchemaRegistry(List<JsonSchemaDefinition> schemas) {
        JsonSchemaRegistry registry = new JsonSchemaRegistry(
                schemas != null ? schemas : Collections.emptyList());
        registry.init();
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonValidationService jsonValidationService(JsonSchemaRegistry schemaRegistry) {
        return new JsonValidationService(schemaRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonMigrationService jsonMigrationService(
            JsonMigrationRegistry migrationRegistry,
            JsonValidationService validationService) {
        JsonMigrationService service = new JsonMigrationService(migrationRegistry);
        service.setValidationService(validationService);
        return service;
    }
}
