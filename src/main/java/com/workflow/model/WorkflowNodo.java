package com.workflow.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Document(collection = "workflow_nodo")
public class WorkflowNodo {

    @Id
    private String id;

    private String workflowId;
    private String name;
    private String description;
    private int order;
    private User.Role responsibleRole;
    private String responsibleDepartmentId;
    private boolean requiresForm = false;
    private int avgMinutes = 0;
    private String nodeType = "proceso";
    private Double posX;
    private Double posY;
    private String responsibleJobRoleId;
    private String trueLabel;
    private String falseLabel;
    private String condition;
    private List<DocumentPermission> documentPermissions = new ArrayList<>();

    @Transient
    private FormDefinition formDefinition;

    @Data
    @NoArgsConstructor
    public static class DocumentPermission {
        private String departmentId;
        private boolean canCreate;
        private boolean canRead;
        private boolean canEdit;
    }
}
