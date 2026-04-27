package com.workflow.model;

import com.fasterxml.jackson.annotation.JsonCreator;
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
@Document(collection = "tramites")
public class Tramite {

    @Id
    private String id;

    @Indexed(unique = true)
    private String code;

    private String title;
    private String description;
    private String workflowId;
    private Status status = Status.PENDIENTE;
    private String currentStageId;
    private String requestedById;
    private String assignedUserId;
    private Map<String, Object> formData;
    private String parentTramiteId;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum Status {
        PENDIENTE, EN_PROGRESO, OBSERVADO, APROBADO, RECHAZADO, COMPLETADO;

        @JsonCreator
        public static Status fromJson(String value) {
            if (value == null) return PENDIENTE;
            return switch (value.toUpperCase()) {
                case "EN_PROGRESO", "IN_PROGRESS" -> EN_PROGRESO;
                case "OBSERVADO",   "OBSERVED"    -> OBSERVADO;
                case "APROBADO",    "APPROVED"    -> APROBADO;
                case "RECHAZADO",   "REJECTED"    -> RECHAZADO;
                case "COMPLETADO",  "COMPLETED"   -> COMPLETADO;
                default -> PENDIENTE;
            };
        }
    }
}
