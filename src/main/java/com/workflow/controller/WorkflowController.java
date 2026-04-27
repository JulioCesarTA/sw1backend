package com.workflow.controller;

import com.workflow.model.User;
import com.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> findAll(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(workflowService.findAll(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> findOne(@PathVariable("id") String id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(workflowService.findOne(id, user));
    }

    @PostMapping
    public ResponseEntity<Object> create(@RequestBody Map<String, Object> body, @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowService.create(body, user));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Object> update(@PathVariable("id") String id,
                                         @RequestBody Map<String, Object> body,
                                         @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(workflowService.update(id, body, user));
    }
}
