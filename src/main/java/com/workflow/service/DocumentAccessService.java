package com.workflow.service;

import com.workflow.model.Department;
import com.workflow.model.DocumentAuditLog;
import com.workflow.model.FormDefinition;
import com.workflow.model.Tramite;
import com.workflow.model.User;
import com.workflow.model.Workflow;
import com.workflow.model.WorkflowNodo;
import com.workflow.repository.DepartmentRepository;
import com.workflow.repository.DocumentAuditLogRepository;
import com.workflow.repository.TramiteRepository;
import com.workflow.repository.WorkflowNodoRepository;
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
    public record FileAccessContext(String tramiteId, String workflowId, String nodoId, StoredFileReference file) {}

    private final TramiteRepository tramiteRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowNodoRepository workflowNodoRepository;
    private final DepartmentRepository departmentRepository;
    private final DocumentAuditLogRepository documentAuditLogRepository;

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
        if (actor.getDepartmentId() == null || actor.getDepartmentId().isBlank()) return false;
        return nodo.getDocumentPermissions().stream()
                .filter(permission -> actor.getDepartmentId().equals(permission.getDepartmentId()))
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

    public FileAccessContext requireFileAccess(String tramiteId,
                                               String fieldName,
                                               String storedName,
                                               User actor,
                                               PermissionType permissionType) {
        Tramite tramite = tramiteRepository.findById(tramiteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));
        Workflow workflow = workflowRepository.findById(tramite.getWorkflowId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow no encontrado"));
        WorkflowNodo nodo = workflowNodoRepository.findById(tramite.getCurrentNodoId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nodo actual no encontrado"));

        if (!isAdmin(actor) && (actor == null || actor.getCompanyId() == null || !Objects.equals(actor.getCompanyId(), workflow.getCompanyId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a este trámite");
        }
        if (!hasPermission(nodo, actor, permissionType) && !isAdmin(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes permisos sobre este documento");
        }

        StoredFileReference fileReference = extractStoredFiles(tramite.getFormData()).stream()
                .filter(file -> Objects.equals(file.fieldName(), fieldName) && Objects.equals(file.storedName(), storedName))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado en el trámite"));

        return new FileAccessContext(tramite.getId(), workflow.getId(), nodo.getId(), fileReference);
    }

    public void recordRead(String tramiteId, String workflowId, String nodoId, StoredFileReference file, User actor) {
        record(tramiteId, workflowId, nodoId, file, actor, DocumentAuditLog.Action.READ, "Documento consultado");
    }

    public void recordCreated(String tramiteId, String workflowId, String nodoId, StoredFileReference file, User actor) {
        record(tramiteId, workflowId, nodoId, file, actor, DocumentAuditLog.Action.CREATED, "Documento agregado");
    }

    public void recordUpdated(String tramiteId, String workflowId, String nodoId, StoredFileReference file, User actor) {
        record(tramiteId, workflowId, nodoId, file, actor, DocumentAuditLog.Action.UPDATED, "Documento reemplazado o editado");
    }

    public void recordDeleted(String tramiteId, String workflowId, String nodoId, StoredFileReference file, User actor) {
        record(tramiteId, workflowId, nodoId, file, actor, DocumentAuditLog.Action.DELETED, "Documento eliminado");
    }

    public void recordCollabOpened(String tramiteId, String workflowId, String storedName, String fileName, User actor) {
        StoredFileReference ref = new StoredFileReference("collab", storedName, fileName);
        record(tramiteId, workflowId, null, ref, actor, DocumentAuditLog.Action.COLLAB_OPENED, "Archivo abierto en editor colaborativo");
    }

    public void recordCollabEdited(String tramiteId, String workflowId, String storedName, String fileName, User actor) {
        StoredFileReference ref = new StoredFileReference("collab", storedName, fileName);
        record(tramiteId, workflowId, null, ref, actor, DocumentAuditLog.Action.COLLAB_EDITED, "Edición guardada en editor colaborativo");
    }

    public List<Map<String, Object>> listAuditLogs(User actor) {
        if (!isAdmin(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo administradores pueden ver la auditoría documental");
        }
        List<DocumentAuditLog> logs = documentAuditLogRepository.findAll().stream()
                .filter(log -> actor.getRole() == User.Role.SUPERADMIN || belongsToActorCompany(log.getWorkflowId(), actor))
                .sorted(Comparator.comparing(DocumentAuditLog::getCreatedAt).reversed())
                .toList();
        Set<String> workflowIds = logs.stream().map(DocumentAuditLog::getWorkflowId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, String> workflowNames = workflowRepository.findAllById(workflowIds).stream()
                .collect(Collectors.toMap(Workflow::getId, Workflow::getName));
        return logs.stream().map(log -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", log.getId());
            item.put("tramiteId", log.getTramiteId());
            item.put("workflowId", log.getWorkflowId());
            item.put("workflowName", workflowNames.get(log.getWorkflowId()));
            item.put("nodoId", log.getNodoId());
            item.put("fieldName", log.getFieldName());
            item.put("storedName", log.getStoredName());
            item.put("fileName", log.getFileName());
            item.put("action", log.getAction());
            item.put("userId", log.getUserId());
            item.put("userName", log.getUserName());
            item.put("userEmail", log.getUserEmail());
            item.put("departmentId", log.getDepartmentId());
            item.put("departmentName", log.getDepartmentName());
            item.put("comment", log.getComment());
            item.put("createdAt", log.getCreatedAt());
            return item;
        }).toList();
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
                        DocumentAuditLog.Action action,
                        String comment) {
        DocumentAuditLog log = new DocumentAuditLog();
        log.setTramiteId(tramiteId);
        log.setWorkflowId(workflowId);
        log.setNodoId(nodoId);
        log.setFieldName(file.fieldName());
        log.setStoredName(file.storedName());
        log.setFileName(file.fileName());
        log.setAction(action);
        log.setComment(comment);
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
