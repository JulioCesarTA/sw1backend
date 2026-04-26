package com.workflow.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@Document(collection = "workflow_stages")
public class WorkflowStage {

    @Id
    private String id;

    private String workflowId;
    private String name;
    private String description;
    private int order;
    private User.Role responsibleRole;
    private String responsibleDepartmentId;
    private boolean requiresForm = false;
    private int slaHours = 24;
    private String nodeType = "process";
    private boolean isConditional = false;
    private String condition;
    private String trueLabel = "Sí";
    private String falseLabel = "No";
    private Double posX;
    private Double posY;
    private String responsibleJobRoleId;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

}
