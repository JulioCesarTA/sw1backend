package com.workflow.repository;

import com.workflow.model.DocumentAuditLog;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

public interface DocumentAuditLogRepository extends MongoRepository<DocumentAuditLog, String> {
    List<DocumentAuditLog> findByWorkflowIdOrderByCreatedAtDesc(String workflowId);
    List<DocumentAuditLog> findByTramiteIdOrderByCreatedAtDesc(String tramiteId);
    /** Para ADMIN: filtra por workflows de la empresa directamente en DB */
    List<DocumentAuditLog> findByWorkflowIdInOrderByCreatedAtDesc(Collection<String> workflowIds);
    /** Para SUPERADMIN: los 500 más recientes */
    List<DocumentAuditLog> findTop500ByOrderByCreatedAtDesc();
}
