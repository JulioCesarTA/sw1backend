package com.workflow.controller;

import com.workflow.model.User;
import com.workflow.service.DocumentAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/document-audit")
@RequiredArgsConstructor
public class DocumentAuditController {

    private final DocumentAccessService documentAccessService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(documentAccessService.listAuditLogs(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(
            @PathVariable String id,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(documentAccessService.getAuditLogDetail(id, user));
    }
}
