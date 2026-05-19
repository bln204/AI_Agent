package com.aiagent.service;

import com.aiagent.model.Document;
import com.aiagent.model.Project;
import com.aiagent.model.ProjectMember;
import com.aiagent.model.User;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.ProjectMemberRepository;
import com.aiagent.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final DocumentRepository documentRepository;

    public List<Project> getAllProjects() {
        return projectRepository.findAll();
    }

    public Optional<Project> getProjectById(Long id) {
        return projectRepository.findById(id);
    }

    @Transactional
    public Project createProject(String name, String description) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Tên dự án không được để trống");
        }

        Project project = new Project();
        project.setName(name);
        project.setDescription(description);
        project.setActive(true);
        
        project.setCode(generateUniqueCode(name));
        
        return projectRepository.save(project);
    }

    private String generateUniqueCode(String name) {
        String baseCode = slugify(name);
        if (baseCode.length() > 30) {
            baseCode = baseCode.substring(0, 30);
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        String finalCode = baseCode + "_" + timestamp;
        
        int attempts = 0;
        while (projectRepository.existsByCode(finalCode) && attempts < 10) {
            finalCode = baseCode + "_" + timestamp + "_" + attempts;
            attempts++;
        }

        return finalCode;
    }

    private String slugify(String name) {
        if (name == null) return "";
        
        String temp = Normalizer.normalize(name, Normalizer.Form.NFD);
        Pattern pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        String result = pattern.matcher(temp).replaceAll("");
        
        result = result.replaceAll("[^a-zA-Z0-9 ]", "")
                       .replace(" ", "_")
                       .replaceAll("_+", "_")
                       .toUpperCase();
        
        if (result.startsWith("_")) result = result.substring(1);
        if (result.endsWith("_")) result = result.substring(0, result.length() - 1);
        
        return result;
    }

    @Transactional
    public Project updateProject(Long id, String name, String description, boolean active) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy dự án ID: " + id));
        project.setName(name);
        project.setDescription(description);
        project.setActive(active);
        return projectRepository.save(project);
    }

    @Transactional
    public void deleteProject(Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy dự án ID: " + id));

        List<ProjectMember> members = projectMemberRepository.findByProject(project);
        projectMemberRepository.deleteAll(members);

        List<Document> documents = documentRepository.findByProjectId(id);
        for (Document doc : documents) {
            doc.getProjects().remove(project);
            documentRepository.save(doc);
        }
        projectRepository.delete(project);
    }

    @Transactional
    public void addMember(Project project, User user) {
        if (!projectMemberRepository.existsByProjectAndUser(project, user)) {
            ProjectMember member = new ProjectMember();
            member.setProject(project);
            member.setUser(user);
            member.setActive(true);
            projectMemberRepository.save(member);
        }
    }

    @Transactional
    public void removeMember(Project project, User user) {
        projectMemberRepository.findByProjectAndUser(project, user)
                .ifPresent(projectMemberRepository::delete);
    }
    
    public List<ProjectMember> getProjectMembers(Project project) {
        return projectMemberRepository.findByProject(project);
    }

    public List<Project> getProjectsForUser(User user) {
        return projectMemberRepository.findByUser(user).stream()
                .filter(ProjectMember::isActive)
                .map(ProjectMember::getProject)
                .toList();
    }
}
