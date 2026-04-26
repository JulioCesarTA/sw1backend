package com.workflow.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@Document(collection = "procedure_history")
@CompoundIndex(name = "proc_date", def = "{'procedureId': 1, 'changedAt': -1}")
public class ProcedureHistory {

    @Id
    private String id;

    private String procedureId;
    private String fromStageId;
    private String toStageId;
    private String action;
    private String changedById;
    private String comment;
    private String userId;
    private String observation;
    private Instant changedAt = Instant.now();
    private Integer durationInStage;

    @CreatedDate
    private Instant createdAt;
}
