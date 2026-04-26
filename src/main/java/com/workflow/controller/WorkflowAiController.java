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

    @PostMapping("/diagram-command")
    public ResponseEntity<Map<String, Object>> diagramCommand(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(workflowAiProxyService.diagramCommand(body));
    }

    @PostMapping("/bottleneck-analysis")
    public ResponseEntity<Map<String, Object>> bottleneckAnalysis(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(workflowAiProxyService.bottleneckAnalysis(body));
    }

    @PostMapping("/worky-suggestions")
    public ResponseEntity<Map<String, Object>> workySuggestions(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(workflowAiProxyService.workySuggestions(body));
    }
}
