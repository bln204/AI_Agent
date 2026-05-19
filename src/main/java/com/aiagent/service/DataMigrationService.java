package com.aiagent.service;

import com.aiagent.model.Department;
import com.aiagent.model.Role;
import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.RoleRepository;
import com.aiagent.repository.UserRepository;
import com.aiagent.util.RoleConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class DataMigrationService {

    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;

    @Transactional
    public void migrate() {
        Map<String, Role> roles = seedRoles();
        Map<String, Department> departments = seedDepartments();
        migrateUsers(roles, departments);
        migrateDocuments(departments);
        rebuildDocumentNormalization();

        log.info("Data migration completed successfully.");
    }

    @Transactional
    public void rebuildDocumentNormalization() {
        log.info("Starting Document Normalization Rebuild...");
        documentRepository.findAll().forEach(doc -> {
            if (doc.getTitle() != null) {
                String oldNormalized = doc.getNormalizedTitle();
                String newNormalized = com.aiagent.util.NormalizationUtils.normalize(doc.getTitle());
                
                if (!newNormalized.equals(oldNormalized)) {
                    log.debug("Updating normalized title for doc {}: '{}' -> '{}'", 
                            doc.getId(), oldNormalized, newNormalized);
                    doc.setNormalizedTitle(newNormalized);
                    documentRepository.save(doc);
                }
            }
        });
        log.info("Document Normalization Rebuild completed.");
    }

    private Map<String, Role> seedRoles() {
        Map<String, Role> roleMap = new HashMap<>();
        saveRole(roleMap, RoleConstants.ROLE_ADMIN, "Admin");
        saveRole(roleMap, RoleConstants.ROLE_DIRECTOR, "Giám đốc");
        saveRole(roleMap, RoleConstants.ROLE_MANAGER, "Trưởng phòng");
        saveRole(roleMap, RoleConstants.ROLE_EMPLOYEE, "Nhân viên");
        return roleMap;
    }

    private void saveRole(Map<String, Role> map, String code, String name) {
        Role role = roleRepository.findByCode(code).orElseGet(() -> {
            Role newRole = new Role();
            newRole.setCode(code);
            newRole.setName(name);
            return roleRepository.save(newRole);
        });
        map.put(code, role);
    }

    private Map<String, Department> seedDepartments() {
        Map<String, Department> deptMap = new HashMap<>();
        saveDept(deptMap, "ALL", "Tất cả");
        saveDept(deptMap, "NS", "Nhân sự");
        saveDept(deptMap, "IT", "IT");
        saveDept(deptMap, "KT", "Kế toán");
        saveDept(deptMap, "MKT", "Marketing");
        return deptMap;
    }

    private void saveDept(Map<String, Department> map, String code, String name) {
        Department dept = departmentRepository.findByCode(code).orElseGet(() -> {
            Department newDept = new Department();
            newDept.setCode(code);
            newDept.setName(name);
            return departmentRepository.save(newDept);
        });
        map.put(name, dept); // Map by Vietnamese name for migration
        map.put(code, dept); // Also map by code
    }

    private void migrateUsers(Map<String, Role> roles, Map<String, Department> departments) {
        userRepository.findAll().forEach(user -> {
            boolean changed = false;
            
            // Migrate Role
            if (user.getRole() == null && user.getOldRole() != null) {
                String code = RoleConstants.fromVietnamese(user.getOldRole());
                user.setRole(roles.get(code));
                changed = true;
            }

            // Migrate Department
            if (user.getDepartment() == null && user.getOldDepartment() != null) {
                Department dept = departments.get(user.getOldDepartment());
                if (dept != null) {
                    user.setDepartment(dept);
                    changed = true;
                }
            }

            if (changed) {
                userRepository.save(user);
            }
        });
    }

    private void migrateDocuments(Map<String, Department> departments) {
        documentRepository.findAllWithDepartments().forEach(doc -> {
            boolean changed = false;

            if (doc.getDepartments().isEmpty()) {
                String oldDept = doc.getOldDepartment();
                Department dept;
                if (oldDept == null || oldDept.trim().isEmpty()) {
                    dept = departments.get("ALL");
                } else {
                    dept = departments.get(oldDept);
                }

                if (dept != null) {
                    doc.getDepartments().add(dept);
                    changed = true;
                }
            }

            if (changed) {
                documentRepository.save(doc);
            }
        });
    }
}
