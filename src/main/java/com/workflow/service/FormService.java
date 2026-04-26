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

    public Optional<FormDefinition> findByStageId(String stageId) {
        return formRepo.findByStageId(stageId);
    }

    public FormDefinition upsert(Map<String, Object> body) {
        String stageId = (String) body.get("stageId");
        FormDefinition fd = formRepo.findByStageId(stageId).orElse(new FormDefinition());
        fd.setStageId(stageId);
        fd.setTitle((String) body.getOrDefault("title", "Formulario"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawFields = (List<Map<String, Object>>) body.getOrDefault("fields", List.of());
        List<FormDefinition.FormField> fields = rawFields.stream().map(f -> {
            FormDefinition.FormField ff = new FormDefinition.FormField();
            ff.setId((String) f.get("id"));
            String rawType = String.valueOf(f.getOrDefault("type", "TEXT")).toUpperCase();
            ff.setType(FormDefinition.FieldType.valueOf(rawType));
            ff.setName((String) f.getOrDefault("name", f.get("id")));
            ff.setRequired(Boolean.TRUE.equals(f.get("required")) || Boolean.TRUE.equals(f.get("isRequired")));
            @SuppressWarnings("unchecked")
            List<String> options = (List<String>) f.get("options");
            ff.setOptions(options);
            Object order = f.get("order");
            if (order instanceof Number number) {
                ff.setOrder(number.intValue());
            }
            return ff;
        }).toList();
        fd.setFields(fields);
        return formRepo.save(fd);
    }

    public void deleteByStageId(String stageId) {
        formRepo.findByStageId(stageId).ifPresent(formRepo::delete);
    }
}
