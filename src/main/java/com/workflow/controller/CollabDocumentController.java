package com.workflow.controller;

import com.workflow.model.CollabDocument;
import com.workflow.model.User;
import com.workflow.service.CollabDocumentService;
import com.workflow.service.CollabExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/collab-documents")
@RequiredArgsConstructor
public class CollabDocumentController {

    private final CollabDocumentService service;
    private final CollabExportService exportService;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal User actor) {
        String tramiteId = body.get("tramiteId");
        String title = body.get("title");
        if (tramiteId == null || tramiteId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tramiteId es requerido"));
        }
        CollabDocument doc = service.create(tramiteId, title, actor);
        return ResponseEntity.ok(service.toSummary(doc));
    }

    @GetMapping("/by-tramite/{tramiteId}")
    public ResponseEntity<List<Map<String, Object>>> listByTramite(@PathVariable String tramiteId) {
        return ResponseEntity.ok(
                service.listByTramite(tramiteId).stream().map(service::toSummary).toList()
        );
    }

    @GetMapping("/{docId}")
    public ResponseEntity<Map<String, Object>> getDoc(@PathVariable String docId) {
        CollabDocument doc = service.getById(docId);
        Map<String, Object> result = new java.util.LinkedHashMap<>(service.toSummary(doc));
        result.put("ydocState",      doc.getYdocState()    != null ? doc.getYdocState()    : "");
        result.put("textSnapshot",   doc.getTextSnapshot() != null ? doc.getTextSnapshot() : "");
        result.put("initialHtml",    doc.getInitialHtml()  != null ? doc.getInitialHtml()  : "");
        result.put("fileStoredName", doc.getFileStoredName() != null ? doc.getFileStoredName() : "");
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{docId}")
    public ResponseEntity<Void> delete(
            @PathVariable String docId,
            @AuthenticationPrincipal User actor) {
        service.delete(docId, actor);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{docId}/save-state")
    public ResponseEntity<Map<String, Object>> saveState(
            @PathVariable String docId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(service.saveState(
                docId,
                body.get("ydocState"),
                body.get("textSnapshot")
        ));
    }

    /**
     * Abre (o reutiliza) un CollabDocument a partir de un archivo del trámite.
     * Devuelve el doc con initialHtml para que el editor lo cargue si ydocState está vacío.
     */
    @PostMapping("/open-file")
    public ResponseEntity<Map<String, Object>> openFile(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal User actor) {
        String tramiteId  = body.get("tramiteId");
        String storedName = body.get("storedName");
        String workflowId = body.get("workflowId");
        String title      = body.get("title");
        if (tramiteId == null || storedName == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "tramiteId y storedName son requeridos"));
        }
        CollabDocument doc = service.openFile(tramiteId, storedName, workflowId, title, actor);
        Map<String, Object> result = new java.util.LinkedHashMap<>(service.toSummary(doc));
        result.put("ydocState",   doc.getYdocState()   != null ? doc.getYdocState()   : "");
        result.put("initialHtml", doc.getInitialHtml()  != null ? doc.getInitialHtml()  : "");
        result.put("fileStoredName", doc.getFileStoredName() != null ? doc.getFileStoredName() : "");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody Map<String, String> body) throws IOException {
        String format = body.getOrDefault("format", "word").trim().toLowerCase();
        String title  = body.getOrDefault("title", "Documento").trim();
        String html   = body.getOrDefault("html", "");
        String ts     = LocalDateTime.now().format(TS);
        String safeName = title.replaceAll("[^\\w\\s-]", "").trim().replace(' ', '_');

        if ("excel".equals(format)) {
            byte[] data = exportService.exportExcel(title, html);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName + "_" + ts + ".xlsx\"")
                    .body(data);
        }

        byte[] data = exportService.exportWord(title, html);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName + "_" + ts + ".docx\"")
                .body(data);
    }
}
