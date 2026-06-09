package com.workflow.service;

import com.workflow.model.Department;
import com.workflow.model.DocumentAuditLog;
import com.workflow.model.FormDefinition;
import com.workflow.model.User;
import com.workflow.model.Workflow;
import com.workflow.model.WorkflowNodo;
import com.workflow.repository.DepartmentRepository;
import com.workflow.repository.DocumentAuditLogRepository;
import com.workflow.repository.UserRepository;
import com.workflow.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DocumentAccessService {

    public enum PermissionType {
        CREATE,
        READ,
        EDIT
    }

    public record StoredFileReference(String fieldName, String storedName, String fileName) {}

    private final WorkflowRepository workflowRepository;
    private final DepartmentRepository departmentRepository;
    private final DocumentAuditLogRepository documentAuditLogRepository;
    private final UserRepository userRepository;

    public Map<String, Boolean> resolvePermissions(WorkflowNodo nodo, User actor) {
        boolean admin = isAdmin(actor);
        boolean canCreate = admin || hasPermission(nodo, actor, PermissionType.CREATE);
        boolean canRead = admin || hasPermission(nodo, actor, PermissionType.READ);
        boolean canEdit = admin || hasPermission(nodo, actor, PermissionType.EDIT);
        return Map.of(
                "canCreate", canCreate,
                "canRead", canRead,
                "canEdit", canEdit
        );
    }

    public boolean hasPermission(WorkflowNodo nodo, User actor, PermissionType type) {
        if (nodo == null || actor == null) return false;
        if (isAdmin(actor)) return true;
        if (nodo.getDocumentPermissions() == null || nodo.getDocumentPermissions().isEmpty()) {
            return matchesResponsibleAssignment(nodo, actor);
        }
        // Los permisos documentales usan departmentId para almacenar el jobRoleId del cargo asignado
        String actorJobRoleId = actor.getJobRoleId();
        if (actorJobRoleId == null || actorJobRoleId.isBlank()) return false;
        return nodo.getDocumentPermissions().stream()
                .filter(permission -> actorJobRoleId.equals(permission.getDepartmentId()))
                .anyMatch(permission -> switch (type) {
                    case CREATE -> permission.isCanCreate();
                    case READ -> permission.isCanRead();
                    case EDIT -> permission.isCanEdit();
                });
    }

    public boolean hasAnyAccess(WorkflowNodo nodo, User actor) {
        if (isAdmin(actor)) return true;
        return hasPermission(nodo, actor, PermissionType.READ)
                || hasPermission(nodo, actor, PermissionType.CREATE)
                || hasPermission(nodo, actor, PermissionType.EDIT);
    }

    public void recordRead(String tramiteId, String workflowId, String nodoId, StoredFileReference file, User actor) {
        record(tramiteId, workflowId, nodoId, file, actor, DocumentAuditLog.Action.READ);
    }

    public void recordCreated(String tramiteId, String workflowId, String nodoId, StoredFileReference file, User actor) {
        record(tramiteId, workflowId, nodoId, file, actor, DocumentAuditLog.Action.CREATED);
    }

    public void recordUpdated(String tramiteId, String workflowId, String nodoId, StoredFileReference file, User actor) {
        record(tramiteId, workflowId, nodoId, file, actor, DocumentAuditLog.Action.UPDATED);
    }

    public void recordDeleted(String tramiteId, String workflowId, String nodoId, StoredFileReference file, User actor) {
        record(tramiteId, workflowId, nodoId, file, actor, DocumentAuditLog.Action.DELETED);
    }

    public void recordCollabOpened(String tramiteId, String workflowId, String storedName, String fileName, User actor) {
        StoredFileReference ref = new StoredFileReference("collab", storedName, fileName);
        record(tramiteId, workflowId, null, ref, actor, DocumentAuditLog.Action.COLLAB_OPENED);
    }

    public void recordCollabEdited(String tramiteId, String workflowId, String storedName, String fileName, User actor) {
        StoredFileReference ref = new StoredFileReference("collab", storedName, fileName);
        record(tramiteId, workflowId, null, ref, actor, DocumentAuditLog.Action.COLLAB_EDITED);
    }

    public void recordCollabEdited(String tramiteId, String workflowId, String storedName, String fileName,
                                   String textBefore, String textAfter,
                                   String userId, String userName, String userEmail) {
        DocumentAuditLog log = new DocumentAuditLog();
        log.setTramiteId(tramiteId);
        log.setWorkflowId(workflowId);
        log.setFieldName("collab");
        log.setStoredName(storedName != null ? storedName : "");
        log.setFileName(fileName != null ? fileName : storedName);
        log.setAction(DocumentAuditLog.Action.COLLAB_EDITED);
        log.setUserId(userId);
        log.setUserName(userName);
        log.setUserEmail(userEmail);
        // Resolver departamento desde el userId
        if (userId != null) {
            userRepository.findById(userId).ifPresent(user -> {
                if (user.getDepartmentId() != null) {
                    log.setDepartmentId(user.getDepartmentId());
                    departmentRepository.findById(user.getDepartmentId())
                            .ifPresent(dept -> log.setDepartmentName(dept.getName()));
                }
            });
        }
        log.setTextBefore(truncate(textBefore, 2000));
        log.setTextAfter(truncate(textAfter, 2000));
        documentAuditLogRepository.save(log);
    }

    private String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    public List<Map<String, Object>> listAuditLogs(User actor) {
        if (!isAdmin(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo administradores pueden ver la auditoría documental");
        }

        // 1. Obtener logs + mapa de nombres de workflow (mínimas queries a Atlas)
        List<DocumentAuditLog> logs;
        Map<String, String> workflowNames;
        if (actor.getRole() == User.Role.SUPERADMIN) {
            // SUPERADMIN: 2 queries total
            logs = documentAuditLogRepository.findTop500ByOrderByCreatedAtDesc();
            Set<String> wfIds = logs.stream()
                    .map(DocumentAuditLog::getWorkflowId).filter(Objects::nonNull).collect(Collectors.toSet());
            workflowNames = workflowRepository.findAllById(wfIds).stream()
                    .collect(Collectors.toMap(Workflow::getId, Workflow::getName));
        } else {
            // ADMIN: 2 queries total — la primera ya trae los nombres, no necesitamos una 3ª
            List<Workflow> companyWorkflows = workflowRepository
                    .findByCompanyIdOrderByCreatedAtDesc(actor.getCompanyId());
            if (companyWorkflows.isEmpty()) return List.of();
            workflowNames = companyWorkflows.stream()
                    .collect(Collectors.toMap(Workflow::getId, Workflow::getName));
            logs = documentAuditLogRepository
                    .findByWorkflowIdInOrderByCreatedAtDesc(workflowNames.keySet());
        }

        // 3. Mapear — textBefore/textAfter NO se incluyen en el listado (son pesados)
        //    Se obtienen solo al ver el detalle de una entrada (GET /document-audit/{id})
        return logs.stream().map(log -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",             log.getId());
            item.put("tramiteId",      log.getTramiteId());
            item.put("workflowId",     log.getWorkflowId());
            item.put("workflowName",   workflowNames.get(log.getWorkflowId()));
            item.put("fieldName",      log.getFieldName());
            item.put("storedName",     log.getStoredName());
            item.put("fileName",       log.getFileName());
            item.put("action",         log.getAction());
            item.put("userId",         log.getUserId());
            item.put("userName",       log.getUserName());
            item.put("departmentName", log.getDepartmentName());
            item.put("createdAt",      log.getCreatedAt());
            return item;
        }).toList();
    }

    public Map<String, Object> getAuditLogDetail(String logId, User actor) {
        if (!isAdmin(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo administradores pueden ver la auditoría documental");
        }
        DocumentAuditLog log = documentAuditLogRepository.findById(logId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entrada no encontrada"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",             log.getId());
        item.put("tramiteId",      log.getTramiteId());
        item.put("workflowId",     log.getWorkflowId());
        item.put("fieldName",      log.getFieldName());
        item.put("storedName",     log.getStoredName());
        item.put("fileName",       log.getFileName());
        item.put("action",         log.getAction());
        item.put("userId",         log.getUserId());
        item.put("userName",       log.getUserName());
        item.put("departmentName", log.getDepartmentName());
        item.put("textBefore",     log.getTextBefore());
        item.put("textAfter",      log.getTextAfter());
        item.put("createdAt",      log.getCreatedAt());
        return item;
    }

    public List<StoredFileReference> extractStoredFiles(Map<String, Object> formData) {
        if (formData == null || formData.isEmpty()) return List.of();
        List<StoredFileReference> files = new ArrayList<>();
        for (Map.Entry<String, Object> entry : formData.entrySet()) {
            addStoredFile(files, entry.getKey(), entry.getValue());
        }
        return files;
    }

    public boolean isFileField(FormDefinition.FormField field) {
        return field != null && field.getType() == FormDefinition.FieldType.FILE;
    }

    private void addStoredFile(List<StoredFileReference> files, String fieldName, Object value) {
        if (value instanceof Map<?, ?> fileMap && fileMap.get("storedName") != null) {
            Object rawFileName = fileMap.containsKey("fileName") ? fileMap.get("fileName") : fileMap.get("storedName");
            files.add(new StoredFileReference(
                    fieldName,
                    String.valueOf(fileMap.get("storedName")),
                    String.valueOf(rawFileName)
            ));
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> addStoredFile(files, fieldName, item));
        }
    }

    private void record(String tramiteId,
                        String workflowId,
                        String nodoId,
                        StoredFileReference file,
                        User actor,
                        DocumentAuditLog.Action action) {
        DocumentAuditLog log = new DocumentAuditLog();
        log.setTramiteId(tramiteId);
        log.setWorkflowId(workflowId);
        log.setNodoId(nodoId);
        log.setFieldName(file.fieldName());
        log.setStoredName(file.storedName());
        log.setFileName(file.fileName());
        log.setAction(action);
        if (actor != null) {
            log.setUserId(actor.getId());
            log.setUserName(actor.getName());
            log.setUserEmail(actor.getEmail());
            log.setDepartmentId(actor.getDepartmentId());
            if (actor.getDepartmentId() != null) {
                Department department = departmentRepository.findById(actor.getDepartmentId()).orElse(null);
                log.setDepartmentName(department != null ? department.getName() : null);
            }
        }
        documentAuditLogRepository.save(log);
    }

    private boolean isAdmin(User actor) {
        return actor != null && (actor.getRole() == User.Role.ADMIN || actor.getRole() == User.Role.SUPERADMIN);
    }

    private boolean belongsToActorCompany(String workflowId, User actor) {
        if (actor == null || actor.getCompanyId() == null || workflowId == null) return false;
        Workflow workflow = workflowRepository.findById(workflowId).orElse(null);
        return workflow != null && Objects.equals(workflow.getCompanyId(), actor.getCompanyId());
    }

    private boolean matchesResponsibleAssignment(WorkflowNodo nodo, User actor) {
        if (actor == null || nodo == null) return false;
        if (nodo.getResponsibleJobRoleId() != null && !nodo.getResponsibleJobRoleId().isBlank()) {
            boolean matchesJobRole = Objects.equals(nodo.getResponsibleJobRoleId(), actor.getJobRoleId());
            if (!matchesJobRole) return false;
            return nodo.getResponsibleDepartmentId() == null || nodo.getResponsibleDepartmentId().isBlank()
                    || Objects.equals(nodo.getResponsibleDepartmentId(), actor.getDepartmentId());
        }
        if (nodo.getResponsibleDepartmentId() != null && !nodo.getResponsibleDepartmentId().isBlank()) {
            return Objects.equals(nodo.getResponsibleDepartmentId(), actor.getDepartmentId());
        }
        if (nodo.getResponsibleRole() != null) {
            return nodo.getResponsibleRole() == actor.getRole();
        }
        return false;
    }
}
