package com.aiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * SEC-006 — verifies the EFFECTIVE configuration Spring Boot resolves when
 * SPRING_PROFILES_ACTIVE=production is set, not merely that the profile
 * file exists. Uses Boot's own ConfigDataEnvironmentPostProcessor so the
 * resolution logic matches exactly what happens at real startup, without
 * needing a live MySQL/Qdrant connection to boot the full ApplicationContext.
 */
class ProductionProfileConfigurationTest {

    @Test
    void productionProfile_disablesUnsafeDevSettings() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("production");
        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"),
                "production must never let Hibernate auto-migrate the schema");
        assertEquals("false", environment.getProperty("spring.jpa.show-sql"));
        assertEquals("INFO", environment.getProperty("logging.level.root"));
        assertEquals("INFO", environment.getProperty("logging.level.com.aiagent"));
        assertEquals("WARN", environment.getProperty("logging.level.org.springframework.ai"));
        assertEquals("WARN", environment.getProperty("logging.level.io.qdrant"));

        // Sanity check: these must differ from the raw base-file dev defaults,
        // proving the production profile file is actually being layered on top.
        assertNotEquals("update", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertNotEquals("DEBUG", environment.getProperty("logging.level.com.aiagent"));
    }
}
