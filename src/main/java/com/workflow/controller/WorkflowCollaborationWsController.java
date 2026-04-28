package com.workflow.controller;

import com.workflow.service.WorkflowCollaborationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class WorkflowCollaborationWsController {

    private final WorkflowCollaborationService collaborationService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/workflows/{workflowId}/join")
    public void join(@DestinationVariable String workflowId,
                     @Payload(required = false) Map<String, Object> body) {
        messagingTemplate.convertAndSend(
                "/topic/workflows/" + workflowId + "/collab",
                Map.of(
                        "type", "snapshot",
                        "workflowId", workflowId,
                        "locks", collaborationService.getLocks(workflowId),
                        "targetUserId", stringValue(body != null ? body.get("userId") : null)
                )
        );
    }

    @MessageMapping("/workflows/{workflowId}/lock-nodo")
    public void lockNodo(@DestinationVariable String workflowId,
                          @Payload Map<String, Object> body,
                          @Header("simpSessionId") String sessionId) {

        String nodoId = stringValue(body.get("nodoId"));
        String userId = stringValue(body.get("userId"));
        String userName = stringValue(body.get("userName"));
        if (nodoId == null || nodoId.isBlank() || userId == null || userId.isBlank()) return;

        WorkflowCollaborationService.LockAttemptResult result =
                collaborationService.lockNodo(workflowId, nodoId, sessionId, userId, userName != null ? userName : userId);

        if (result.isGranted()) {
            messagingTemplate.convertAndSend(
                    "/topic/workflows/" + workflowId + "/collab",
                    Map.of("type", "nodo_locked", "lock", result.getLock())
            );
            return;
        }

        messagingTemplate.convertAndSend(
                "/topic/workflows/" + workflowId + "/collab",
                Map.of(
                        "type", "lock_denied",
                        "workflowId", workflowId,
                        "nodoId", nodoId,
                        "lock", result.getExistingLock(),
                        "targetUserId", userId
                )
        );
    }

    @MessageMapping("/workflows/{workflowId}/unlock-nodo")
    public void unlockNodo(@DestinationVariable String workflowId,
                            @Payload Map<String, Object> body,
                            @Header("simpSessionId") String sessionId) {

        String nodoId = stringValue(body.get("nodoId"));
        String userId = stringValue(body.get("userId"));
        if (nodoId == null || nodoId.isBlank() || userId == null || userId.isBlank()) return;

        WorkflowCollaborationService.NodoLock released =
                collaborationService.unlockNodo(workflowId, nodoId, sessionId, userId);
        if (released == null) return;

        messagingTemplate.convertAndSend(
                "/topic/workflows/" + workflowId + "/collab",
                Map.of(
                        "type", "nodo_unlocked",
                        "workflowId", workflowId,
                        "nodoId", nodoId,
                        "userId", released.getUserId()
                )
        );
    }

    @MessageMapping("/workflows/{workflowId}/move-nodo")
    public void moveNodo(@DestinationVariable String workflowId,
                          @Payload Map<String, Object> body,
                          @Header("simpSessionId") String sessionId) {

        String nodoId = stringValue(body.get("nodoId"));
        String userId = stringValue(body.get("userId"));
        Double x = doubleValue(body.get("x"));
        Double y = doubleValue(body.get("y"));
        if (nodoId == null || x == null || y == null || userId == null || userId.isBlank()) return;

        if (!collaborationService.canMoveNodo(workflowId, nodoId, sessionId, userId)) {
            messagingTemplate.convertAndSend(
                    "/topic/workflows/" + workflowId + "/collab",
                    Map.of(
                            "type", "move_denied",
                            "workflowId", workflowId,
                            "nodoId", nodoId,
                            "targetUserId", userId
                    )
            );
            return;
        }

        messagingTemplate.convertAndSend(
                "/topic/workflows/" + workflowId + "/collab",
                Map.of(
                        "type", "nodo_moved",
                        "workflowId", workflowId,
                        "nodoId", nodoId,
                        "x", x,
                        "y", y,
                        "userId", userId
                )
        );
    }

    @MessageMapping("/workflows/{workflowId}/nodo-created")
    public void nodoCreated(@DestinationVariable String workflowId,
                             @Payload Map<String, Object> body) {
        Object nodo = body.get("nodo");
        if (nodo == null) return;

        messagingTemplate.convertAndSend(
                "/topic/workflows/" + workflowId + "/collab",
                Map.of(
                        "type", "nodo_created",
                        "workflowId", workflowId,
                        "nodo", nodo,
                        "userId", stringValue(body.get("userId"))
                )
        );
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        List<WorkflowCollaborationService.NodoLock> released =
                collaborationService.releaseSession(event.getSessionId());

        for (WorkflowCollaborationService.NodoLock lock : released) {
            messagingTemplate.convertAndSend(
                    "/topic/workflows/" + lock.getWorkflowId() + "/collab",
                    Map.of(
                            "type", "nodo_unlocked",
                            "workflowId", lock.getWorkflowId(),
                            "nodoId", lock.getNodoId(),
                            "userId", lock.getUserId()
                    )
            );
        }
    }

    private String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return null;
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
