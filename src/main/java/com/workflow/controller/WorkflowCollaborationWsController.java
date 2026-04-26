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

    @MessageMapping("/workflows/{workflowId}/lock-stage")
    public void lockStage(@DestinationVariable String workflowId,
                          @Payload Map<String, Object> body,
                          @Header("simpSessionId") String sessionId) {

        String stageId = stringValue(body.get("stageId"));
        String userId = stringValue(body.get("userId"));
        String userName = stringValue(body.get("userName"));
        if (stageId == null || stageId.isBlank() || userId == null || userId.isBlank()) return;

        WorkflowCollaborationService.LockAttemptResult result =
                collaborationService.lockStage(workflowId, stageId, sessionId, userId, userName != null ? userName : userId);

        if (result.isGranted()) {
            messagingTemplate.convertAndSend(
                    "/topic/workflows/" + workflowId + "/collab",
                    Map.of("type", "stage_locked", "lock", result.getLock())
            );
            return;
        }

        messagingTemplate.convertAndSend(
                "/topic/workflows/" + workflowId + "/collab",
                Map.of(
                        "type", "lock_denied",
                        "workflowId", workflowId,
                        "stageId", stageId,
                        "lock", result.getExistingLock(),
                        "targetUserId", userId
                )
        );
    }

    @MessageMapping("/workflows/{workflowId}/unlock-stage")
    public void unlockStage(@DestinationVariable String workflowId,
                            @Payload Map<String, Object> body,
                            @Header("simpSessionId") String sessionId) {

        String stageId = stringValue(body.get("stageId"));
        String userId = stringValue(body.get("userId"));
        if (stageId == null || stageId.isBlank() || userId == null || userId.isBlank()) return;

        WorkflowCollaborationService.StageLock released =
                collaborationService.unlockStage(workflowId, stageId, sessionId, userId);
        if (released == null) return;

        messagingTemplate.convertAndSend(
                "/topic/workflows/" + workflowId + "/collab",
                Map.of(
                        "type", "stage_unlocked",
                        "workflowId", workflowId,
                        "stageId", stageId,
                        "userId", released.getUserId()
                )
        );
    }

    @MessageMapping("/workflows/{workflowId}/move-stage")
    public void moveStage(@DestinationVariable String workflowId,
                          @Payload Map<String, Object> body,
                          @Header("simpSessionId") String sessionId) {

        String stageId = stringValue(body.get("stageId"));
        String userId = stringValue(body.get("userId"));
        Double x = doubleValue(body.get("x"));
        Double y = doubleValue(body.get("y"));
        if (stageId == null || x == null || y == null || userId == null || userId.isBlank()) return;

        if (!collaborationService.canMoveStage(workflowId, stageId, sessionId, userId)) {
            messagingTemplate.convertAndSend(
                    "/topic/workflows/" + workflowId + "/collab",
                    Map.of(
                            "type", "move_denied",
                            "workflowId", workflowId,
                            "stageId", stageId,
                            "targetUserId", userId
                    )
            );
            return;
        }

        messagingTemplate.convertAndSend(
                "/topic/workflows/" + workflowId + "/collab",
                Map.of(
                        "type", "stage_moved",
                        "workflowId", workflowId,
                        "stageId", stageId,
                        "x", x,
                        "y", y,
                        "userId", userId
                )
        );
    }

    @MessageMapping("/workflows/{workflowId}/stage-created")
    public void stageCreated(@DestinationVariable String workflowId,
                             @Payload Map<String, Object> body) {
        Object stage = body.get("stage");
        if (stage == null) return;

        messagingTemplate.convertAndSend(
                "/topic/workflows/" + workflowId + "/collab",
                Map.of(
                        "type", "stage_created",
                        "workflowId", workflowId,
                        "stage", stage,
                        "userId", stringValue(body.get("userId"))
                )
        );
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        List<WorkflowCollaborationService.StageLock> released =
                collaborationService.releaseSession(event.getSessionId());

        for (WorkflowCollaborationService.StageLock lock : released) {
            messagingTemplate.convertAndSend(
                    "/topic/workflows/" + lock.getWorkflowId() + "/collab",
                    Map.of(
                            "type", "stage_unlocked",
                            "workflowId", lock.getWorkflowId(),
                            "stageId", lock.getStageId(),
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
