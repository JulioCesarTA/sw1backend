package com.workflow.controller;

import com.workflow.model.WorkflowNodo;
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
@RequestMapping("/workflow-nodos")
@RequiredArgsConstructor
public class WorkflowNodosController {

    private final WorkflowService workflowService;
    private final SimpMessagingTemplate messagingTemplate;

    @PostMapping
    public ResponseEntity<WorkflowNodo> create(@RequestBody Map<String, Object> body,
                                               @AuthenticationPrincipal User user) {
        WorkflowNodo created = workflowService.createNodo(body);
        messagingTemplate.convertAndSend(
                "/topic/workflows/" + created.getWorkflowId() + "/collab",
                Map.of(
                        "type", "nodo_created",
                        "workflowId", created.getWorkflowId(),
                        "nodo", created,
                        "userId", user != null ? user.getId() : null
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<WorkflowNodo> update(@PathVariable("id") String id, @RequestBody Map<String, Object> body,
                                               @AuthenticationPrincipal User user) {
        WorkflowNodo updated = workflowService.updateNodo(id, body);
        messagingTemplate.convertAndSend(
                "/topic/workflows/" + updated.getWorkflowId() + "/collab",
                Map.of(
                        "type", "nodo_updated",
                        "workflowId", updated.getWorkflowId(),
                        "nodo", updated,
                        "userId", user != null ? user.getId() : null
                )
        );
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable("id") String id, @AuthenticationPrincipal User user) {
        WorkflowNodo nodo = workflowService.findNodo(id);
        workflowService.deleteNodo(id);
        messagingTemplate.convertAndSend(
                "/topic/workflows/" + nodo.getWorkflowId() + "/collab",
                Map.of(
                        "type", "nodo_deleted",
                        "workflowId", nodo.getWorkflowId(),
                        "nodoId", nodo.getId(),
                        "userId", user != null ? user.getId() : null
                )
        );
        return ResponseEntity.noContent().build();
    }
}
