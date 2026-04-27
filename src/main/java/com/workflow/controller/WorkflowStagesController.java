package com.workflow.controller;

import com.workflow.model.WorkflowStage;
import com.workflow.model.User;
import com.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/workflow-stages")
@RequiredArgsConstructor
public class WorkflowStagesController {

    private final WorkflowService workflowService;
    private final SimpMessagingTemplate messagingTemplate;

    @PostMapping
    public ResponseEntity<WorkflowStage> create(@RequestBody Map<String, Object> body,
                                                @AuthenticationPrincipal User user) {
        WorkflowStage created = workflowService.createStage(body);
        messagingTemplate.convertAndSend(
                "/topic/workflows/" + created.getWorkflowId() + "/collab",
                Map.of(
                        "type", "stage_created",
                        "workflowId", created.getWorkflowId(),
                        "stage", created,
                        "userId", user != null ? user.getId() : null
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<WorkflowStage> update(@PathVariable("id") String id, @RequestBody Map<String, Object> body,
                                                @AuthenticationPrincipal User user) {
        WorkflowStage updated = workflowService.updateStage(id, body);
        messagingTemplate.convertAndSend(
                "/topic/workflows/" + updated.getWorkflowId() + "/collab",
                Map.of(
                        "type", "stage_updated",
                        "workflowId", updated.getWorkflowId(),
                        "stage", updated,
                        "userId", user != null ? user.getId() : null
                )
        );
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable("id") String id, @AuthenticationPrincipal User user) {
        WorkflowStage stage = workflowService.findStage(id);
        workflowService.deleteStage(id);
        messagingTemplate.convertAndSend(
                "/topic/workflows/" + stage.getWorkflowId() + "/collab",
                Map.of(
                        "type", "stage_deleted",
                        "workflowId", stage.getWorkflowId(),
                        "stageId", stage.getId(),
                        "userId", user != null ? user.getId() : null
                )
        );
        return ResponseEntity.noContent().build();
    }
}
