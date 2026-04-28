package com.workflow.controller;

import com.workflow.model.User;
import com.workflow.service.TramiteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/activities")
@RequiredArgsConstructor
public class ActivityController {

    private final TramiteService tramiteService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(tramiteService.listActivities(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> findOne(@PathVariable("id") String id,
                                                       @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(tramiteService.findActivity(id, user));
    }

    @PostMapping("/{id}/advance")
    public ResponseEntity<Map<String, Object>> advance(@PathVariable("id") String id,
                                                       @RequestBody Map<String, Object> body,
                                                       @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(tramiteService.advance(id, body, user.getId()));
    }

    @PostMapping("/{id}/voice-fill")
    public ResponseEntity<Map<String, Object>> voiceFill(@PathVariable("id") String id,
                                                         @RequestBody Map<String, Object> body,
                                                         @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(tramiteService.parseVoiceFill(id, body, user));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable("id") String id,
                                    @RequestBody Map<String, Object> body,
                                    @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(tramiteService.reject(id, body, user.getId()));
    }
}
