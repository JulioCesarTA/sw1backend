package com.workflow.controller;

import com.workflow.model.FormDefinition;
import com.workflow.service.FormService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/forms")
@RequiredArgsConstructor
public class FormController {

    private final FormService formService;

    @GetMapping("/stage/{stageId}")
    public ResponseEntity<FormDefinition> findByStage(@PathVariable String stageId) {
        return formService.findByStageId(stageId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<FormDefinition> upsert(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(formService.upsert(body));
    }

    @DeleteMapping("/stage/{stageId}")
    public ResponseEntity<Void> deleteByStage(@PathVariable String stageId) {
        formService.deleteByStageId(stageId);
        return ResponseEntity.noContent().build();
    }
}
