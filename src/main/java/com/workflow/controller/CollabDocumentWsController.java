package com.workflow.controller;

import com.workflow.service.CollabExportService;
import com.workflow.service.DocumentAccessService;
import com.workflow.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.Map;

/**
 * Relay de edición colaborativa en tiempo real via STOMP.
 *
 * roomId = tramiteId + "_" + storedName  (estable, sin MongoDB)
 *
 * Protocolo:
 *   join       → carga archivo desde S3 (editado si existe, si no el original) → envía init
 *   update     → rebroadcast a todos los suscritos (sin tocar BD)
 *   save-state → convierte HTML/grid → sube a S3 como .edited.* → registra auditoría
 *   peer-state → rebroadcast estado completo a usuario específico
 *   presence   → rebroadcast posición de cursor
 */
@Controller
@RequiredArgsConstructor
public class CollabDocumentWsController {

    private final FileStorageService    fileStorageService;
    private final CollabExportService   collabExportService;
    private final DocumentAccessService documentAccessService;
    private final SimpMessagingTemplate messaging;

    private static final String TOPIC = "/topic/collab-docs/";

    @MessageMapping("/collab-docs/{roomId}/join")
    public void join(@DestinationVariable String roomId,
                     @Payload Map<String, Object> body) {
        String joiningUserId = str(body.get("userId"));
        String workflowName  = str(body.get("workflowName"));
        String tramiteFolder = str(body.get("tramiteFolder"));
        String storedName    = str(body.get("storedName"));
        String title         = str(body.get("title"));

        String initialHtml = "";
        String gridJson    = null;

        if (workflowName != null && tramiteFolder != null && storedName != null) {
            try {
                byte[] bytes = fileStorageService.readBestVersionBytes(storedName, workflowName, tramiteFolder);
                String lower = storedName.toLowerCase();
                if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                    gridJson = collabExportService.xlsxToGridJson(bytes);
                } else if (lower.endsWith(".docx")) {
                    initialHtml = collabExportService.readDocxAsHtml(bytes);
                }
            } catch (Exception ignored) {}
        }

        Map<String, Object> initMsg = new HashMap<>();
        initMsg.put("type", "init");
        initMsg.put("roomId", roomId);
        initMsg.put("targetUserId", joiningUserId);
        initMsg.put("initialHtml", initialHtml);
        if (gridJson != null) initMsg.put("gridJson", gridJson);
        initMsg.put("title", title);
        messaging.convertAndSend(TOPIC + roomId, initMsg);

        // Notificar a pares para que envíen su estado en vivo al nuevo usuario
        Map<String, Object> peerMsg = new HashMap<>();
        peerMsg.put("type", "peer_joined");
        peerMsg.put("roomId", roomId);
        peerMsg.put("joiningUserId", joiningUserId);
        messaging.convertAndSend(TOPIC + roomId, peerMsg);
    }

    @MessageMapping("/collab-docs/{roomId}/update")
    public void update(@DestinationVariable String roomId,
                       @Payload Map<String, Object> body) {
        String userId = str(body.get("userId"));
        if (userId == null) return;
        Map<String, Object> msg = new HashMap<>(body);
        msg.put("type", "update");
        msg.put("roomId", roomId);
        messaging.convertAndSend(TOPIC + roomId, msg);
    }

    @MessageMapping("/collab-docs/{roomId}/save-state")
    public void saveState(@DestinationVariable String roomId,
                          @Payload Map<String, Object> body) {
        String workflowName  = str(body.get("workflowName"));
        String tramiteFolder = str(body.get("tramiteFolder"));
        String storedName    = str(body.get("storedName"));
        String tramiteId     = str(body.get("tramiteId"));
        String workflowId    = str(body.get("workflowId"));
        String title         = str(body.get("title"));
        String userId        = str(body.get("userId"));
        String userName      = str(body.get("userName"));
        String userEmail     = str(body.get("userEmail"));
        String htmlContent   = str(body.get("htmlContent"));
        String gridJson      = str(body.get("gridJson"));
        String textSnapshot  = str(body.get("textSnapshot"));

        if (workflowName == null || tramiteFolder == null || storedName == null) return;

        try {
            // Texto anterior para el diff de auditoría
            String textBefore = "";
            try {
                byte[] existing = fileStorageService.readBestVersionBytes(storedName, workflowName, tramiteFolder);
                textBefore = collabExportService.extractText(storedName, existing);
            } catch (Exception ignored) {}

            // Convertir contenido y subir a S3
            byte[] fileBytes = null;
            String lower = storedName.toLowerCase();
            if ((lower.endsWith(".xlsx") || lower.endsWith(".xls")) && gridJson != null && !gridJson.isBlank()) {
                fileBytes = collabExportService.gridJsonToXlsxBytes(gridJson);
                if (textSnapshot == null || textSnapshot.isBlank()) {
                    textSnapshot = collabExportService.textFromGridJson(gridJson);
                }
            } else if (lower.endsWith(".docx") && htmlContent != null && !htmlContent.isBlank()) {
                fileBytes = collabExportService.htmlToDocxBytes(htmlContent);
            }

            if (fileBytes != null) {
                fileStorageService.storeEditedBytes(fileBytes, storedName, workflowName, tramiteFolder);
                // Extraer texto del archivo convertido para que la comparación use el mismo método
                // en ambos lados del diff (evita diferencias de espacios/líneas vacías)
                textSnapshot = collabExportService.extractText(storedName, fileBytes);
            }

            // Auditoría documental
            if (userId != null && textSnapshot != null && !textSnapshot.isBlank()) {
                documentAccessService.recordCollabEdited(
                        tramiteId, workflowId, storedName, title,
                        textBefore, textSnapshot, userId, userName, userEmail);
            }
        } catch (Exception ignored) {}
    }

    @MessageMapping("/collab-docs/{roomId}/peer-state")
    public void peerState(@DestinationVariable String roomId,
                          @Payload Map<String, Object> body) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "peer_state");
        msg.put("roomId", roomId);
        msg.put("targetUserId", str(body.get("targetUserId")));
        msg.put("update", str(body.get("update")));
        msg.put("fromUserId", str(body.get("fromUserId")));
        messaging.convertAndSend(TOPIC + roomId, msg);
    }

    @MessageMapping("/collab-docs/{roomId}/presence")
    public void presence(@DestinationVariable String roomId,
                         @Payload Map<String, Object> body) {
        Map<String, Object> msg = new HashMap<>(body);
        msg.put("type", "presence");
        msg.put("roomId", roomId);
        messaging.convertAndSend(TOPIC + roomId, msg);
    }

    private String str(Object v) { return v != null ? v.toString() : null; }
}
