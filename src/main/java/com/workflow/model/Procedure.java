package com.workflow.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@Document(collection = "procedures")
public class Procedure {

    @Id
    private String id;

    @Indexed(unique = true)
    private String code;

    private String title;
    private String description;
    private String clientName;
    private String documentNumber;
    private String phone;
    private String address;
    private String requestType;
    private String workflowId;
    private Status status = Status.PENDING;
    private String currentStageId;
    private String requestedById;
    private String assignedUserId;
    private String currentResponsibleId;
    private Map<String, String> nextResponsibleMap;
    private Map<String, Object> formData;
    private String parentProcedureId;
    private Instant startedAt = Instant.now();
    private Instant closedAt;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum Status {
        PENDING, IN_PROGRESS, OBSERVED, APPROVED, REJECTED, COMPLETED
    }
}
