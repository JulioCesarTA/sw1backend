package com.workflow.service;

import com.workflow.model.FormDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceFormFillServiceTest {

    private final VoiceFormFillService service = new VoiceFormFillService();

    @Test
    void parsesCheckboxValuesFromSpanishTranscript() {
        FormDefinition.FormField field = new FormDefinition.FormField();
        field.setName("acepta_terminos");
        field.setType(FormDefinition.FieldType.CHECKBOX);

        FormDefinition formDefinition = new FormDefinition();
        formDefinition.setFields(List.of(field));

        Map<String, Object> result = service.parseTranscript("acepta_terminos si", formDefinition, Map.of());

        assertEquals(true, ((Map<?, ?>) result.get("formData")).get("acepta_terminos"));
        assertTrue(((List<?>) result.get("appliedFields")).size() == 1);
    }
}
