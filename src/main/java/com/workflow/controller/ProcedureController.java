package com.workflow.controller;

import com.workflow.model.Procedure;
import com.workflow.model.ProcedureHistory;
import com.workflow.model.User;
import com.workflow.service.ProcedureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/procedures")
@RequiredArgsConstructor
public class ProcedureController {

    private final ProcedureService procedureService;

    @GetMapping
    public ResponseEntity<List<Procedure>> findAll(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(procedureService.findAll(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> findOne(@PathVariable("id") String id) {
        return ResponseEntity.ok(procedureService.findOne(id));
    }

    @PostMapping
    public ResponseEntity<Procedure> create(@RequestBody Map<String, Object> body,
                                             @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(procedureService.create(body, user.getId()));
    }

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> createAndSubmit(@RequestBody Map<String, Object> body,
                                                               @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(procedureService.createAndSubmit(body, user.getId()));
    }

    @PostMapping("/{id}/advance")
    public ResponseEntity<Map<String, Object>> advance(@PathVariable("id") String id,
                                                        @RequestBody Map<String, Object> body,
                                                        @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(procedureService.advance(id, body, user.getId()));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Procedure> reject(@PathVariable("id") String id,
                                             @RequestBody Map<String, Object> body,
                                             @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(procedureService.reject(id, body, user.getId()));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<ProcedureHistory>> getHistory(@PathVariable("id") String id) {
        return ResponseEntity.ok(procedureService.getHistory(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable("id") String id) {
        procedureService.remove(id);
        return ResponseEntity.noContent().build();
    }
}
