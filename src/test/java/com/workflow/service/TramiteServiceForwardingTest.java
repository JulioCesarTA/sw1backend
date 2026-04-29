package com.workflow.service;

import com.workflow.model.FormDefinition;
import com.workflow.repository.DepartmentRepository;
import com.workflow.repository.FormDefinitionRepository;
import com.workflow.repository.HistorialTramiteRepository;
import com.workflow.repository.JobRoleRepository;
import com.workflow.repository.TramiteRepository;
import com.workflow.repository.UserRepository;
import com.workflow.repository.WorkflowNodoRepository;
import com.workflow.repository.WorkflowRepository;
import com.workflow.repository.WorkflowTransitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TramiteServiceForwardingTest {

    private TramiteService tramiteService;
    private FormDefinition.FormField textField;
    private FormDefinition.FormField fileField;

    @BeforeEach
    void setUp() {
        tramiteService = new TramiteService(
                mock(TramiteRepository.class),
                mock(HistorialTramiteRepository.class),
                mock(WorkflowRepository.class),
                mock(WorkflowNodoRepository.class),
                mock(WorkflowTransitionRepository.class),
                mock(FormDefinitionRepository.class),
                mock(JobRoleRepository.class),
                mock(DepartmentRepository.class),
                mock(UserRepository.class),
                mock(WorkflowAiProxyService.class),
                mock(FcmService.class),
                mock(ReportRealtimeService.class)
        );

        textField = new FormDefinition.FormField();
        textField.setName("descripcion");
        textField.setType(FormDefinition.FieldType.TEXT);

        fileField = new FormDefinition.FormField();
        fileField.setName("adjunto");
        fileField.setType(FormDefinition.FieldType.FILE);
    }

    @Test
    void allModeIncludesNonFileFieldsAndRespectsIncludeFilesFlag() {
        boolean textIncluded = invokeShouldIncludeField(textField, "all", Set.of(), false);
        boolean fileExcluded = invokeShouldIncludeField(fileField, "all", Set.of(), false);
        boolean fileIncluded = invokeShouldIncludeField(fileField, "all", Set.of(), true);

        assertTrue(textIncluded);
        assertFalse(fileExcluded);
        assertTrue(fileIncluded);
    }

    @Test
    void filesOnlyModeKeepsOnlyFileFields() {
        boolean textIncluded = invokeShouldIncludeField(textField, "files-only", Set.of(), true);
        boolean fileIncluded = invokeShouldIncludeField(fileField, "files-only", Set.of(), false);

        assertFalse(textIncluded);
        assertTrue(fileIncluded);
    }

    @Test
    void selectedModeCanIncludeFilesThroughFlag() {
        boolean selectedTextIncluded = invokeShouldIncludeField(textField, "selected", Set.of("descripcion"), false);
        boolean unselectedTextExcluded = invokeShouldIncludeField(textField, "selected", Set.of(), true);
        boolean fileIncludedByFlag = invokeShouldIncludeField(fileField, "selected", Set.of(), true);

        assertTrue(selectedTextIncluded);
        assertFalse(unselectedTextExcluded);
        assertTrue(fileIncludedByFlag);
    }

    @Test
    void resolveForwardModeSupportsLegacyModes() {
        String allMode = (String) ReflectionTestUtils.invokeMethod(
                tramiteService,
                "resolveForwardMode",
                java.util.Map.of("mode", "all")
        );
        String filesOnlyMode = (String) ReflectionTestUtils.invokeMethod(
                tramiteService,
                "resolveForwardMode",
                java.util.Map.of("mode", "files-only")
        );
        String fallbackMode = (String) ReflectionTestUtils.invokeMethod(
                tramiteService,
                "resolveForwardMode",
                java.util.Map.of("mode", "unsupported")
        );

        assertTrue("all".equals(allMode));
        assertTrue("files-only".equals(filesOnlyMode));
        assertTrue("none".equals(fallbackMode));
    }

    private boolean invokeShouldIncludeField(FormDefinition.FormField field,
                                             String mode,
                                             Set<String> selectedFieldNames,
                                             boolean includeFiles) {
        return (Boolean) ReflectionTestUtils.invokeMethod(
                tramiteService,
                "shouldIncludeField",
                field,
                mode,
                selectedFieldNames,
                includeFiles
        );
    }
}
