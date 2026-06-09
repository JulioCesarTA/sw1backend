package com.workflow.controller;

import com.workflow.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    /**
     * Sube un archivo al bucket con estructura:
     *   {workflowName}/{tramiteFolder}/{uuid}.ext
     *
     * workflowName  : nombre del workflow (obligatorio para estructura nueva)
     * tramiteFolder : UUID generado por el frontend antes del primer upload
     * workflowId    : parámetro legacy — se usa si no viene workflowName
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "workflowName",   required = false) String workflowName,
            @RequestParam(name = "tramiteFolder",  required = false) String tramiteFolder,
            @RequestParam(name = "workflowId",     required = false) String workflowId) {

        if (workflowName != null && !workflowName.isBlank()
                && tramiteFolder != null && !tramiteFolder.isBlank()) {
            return ResponseEntity.ok(fileStorageService.store(file, workflowName, tramiteFolder));
        }
        return ResponseEntity.ok(fileStorageService.store(file, workflowId));
    }
}
