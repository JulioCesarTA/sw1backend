package com.workflow.service;

import com.workflow.model.FormDefinition;
import com.workflow.repository.FormDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class FormService {

    private final FormDefinitionRepository formRepo;

    public Optional<FormDefinition> findByNodoId(String nodoId) {
        return formRepo.findByNodoId(nodoId);
    }

    public FormDefinition upsert(Map<String, Object> body) {
        String nodoId = (String) body.get("nodoId");
        FormDefinition fd = formRepo.findByNodoId(nodoId).orElse(new FormDefinition());
        fd.setNodoId(nodoId);
        fd.setTitle((String) body.getOrDefault("title", "Formulario"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawFields = (List<Map<String, Object>>) body.getOrDefault("fields", List.of());
        List<FormDefinition.FormField> fields = rawFields.stream().map(f -> {
            FormDefinition.FormField ff = new FormDefinition.FormField();
            ff.setId((String) f.get("id"));
            String rawType = String.valueOf(f.getOrDefault("type", "TEXT")).toUpperCase();
            ff.setType(FormDefinition.FieldType.valueOf(rawType));
            ff.setName((String) f.getOrDefault("name", f.get("id")));
            ff.setColumns(parseGridColumns(f.get("columns")));
            ff.setRequired(Boolean.TRUE.equals(f.get("required")) || Boolean.TRUE.equals(f.get("isRequired")));
            Object order = f.get("order");
            if (order instanceof Number number) {
                ff.setOrder(number.intValue());
            }
            return ff;
        }).toList();
        fd.setFields(fields);
        return formRepo.save(fd);
    }

    @SuppressWarnings("unchecked")
    private List<FormDefinition.GridColumn> parseGridColumns(Object rawColumns) {
        if (!(rawColumns instanceof List<?> columnList) || columnList.isEmpty()) {
            return List.of();
        }

        return columnList.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .map(this::mapGridColumn)
                .toList();
    }

    private FormDefinition.GridColumn mapGridColumn(Map<String, Object> rawColumn) {
        FormDefinition.GridColumn column = new FormDefinition.GridColumn();
        column.setId((String) rawColumn.get("id"));
        column.setName((String) rawColumn.getOrDefault("name", rawColumn.get("id")));
        column.setType(parseGridColumnType(rawColumn.get("type")));
        Object order = rawColumn.get("order");
        if (order instanceof Number number) {
            column.setOrder(number.intValue());
        }
        return column;
    }

    private FormDefinition.FieldType parseGridColumnType(Object rawType) {
        FormDefinition.FieldType parsedType;
        try {
            parsedType = FormDefinition.FieldType.valueOf(String.valueOf(rawType == null ? "TEXT" : rawType).toUpperCase());
        } catch (IllegalArgumentException ex) {
            parsedType = FormDefinition.FieldType.TEXT;
        }
        return parsedType == FormDefinition.FieldType.GRID ? FormDefinition.FieldType.TEXT : parsedType;
    }

    public void deleteByNodoId(String nodoId) {
        formRepo.findByNodoId(nodoId).ifPresent(formRepo::delete);
    }
}
