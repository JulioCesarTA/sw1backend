package com.workflow.controller;

import com.workflow.model.WorkflowTransition;
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
@RequestMapping("/workflow-transitions")
@RequiredArgsConstructor
public class WorkflowTransitionsController {

    private final WorkflowService workflowService;
    private final SimpMessagingTemplate messagingTemplate;

    @PostMapping
    public ResponseEntity<WorkflowTransition> create(@RequestBody Map<String, Object> body,
                                                     @AuthenticationPrincipal User user) {
        WorkflowTransition created = workflowService.createTransition(body);
        messagingTemplate.convertAndSend(
                "/topic/workflows/" + created.getWorkflowId() + "/collab",
                Map.of(
                        "type", "transition_created",
                        "workflowId", created.getWorkflowId(),
                        "transition", created,
                        "userId", user != null ? user.getId() : null
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<WorkflowTransition> update(@PathVariable("id") String id, @RequestBody Map<String, Object> body,
                                                     @AuthenticationPrincipal User user) {
        WorkflowTransition updated = workflowService.updateTransition(id, body);
        messagingTemplate.convertAndSend(
                "/topic/workflows/" + updated.getWorkflowId() + "/collab",
                Map.of(
                        "type", "transition_updated",
                        "workflowId", updated.getWorkflowId(),
                        "transition", updated,
                        "userId", user != null ? user.getId() : null
                )
        );
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable("id") String id, @AuthenticationPrincipal User user) {
        WorkflowTransition transition = workflowService.findTransition(id);
        workflowService.deleteTransition(id);
        messagingTemplate.convertAndSend(
                "/topic/workflows/" + transition.getWorkflowId() + "/collab",
                Map.of(
                        "type", "transition_deleted",
                        "workflowId", transition.getWorkflowId(),
                        "transitionId", transition.getId(),
                        "userId", user != null ? user.getId() : null
                )
        );
        return ResponseEntity.noContent().build();
    }
}
