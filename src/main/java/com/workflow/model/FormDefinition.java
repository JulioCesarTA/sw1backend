package com.workflow.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@Document(collection = "form_definitions")
public class FormDefinition {

    @Id
    private String id;

    @Indexed(unique = true)
    private String stageId;

    private String title;
    private String description;
    private List<FormField> fields;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Data
    @NoArgsConstructor
    public static class FormField {
        private String id;
        private String label;
        private String name;
        private FieldType type;
        private String placeholder;
        private List<String> options;
        private boolean isRequired = false;
        private int order;
    }

    public enum FieldType {
        TEXT, TEXTAREA, NUMBER, DATE, SELECT, CHECKBOX, RADIO, FILE
    }
}
