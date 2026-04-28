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

    @GetMapping("/nodo/{nodoId}")
    public ResponseEntity<FormDefinition> findByNodo(@PathVariable String nodoId) {
        return formService.findByNodoId(nodoId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<FormDefinition> upsert(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(formService.upsert(body));
    }

    @DeleteMapping("/nodo/{nodoId}")
    public ResponseEntity<Void> deleteByNodo(@PathVariable String nodoId) {
        formService.deleteByNodoId(nodoId);
        return ResponseEntity.noContent().build();
    }
}
