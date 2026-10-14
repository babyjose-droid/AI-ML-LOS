package com.rhythm.los.tenant;

import jakarta.annotation.PostConstruct;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * Migrates the platform schema (tenant registry, benchmarking) and every tenant's own schema at start-up, so a new
 * release reaches all lenders. The default tenant's schema (public) is migrated by Spring's own Flyway.
 */
@Component
public class PlatformMigrator {
    private static final Logger log = LoggerFactory.getLogger(PlatformMigrator.class);
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public PlatformMigrator(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void migrate() {
        Flyway.configure().dataSource(dataSource).schemas("platform").table("flyway_platform_history").locations("classpath:db/platform").load().migrate();
        List<String> schemas = jdbc.queryForList("select schema_name from platform.tenant where schema_name <> 'public'", String.class);
        for (String s : schemas) {
            log.info("Migrating tenant schema {}", s);
            migrateTenant(s);
        }
    }

    public void migrateTenant(String schema) {
        TenantContext.run(new TenantContext.Tenant("migration", schema), () ->
                Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration").load().migrate());
    }
}
