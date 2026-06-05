package com.workflow.controller;

import com.workflow.service.WorkflowAiProxyService;
import com.workflow.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/workflow-ai")
@RequiredArgsConstructor
public class WorkflowAiController {

    private final WorkflowAiProxyService workflowAiProxyService;

    @PostMapping("/diagramaporcomand")
    public ResponseEntity<Map<String, Object>> diagramCommand(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(workflowAiProxyService.diagramCommand(body));
    }

    @PostMapping("/diagramaporvoz")
    public ResponseEntity<Map<String, Object>> diagramVoiceCommand(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(workflowAiProxyService.diagramVoiceCommand(body));
    }

    @PostMapping("/detectcuellodebotella")
    public ResponseEntity<Map<String, Object>> bottleneckAnalysis(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(workflowAiProxyService.bottleneckAnalysis(body));
    }

    @PostMapping("/sugerenciaworky")
    public ResponseEntity<Map<String, Object>> workySuggestions(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(workflowAiProxyService.workySuggestions(body));
    }

    @PostMapping("/formularioporvoz")
    public ResponseEntity<Map<String, Object>> formVoiceDesign(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(workflowAiProxyService.formVoiceDesign(body));
    }

    @PostMapping(value = "/asistente-clasificacion", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> workflowRouter(@RequestParam("prompt") String prompt,
                                                              @RequestParam(name = "files", required = false) List<MultipartFile> files,
                                                              @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(workflowAiProxyService.workflowRouter(prompt, user != null ? user.getCompanyId() : null, files));
    }
}
