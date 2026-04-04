package com.aiagent.config;

import com.aiagent.service.DataMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runner to trigger data migration after the application has started.
 * This approach ensures that the migration runs within a valid transactional context
 * and avoids issues with @PostConstruct.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataMigrationRunner implements ApplicationRunner {

    private final DataMigrationService dataMigrationService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("DataMigrationRunner: Starting Role and Department data migration...");
        try {
            dataMigrationService.migrate();
            log.info("DataMigrationRunner: Migration completed successfully.");
        } catch (Exception e) {
            log.error("DataMigrationRunner: Migration failed!", e);
            // Optionally rethrow if you want the app to fail startup
            // throw e;
        }
    }
}
