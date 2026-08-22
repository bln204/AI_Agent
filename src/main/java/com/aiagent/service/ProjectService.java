package com.aiagent.service;

import com.aiagent.model.Document;
import com.aiagent.model.Project;
import com.aiagent.model.ProjectMember;
import com.aiagent.model.ProjectStatus;
import com.aiagent.model.User;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.ProjectMemberRepository;
import com.aiagent.repository.ProjectRepository;
import com.aiagent.util.RoleConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final DocumentRepository documentRepository;
    private final DocumentService documentService;
    private final NotificationService notificationService;

    public List<Project> getAllProjects() {
        return projectRepository.findAll();
    }

    public Optional<Project> getProjectById(Long id) {
        return projectRepository.findById(id);
    }

    public List<Project> getProjectsForUser(User user) {
        return projectMemberRepository.findByUser(user).stream()
                .filter(ProjectMember::isActive)
                .map(ProjectMember::getProject)
                .toList();
    }

    @Transactional
    public Project createProject(String name, LocalDate startDate, LocalDate expectedEndDate,
                                  String projectType, BigDecimal cost, String description,
                                  List<User> members, User leader) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Tên dự án không được để trống");
        }
        if (startDate == null || expectedEndDate == null) {
            throw new IllegalArgumentException("Vui lòng nhập ngày bắt đầu và ngày dự kiến kết thúc");
        }
        if (startDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Ngày bắt đầu phải lớn hơn hoặc bằng ngày hiện tại");
        }
        if (!expectedEndDate.isAfter(startDate)) {
            throw new IllegalArgumentException("Ngày dự kiến kết thúc phải sau ngày bắt đầu");
        }
        if (leader != null && (members == null || members.stream().noneMatch(m -> m.getId().equals(leader.getId())))) {
            throw new IllegalArgumentException("Leader phải là một trong các thành viên tham gia dự án");
        }

        Project project = new Project();
        project.setName(name);
        project.setDescription(description);
        project.setStartDate(startDate);
        project.setExpectedEndDate(expectedEndDate);
        project.setProjectType(projectType);
        project.setCost(cost);
        project.setStatus(ProjectStatus.RUNNING);
        project.setCode(generateUniqueCode(name));
        project = projectRepository.save(project);

        if (members != null) {
            for (User member : members) {
                ProjectMember pm = new ProjectMember();
                pm.setProject(project);
                pm.setUser(member);
                pm.setActive(true);
                pm.setLeader(leader != null && member.getId().equals(leader.getId()));
                projectMemberRepository.save(pm);
            }
        }

        return project;
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

    /**
     * Tên/Ngày/Loại/Chi phí/Thành viên bị khoá vĩnh viễn sau khi tạo (theo
     * business rule) -- chỉ Mô tả còn sửa được, bởi Director hoặc Leader của
     * chính dự án đó, và chỉ khi dự án chưa bị đóng băng.
     */
    @Transactional
    public Project updateDescription(Long id, String description, User actor) {
        Project project = getProjectOrThrow(id);
        requireDirectorOrLeader(project, actor, "chỉnh sửa");
        requireEditable(project, "chỉnh sửa");
        project.setDescription(description);
        return projectRepository.save(project);
    }

    /**
     * Xoá project phải dọn sạch mọi thứ liên quan (member + tài liệu), không
     * để lại rác: tài liệu PROJECT-scope mà sau khi gỡ liên kết không còn
     * thuộc project nào nữa là orphan thật sự (gần như không ai truy cập
     * được, trừ Director) -- purge hẳn (DB + vector store + file vật lý) qua
     * DocumentService#purgeDocument thay vì chỉ gỡ liên kết, để không giữ lại
     * fileHash/contentHash cũ chặn upload lại. Tài liệu vẫn còn thuộc project
     * KHÁC (many-to-many) thì chỉ gỡ liên kết, không được xoá vì project kia
     * vẫn đang dùng.
     */
    @Transactional
    public void deleteProject(Long id) {
        Project project = getProjectOrThrow(id);

        List<ProjectMember> members = projectMemberRepository.findByProject(project);
        projectMemberRepository.deleteAll(members);

        List<Document> documents = documentRepository.findByProjectId(id);
        for (Document doc : documents) {
            doc.getProjects().remove(project);
            if (doc.getProjects().isEmpty()) {
                documentService.purgeDocument(doc);
            } else {
                documentRepository.save(doc);
            }
        }
        projectRepository.delete(project);
    }

    @Transactional
    public void addMember(Project project, User user) {
        requireEditable(project, "quản lý thành viên");
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
        requireEditable(project, "quản lý thành viên");
        projectMemberRepository.findByProjectAndUser(project, user)
                .ifPresent(projectMemberRepository::delete);
    }

    public List<ProjectMember> getProjectMembers(Project project) {
        return projectMemberRepository.findByProject(project);
    }

    /**
     * Đảm bảo bất biến "tối đa 1 leader/dự án": bỏ cờ ở leader cũ (nếu có)
     * trước khi set leader mới. newLeader phải đã là thành viên dự án.
     */
    @Transactional
    public void setLeader(Project project, User newLeader) {
        requireEditable(project, "quản lý thành viên");
        ProjectMember target = projectMemberRepository.findByProjectAndUser(project, newLeader)
                .orElseThrow(() -> new IllegalArgumentException("Người được chọn làm leader phải là thành viên của dự án"));

        projectMemberRepository.findByProjectAndIsLeaderTrue(project).ifPresent(current -> {
            if (!current.getId().equals(target.getId())) {
                current.setLeader(false);
                projectMemberRepository.save(current);
            }
        });

        target.setLeader(true);
        projectMemberRepository.save(target);
    }

    // effectiveDeadline/isFrozen giờ sống ở Project entity (@Transient) để
    // Thymeleaf dùng chung được 1 nguồn logic duy nhất -- các method dưới đây
    // giữ lại chỉ để không phải sửa mọi call site hiện có trong service này.
    public LocalDate effectiveDeadline(Project project) {
        return project.getEffectiveDeadline();
    }

    public boolean isFrozen(Project project) {
        return project.isFrozen();
    }

    /**
     * COMPLETED là trạng thái TERMINAL, khác với "frozen" (tự động do hết
     * hạn, Director vẫn mở lại được qua reopenProject) -- COMPLETED không có
     * đường quay lại. Một khi đã Hoàn thành, requireEditable() chặn MỌI thay
     * đổi khác trên dự án, kể cả với Director.
     */
    public boolean isCompleted(Project project) {
        return project.getStatus() == ProjectStatus.COMPLETED;
    }

    private void requireEditable(Project project, String action) {
        if (isCompleted(project)) {
            throw new IllegalStateException("Dự án đã Hoàn thành và không thể " + action + ".");
        }
        if (isFrozen(project)) {
            throw new IllegalStateException("Dự án đã hết hạn và đang bị đóng băng. Giám đốc cần mở lại dự án trước khi " + action + ".");
        }
    }

    /**
     * Dropdown chỉnh sửa chỉ nhận PAUSED/COMPLETED/EXTENDED -- RUNNING không
     * bao giờ set qua đường này (chỉ set tự động lúc tạo mới hoặc lúc
     * reopenProject). Khi chọn EXTENDED: Director tự duyệt ngay lập tức
     * (giống DIRECTOR upload tài liệu tự động APPROVED); Leader phải chờ
     * Director duyệt (giống MANAGER upload tài liệu vào PENDING_APPROVAL).
     */
    @Transactional
    public Project updateStatus(Long id, ProjectStatus newStatus, LocalDate extensionDateInput, User actor) {
        Project project = getProjectOrThrow(id);
        boolean isDirector = requireDirectorOrLeader(project, actor, "chỉnh sửa trạng thái");
        requireEditable(project, "chỉnh sửa trạng thái");

        if (newStatus == null || newStatus == ProjectStatus.RUNNING) {
            throw new IllegalArgumentException("Trạng thái không hợp lệ.");
        }

        if (newStatus != ProjectStatus.EXTENDED) {
            project.setStatus(newStatus);
            return projectRepository.save(project);
        }

        LocalDate deadline = effectiveDeadline(project);
        if (extensionDateInput == null || (deadline != null && !extensionDateInput.isAfter(deadline))) {
            throw new IllegalArgumentException("Ngày gia hạn phải sau ngày dự kiến kết thúc hiện tại.");
        }

        if (isDirector) {
            project.setStatus(ProjectStatus.EXTENDED);
            project.setExtensionDate(extensionDateInput);
            project.setPendingExtensionDate(null);
            project.setExtensionRequestedBy(actor);
            project.setExtensionRequestedAt(LocalDateTime.now());
            return projectRepository.save(project);
        }

        project.setPendingExtensionDate(extensionDateInput);
        project.setExtensionRequestedBy(actor);
        project.setExtensionRequestedAt(LocalDateTime.now());
        Project saved = projectRepository.save(project);
        notificationService.notifyDirectorsOfPendingExtension(saved);
        return saved;
    }

    @Transactional
    public Project approveExtension(Long id, User director) {
        requireDirector(director, "duyệt gia hạn");

        int updated = projectRepository.approveExtensionIfPending(id);
        Project project = getProjectOrThrow(id);
        if (updated == 0) {
            throw new IllegalStateException("Yêu cầu gia hạn đã được xử lý trước đó hoặc không tồn tại. Vui lòng tải lại trang.");
        }
        if (project.getExtensionRequestedBy() != null) {
            notificationService.notifyLeaderOfExtensionDecision(project, project.getExtensionRequestedBy(), true);
        }
        return project;
    }

    @Transactional
    public Project rejectExtension(Long id, User director) {
        requireDirector(director, "từ chối gia hạn");

        int updated = projectRepository.rejectExtensionIfPending(id);
        Project project = getProjectOrThrow(id);
        if (updated == 0) {
            throw new IllegalStateException("Yêu cầu gia hạn đã được xử lý trước đó hoặc không tồn tại. Vui lòng tải lại trang.");
        }
        if (project.getExtensionRequestedBy() != null) {
            notificationService.notifyLeaderOfExtensionDecision(project, project.getExtensionRequestedBy(), false);
        }
        return project;
    }

    /**
     * Case 2: chỉ Giám đốc mở lại được dự án đã đóng băng. Đặt lại mốc gia
     * hạn (đẩy effectiveDeadline ra tương lai) và trả status về RUNNING --
     * đây là đường DUY NHẤT set lại RUNNING sau khi tạo.
     */
    @Transactional
    public Project reopenProject(Long id, LocalDate newExtensionDate, User director) {
        requireDirector(director, "mở lại dự án");
        Project project = getProjectOrThrow(id);

        if (!isFrozen(project)) {
            throw new IllegalStateException("Dự án hiện không bị đóng băng.");
        }
        LocalDate deadline = effectiveDeadline(project);
        if (newExtensionDate == null || (deadline != null && !newExtensionDate.isAfter(deadline))) {
            throw new IllegalArgumentException("Ngày gia hạn mới phải sau ngày dự kiến kết thúc hiện tại.");
        }

        project.setExtensionDate(newExtensionDate);
        project.setStatus(ProjectStatus.RUNNING);
        project.setPendingExtensionDate(null);
        project.setExtensionRequestedBy(director);
        project.setExtensionRequestedAt(LocalDateTime.now());
        return projectRepository.save(project);
    }

    private boolean isDirector(User user) {
        return user != null && user.getRole() != null && RoleConstants.ROLE_DIRECTOR.equals(user.getRole().getCode());
    }

    private void requireDirector(User user, String action) {
        if (!isDirector(user)) {
            throw new SecurityException("Chỉ Giám đốc mới có quyền " + action + ".");
        }
    }

    /** @return true nếu actor là Director (false nếu actor là Leader hợp lệ khác). */
    private boolean requireDirectorOrLeader(Project project, User actor, String action) {
        boolean director = isDirector(actor);
        if (director) return true;
        boolean leader = projectMemberRepository.findByProjectAndUser(project, actor)
                .map(ProjectMember::isLeader).orElse(false);
        if (!leader) {
            throw new SecurityException("Bạn không có quyền " + action + " dự án này.");
        }
        return false;
    }

    private Project getProjectOrThrow(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy dự án ID: " + id));
    }
}
