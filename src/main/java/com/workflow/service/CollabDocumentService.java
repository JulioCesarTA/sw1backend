package com.workflow.service;

import com.workflow.model.CollabDocument;
import com.workflow.model.User;
import com.workflow.repository.CollabDocumentRepository;
import com.workflow.service.DocumentAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CollabDocumentService {

    private final CollabDocumentRepository repository;
    private final FileStorageService fileStorageService;
    private final CollabExportService collabExportService;
    private final DocumentAccessService documentAccessService;

    public CollabDocument create(String tramiteId, String title, User actor) {
        CollabDocument doc = new CollabDocument();
        doc.setTramiteId(tramiteId);
        doc.setCompanyId(actor.getCompanyId());
        doc.setTitle(title != null && !title.isBlank() ? title.trim() : "Documento sin título");
        doc.setCreatedBy(actor.getId());
        doc.setCreatedByName(actor.getName());
        doc.setYdocState(null);
        doc.setTextSnapshot("");
        return repository.save(doc);
    }

    public List<CollabDocument> listByTramite(String tramiteId) {
        return repository.findByTramiteIdOrderByCreatedAtDesc(tramiteId);
    }

    public CollabDocument getById(String docId) {
        CollabDocument doc = repository.findById(docId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento no encontrado"));
        // Re-convert stale placeholder on direct load (e.g., page refresh)
        if (isStaleInitialHtml(doc) && doc.getFileStoredName() != null && doc.getWorkflowId() != null) {
            doc = reConvert(doc);
        }
        return doc;
    }

    private boolean isStaleInitialHtml(CollabDocument doc) {
        String html = doc.getInitialHtml();
        return html == null || html.isBlank() || html.contains("Vista previa no disponible");
    }

    private String convertFileToHtml(String storedName, String workflowId) {
        return convertFileToHtml(storedName, workflowId, null);
    }

    private String convertFileToHtml(String storedName, String workflowId, String downloadPath) {
        try {
            byte[] fileBytes = null;
            // Primero intentar con la URL presignada si viene del frontend
            if (downloadPath != null && !downloadPath.isBlank()) {
                try { fileBytes = fetchBytesFromUrl(downloadPath); } catch (Exception ignored) {}
            }
            // Fallback: leer desde S3 con path legacy
            if (fileBytes == null || fileBytes.length == 0) {
                fileBytes = fileStorageService.readFileBytes(storedName, workflowId);
            }
            String lower = storedName.toLowerCase();
            if (lower.endsWith(".docx")) return collabExportService.readDocxAsHtml(fileBytes);
            if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return collabExportService.readXlsxAsHtml(fileBytes);
        } catch (Exception ignored) {}
        return "";
    }

    private byte[] fetchBytesFromUrl(String url) throws java.io.IOException {
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        if (conn.getResponseCode() >= 400) {
            conn.disconnect();
            throw new java.io.IOException("HTTP " + conn.getResponseCode());
        }
        try (java.io.InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        } finally {
            conn.disconnect();
        }
    }

    private CollabDocument reConvert(CollabDocument doc) {
        String html = convertFileToHtml(doc.getFileStoredName(), doc.getWorkflowId());
        if (html.isBlank()) return doc;
        doc.setInitialHtml(html);
        doc.setUpdatedAt(Instant.now());
        return repository.save(doc);
    }

    public void delete(String docId, User actor) {
        CollabDocument doc = getById(docId);
        boolean isOwner = actor.getId().equals(doc.getCreatedBy());
        boolean isAdmin = actor.getRole() == User.Role.ADMIN || actor.getRole() == User.Role.SUPERADMIN;
        if (!isOwner && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes permiso para eliminar este documento");
        }
        repository.deleteById(docId);
    }

    public Map<String, Object> saveState(String docId, String ydocState, String textSnapshot,
                                         String userId, String userName, String userEmail) {
        CollabDocument doc = getById(docId);
        String textBefore = doc.getTextSnapshot();
        if (ydocState != null && !ydocState.isBlank()) {
            doc.setYdocState(ydocState);
        }
        if (textSnapshot != null) {
            doc.setTextSnapshot(textSnapshot);
        }
        doc.setUpdatedAt(Instant.now());
        repository.save(doc);
        if (userId != null && !userId.isBlank() && textSnapshot != null
                && !textSnapshot.equals(textBefore)) {
            String storedRef = doc.getFileStoredName() != null ? doc.getFileStoredName() : doc.getId();
            documentAccessService.recordCollabEdited(
                    doc.getTramiteId(), doc.getWorkflowId(), storedRef, doc.getTitle(),
                    textBefore, textSnapshot, userId, userName, userEmail);
        }
        return Map.of("ok", true);
    }

    /**
     * Abre (o reutiliza) un CollabDocument vinculado a un archivo del trámite.
     * Si ya existe un doc con ese fileStoredName en el tramite, lo devuelve directamente.
     * Si no, convierte el archivo a HTML y crea un nuevo CollabDocument con ese contenido.
     */
    public CollabDocument openFile(String tramiteId, String storedName, String workflowId,
                                   String title, String downloadPath, User actor) {
        Optional<CollabDocument> existing = repository.findByTramiteIdAndFileStoredName(tramiteId, storedName);

        // Stale = initialHtml is the old fallback placeholder (ydocState may still have user edits).
        // Updating initialHtml is safe because the editor only uses it when ydocState is empty.
        boolean stale = existing.isPresent() && isStaleInitialHtml(existing.get());

        if (existing.isPresent() && !stale) {
            CollabDocument ex = existing.get();
            // Registrar que alguien abrió el documento para colaborar
            documentAccessService.recordCollabOpened(tramiteId, workflowId, storedName,
                    ex.getTitle() != null ? ex.getTitle() : storedName, actor);
            if (ex.getWorkflowId() == null && workflowId != null) {
                ex.setWorkflowId(workflowId);
                return repository.save(ex);
            }
            return ex;
        }

        String html = convertFileToHtml(storedName, workflowId, downloadPath);

        if (stale) {
            CollabDocument doc = existing.get();
            doc.setInitialHtml(html);
            doc.setWorkflowId(workflowId);
            doc.setUpdatedAt(Instant.now());
            // Si no hay ediciones previas (ydocState vacío o era un placeholder),
            // resetear ydocState para que el editor cargue desde initialHtml
            String existingYdoc = doc.getYdocState();
            if (existingYdoc == null || existingYdoc.isBlank()) {
                doc.setYdocState(null);
            }
            return repository.save(doc);
        }

        String resolvedTitle = title != null && !title.isBlank() ? title : storedName;
        documentAccessService.recordCollabOpened(tramiteId, workflowId, storedName, resolvedTitle, actor);

        CollabDocument doc = new CollabDocument();
        doc.setTramiteId(tramiteId);
        doc.setCompanyId(actor.getCompanyId());
        doc.setTitle(resolvedTitle);
        doc.setCreatedBy(actor.getId());
        doc.setCreatedByName(actor.getName());
        doc.setFileStoredName(storedName);
        doc.setWorkflowId(workflowId);
        doc.setInitialHtml(html);
        doc.setYdocState(null);
        doc.setTextSnapshot("");
        return repository.save(doc);
    }

    public Map<String, Object> toSummary(CollabDocument doc) {
        return Map.of(
                "id", doc.getId(),
                "tramiteId", doc.getTramiteId() != null ? doc.getTramiteId() : "",
                "title", doc.getTitle() != null ? doc.getTitle() : "",
                "createdBy", doc.getCreatedBy() != null ? doc.getCreatedBy() : "",
                "createdByName", doc.getCreatedByName() != null ? doc.getCreatedByName() : "",
                "createdAt", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : "",
                "updatedAt", doc.getUpdatedAt() != null ? doc.getUpdatedAt().toString() : ""
        );
    }
}
