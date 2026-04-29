package com.workflow.controller;

import com.workflow.service.WorkflowAiProxyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
