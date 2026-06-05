package com.workflow.controller;

import com.workflow.service.CollabDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.Map;

/**
 * Relays Yjs CRDT updates between browser clients over STOMP.
 *
 * Protocol:
 *   Client → /app/collab-docs/{id}/join         → server sends current ydocState to that user
 *   Client → /app/collab-docs/{id}/update        → server broadcasts update to all subscribers
 *   Client → /app/collab-docs/{id}/save-state    → server persists Yjs state to MongoDB
 *   Client → /app/collab-docs/{id}/presence      → server broadcasts cursor/presence info
 *
 * All messages are broadcast on /topic/collab-docs/{id}
 */
@Controller
@RequiredArgsConstructor
public class CollabDocumentWsController {

    private final CollabDocumentService service;
    private final SimpMessagingTemplate messaging;

    private static final String TOPIC = "/topic/collab-docs/";

    @MessageMapping("/collab-docs/{docId}/join")
    public void join(@DestinationVariable String docId,
                     @Payload Map<String, Object> body) {
        String joiningUserId = str(body.get("userId"));

        // 1. Send persisted DB state to the joining user
        String ydocState = "";
        String docTitle = "";
        try {
            var doc = service.getById(docId);
            ydocState = doc.getYdocState() != null ? doc.getYdocState() : "";
            docTitle  = doc.getTitle() != null ? doc.getTitle() : "";
        } catch (Exception ignored) {}

        Map<String, Object> initMsg = new HashMap<>();
        initMsg.put("type", "init");
        initMsg.put("docId", docId);
        initMsg.put("targetUserId", joiningUserId);
        initMsg.put("ydocState", ydocState);
        initMsg.put("title", docTitle);
        messaging.convertAndSend(TOPIC + docId, initMsg);

        // 2. Ask existing peers to send their live state to the new user
        //    so the joiner gets any unsaved changes too.
        Map<String, Object> peerMsg = new HashMap<>();
        peerMsg.put("type", "peer_joined");
        peerMsg.put("docId", docId);
        peerMsg.put("joiningUserId", joiningUserId);
        messaging.convertAndSend(TOPIC + docId, peerMsg);
    }

    @MessageMapping("/collab-docs/{docId}/update")
    public void update(@DestinationVariable String docId,
                       @Payload Map<String, Object> body) {
        String update = str(body.get("update"));
        String userId = str(body.get("userId"));
        if (update == null || update.isBlank() || userId == null) return;

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "update");
        msg.put("docId", docId);
        msg.put("update", update);
        msg.put("userId", userId);
        messaging.convertAndSend(TOPIC + docId, msg);
    }

    @MessageMapping("/collab-docs/{docId}/save-state")
    public void saveState(@DestinationVariable String docId,
                          @Payload Map<String, Object> body) {
        String ydocState = str(body.get("ydocState"));
        String textSnapshot = str(body.get("textSnapshot"));
        if (ydocState == null || ydocState.isBlank()) return;
        try {
            service.saveState(docId, ydocState, textSnapshot);
        } catch (Exception ignored) {}
    }

    @MessageMapping("/collab-docs/{docId}/peer-state")
    public void peerState(@DestinationVariable String docId,
                          @Payload Map<String, Object> body) {
        // A peer is forwarding their full Yjs state to a specific joining user
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "peer_state");
        msg.put("docId", docId);
        msg.put("targetUserId", str(body.get("targetUserId")));
        msg.put("update", str(body.get("update")));
        msg.put("fromUserId", str(body.get("fromUserId")));
        messaging.convertAndSend(TOPIC + docId, msg);
    }

    @MessageMapping("/collab-docs/{docId}/presence")
    public void presence(@DestinationVariable String docId,
                         @Payload Map<String, Object> body) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "presence");
        msg.put("docId", docId);
        msg.put("userId", str(body.get("userId")));
        msg.put("userName", str(body.get("userName")));
        msg.put("color", str(body.get("color")));
        msg.put("from", str(body.get("from")));
        msg.put("to", str(body.get("to")));
        messaging.convertAndSend(TOPIC + docId, msg);
    }

    private String str(Object value) {
        return value != null ? value.toString() : null;
    }
}
