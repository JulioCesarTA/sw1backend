package com.workflow.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@Document(collection = "historial_tramites")
@CompoundIndex(name = "tramite_date", def = "{'tramiteId': 1, 'changedAt': -1}")
public class HistorialTramite {

    @Id
    private String id;

    private String tramiteId;
    private String fromStageId;
    private String toStageId;
    private String action;
    private String changedById;
    private String comment;
    private Instant changedAt = Instant.now();
    private Integer durationInStage;
}
