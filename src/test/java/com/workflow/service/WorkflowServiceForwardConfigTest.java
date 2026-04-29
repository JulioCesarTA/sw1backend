package com.workflow.service;

import com.workflow.model.WorkflowTransition;
import com.workflow.repository.CompanyRepository;
import com.workflow.repository.DepartmentRepository;
import com.workflow.repository.FormDefinitionRepository;
import com.workflow.repository.WorkflowNodoRepository;
import com.workflow.repository.WorkflowRepository;
import com.workflow.repository.WorkflowTransitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WorkflowServiceForwardConfigTest {

    private WorkflowService workflowService;

    @BeforeEach
    void setUp() {
        workflowService = new WorkflowService(
                mock(WorkflowRepository.class),
                mock(WorkflowNodoRepository.class),
                mock(WorkflowTransitionRepository.class),
                mock(FormDefinitionRepository.class),
                mock(CompanyRepository.class),
                mock(DepartmentRepository.class)
        );
    }

    @Test
    void applyTransitionFieldsPreservesAllMode() {
        WorkflowTransition transition = new WorkflowTransition();
        ReflectionTestUtils.invokeMethod(
                workflowService,
                "applyTransitionFields",
                transition,
                Map.of("forwardConfig", Map.of("mode", "all", "includeFiles", true))
        );

        assertEquals("all", transition.getForwardConfig().get("mode"));
        assertEquals(true, transition.getForwardConfig().get("includeFiles"));
        assertEquals(List.of(), transition.getForwardConfig().get("fieldNames"));
    }

    @Test
    void applyTransitionFieldsPreservesFilesOnlyMode() {
        WorkflowTransition transition = new WorkflowTransition();
        ReflectionTestUtils.invokeMethod(
                workflowService,
                "applyTransitionFields",
                transition,
                Map.of("forwardConfig", Map.of("mode", "files-only"))
        );

        assertEquals("files-only", transition.getForwardConfig().get("mode"));
        assertEquals(true, transition.getForwardConfig().get("includeFiles"));
    }

    @Test
    void applyTransitionFieldsKeepsSelectedFieldNames() {
        WorkflowTransition transition = new WorkflowTransition();
        ReflectionTestUtils.invokeMethod(
                workflowService,
                "applyTransitionFields",
                transition,
                Map.of("forwardConfig", Map.of("mode", "selected", "fieldNames", List.of("adjunto", "nombre")))
        );

        assertEquals("selected", transition.getForwardConfig().get("mode"));
        assertTrue(((List<?>) transition.getForwardConfig().get("fieldNames")).contains("adjunto"));
        assertTrue(((List<?>) transition.getForwardConfig().get("fieldNames")).contains("nombre"));
    }
}
