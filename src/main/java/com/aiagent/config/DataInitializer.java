package com.aiagent.config;

import com.aiagent.model.Department;
import com.aiagent.model.Role;
import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.RoleRepository;
import com.aiagent.util.RoleConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DataInitializer handles basic seeding of Roles and Departments.
 * Migration logic has been moved to DataMigrationService and DataMigrationRunner.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;

    @Override
    @Transactional
    public void run(String... args) {
        log.info("Starting Basic Data Seeding...");

        // 1. Initialize Roles
        initRole(RoleConstants.ROLE_ADMIN, "Quản trị viên");
        initRole(RoleConstants.ROLE_DIRECTOR, "Giám đốc");
        initRole(RoleConstants.ROLE_MANAGER, "Trưởng phòng");
        initRole(RoleConstants.ROLE_EMPLOYEE, "Nhân viên");

        // 2. Initialize "ALL" Department if missing
        if (departmentRepository.findByCode("ALL").isEmpty()) {
            Department allDept = new Department();
            allDept.setCode("ALL");
            allDept.setName("Tất cả");
            allDept.setActive(true);
            departmentRepository.save(allDept);
            log.info("Created 'ALL' department.");
        }

        log.info("Basic Data Seeding completed successfully.");
    }

    private void initRole(String code, String name) {
        if (roleRepository.findByCode(code).isEmpty()) {
            Role role = new Role();
            role.setCode(code);
            role.setName(name);
            roleRepository.save(role);
            log.info("Created role: {}", code);
        }
    }
}
