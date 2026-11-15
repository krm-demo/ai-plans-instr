package com.example.plans.lambda;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Map;
import java.util.function.Function;

@Configuration
public class MigrationFunctions {

    /**
     * Applies the SQL files in {@code db/migration}. The release workflow invokes this Lambda
     * after every deploy; the other Lambdas never run migrations.
     */
    @Bean
    public Function<Map<String, Object>, String> migrateDatabase(DataSource dataSource) {
        return ignored -> {
            MigrateResult result = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:plans/db/migration")
                    .load()
                    .migrate();
            return "Applied " + result.migrationsExecuted + " migration(s), schema version "
                    + result.targetSchemaVersion;
        };
    }
}
