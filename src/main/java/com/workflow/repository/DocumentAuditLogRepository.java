package com.workflow.repository;

import com.workflow.model.DocumentAuditLog;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface DocumentAuditLogRepository extends MongoRepository<DocumentAuditLog, String> {
    List<DocumentAuditLog> findByWorkflowIdOrderByCreatedAtDesc(String workflowId);
    List<DocumentAuditLog> findByTramiteIdOrderByCreatedAtDesc(String tramiteId);
}
