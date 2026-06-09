package com.workflow.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@Document(collection = "document_audit_logs")
@CompoundIndex(name = "tramite_document_date", def = "{'tramiteId': 1, 'storedName': 1, 'createdAt': -1}")
@CompoundIndex(name = "workflow_date",         def = "{'workflowId': 1, 'createdAt': -1}")
@CompoundIndex(name = "date_only",             def = "{'createdAt': -1}")
public class DocumentAuditLog {

    public enum Action {
        READ,
        CREATED,
        UPDATED,
        DELETED,
        COLLAB_OPENED,
        COLLAB_EDITED
    }

    @Id
    private String id;

    private String tramiteId;
    private String workflowId;
    private String nodoId;
    private String fieldName;
    private String storedName;
    private String fileName;
    private Action action;
    private String userId;
    private String userName;
    private String userEmail;
    private String departmentId;
    private String departmentName;
    private String textBefore;
    private String textAfter;
    private Instant createdAt = Instant.now();
}
