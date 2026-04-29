package com.workflow.controller;

import com.workflow.model.Tramite;
import com.workflow.model.User;
import com.workflow.service.TramiteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tramites")
@RequiredArgsConstructor
public class TramiteController {

    private final TramiteService tramiteService;

    @GetMapping
    public ResponseEntity<List<Tramite>> findAll(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(tramiteService.findAll(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> findOne(@PathVariable("id") String id) {
        return ResponseEntity.ok(tramiteService.findOne(id));
    }

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> createAndSubmit(@RequestBody Map<String, Object> body,
                                                               @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tramiteService.createAndSubmit(body, user.getId()));
    }

    @PostMapping("/voice-fill")
    public ResponseEntity<Map<String, Object>> voiceFillForCreate(@RequestBody Map<String, Object> body,
                                                                  @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(tramiteService.parseVoiceFillForCreate(body, user));
    }
}
