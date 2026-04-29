package com.workflow.controller;

import com.workflow.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(fileStorageService.store(file));
    }

    @GetMapping("/{storedName}/download")
    public ResponseEntity<?> download(@PathVariable String storedName,
                                      @RequestParam(name = "filename", required = false) String filename) {
        if (fileStorageService.isS3Available()) {
            String signedUrl = fileStorageService.createPresignedDownloadUrl(storedName, filename);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, URI.create(signedUrl).toString())
                    .build();
        }

        byte[] data = fileStorageService.readLocalFile(storedName);
        String contentType = fileStorageService.detectContentType(storedName);
        String safeFilename = (filename == null || filename.isBlank()) ? storedName : filename.replace("\"", "");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFilename + "\"")
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
                .body(data);
    }
}
