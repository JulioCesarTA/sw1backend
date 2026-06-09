package com.workflow.controller;

import com.workflow.model.User;
import com.workflow.service.CollabExportService;
import com.workflow.service.DocumentAccessService;
import com.workflow.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/collab-documents")
@RequiredArgsConstructor
public class CollabDocumentController {

    private final FileStorageService    fileStorageService;
    private final CollabExportService   collabExportService;
    private final DocumentAccessService documentAccessService;

    /**
     * Abre un archivo para edición colaborativa.
     * - Lee la versión editada desde S3 si existe, si no el original.
     * - Devuelve roomId estable (tramiteId_storedName) para que el frontend use como sala STOMP.
     * - Registra en auditoría que el archivo fue abierto.
     */
    @PostMapping("/open-file")
    public ResponseEntity<Map<String, Object>> openFile(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal User actor) {

        String tramiteId     = body.get("tramiteId");
        String storedName    = body.get("storedName");
        String workflowName  = body.get("workflowName");
        String tramiteFolder = body.get("tramiteFolder");
        String workflowId    = body.get("workflowId");
        String title         = body.get("title");

        if (tramiteId == null || storedName == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "tramiteId y storedName son requeridos"));
        }

        // roomId: identificador estable de la sala STOMP, sin MongoDB
        String roomId = tramiteId + "_" + storedName;

        // Auditoría: apertura del editor colaborativo
        String resolvedTitle = title != null && !title.isBlank() ? title : storedName;
        documentAccessService.recordCollabOpened(tramiteId, workflowId, storedName, resolvedTitle, actor);

        String initialHtml = "";
        String gridJson    = null;

        if (workflowName != null && tramiteFolder != null) {
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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomId",        roomId);
        result.put("id",            roomId);   // alias para compatibilidad con router.navigate
        result.put("title",         resolvedTitle);
        result.put("fileStoredName", storedName);
        result.put("initialHtml",   initialHtml);
        result.put("ydocState",     gridJson != null ? gridJson : "");
        result.put("workflowName",  workflowName != null ? workflowName : "");
        result.put("tramiteFolder", tramiteFolder != null ? tramiteFolder : "");
        result.put("workflowId",    workflowId != null ? workflowId : "");
        result.put("tramiteId",     tramiteId);

        return ResponseEntity.ok(result);
    }
}
