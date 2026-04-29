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

    @Indexed(unique = true, sparse = true)
    private String nodoId;

    private String title;
    private List<FormField> fields;

    @Data
    @NoArgsConstructor
    public static class FormField {
        private String id;
        private String name;
        private FieldType type;
        private List<GridColumn> columns;
        private boolean isRequired = false;
        private int order;
    }

    @Data
    @NoArgsConstructor
    public static class GridColumn {
        private String id;
        private String name;
        private FieldType type;
        private int order;
    }

    public enum FieldType {
        TEXT, NUMBER, DATE, FILE, EMAIL, CHECKBOX, GRID
    }
}
