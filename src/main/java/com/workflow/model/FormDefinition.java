package com.workflow.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
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
    private List<FormField> fields;

    @Data
    @NoArgsConstructor
    public static class FormField {
        private String id;
        private String name;
        private FieldType type;
        private List<String> options;
        private boolean isRequired = false;
        private int order;
    }

    public enum FieldType {
        TEXT, NUMBER, DATE,  FILE, EMAIL
    }
}
