package com.workflow.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@Document(collection = "workflow_transitions")
public class WorkflowTransition {

    @Id
    private String id;

    private String workflowId;
    private String fromNodoId;
    private String toNodoId;
    private String name;
    private String condition;
    private Map<String, Object> forwardConfig;

    @CreatedDate
    private Instant createdAt;

}
