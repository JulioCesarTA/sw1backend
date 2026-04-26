package com.workflow.controller;

import com.workflow.model.Company;
import com.workflow.service.CompanyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    @GetMapping
    public ResponseEntity<List<Company>> findAll() {
        return ResponseEntity.ok(companyService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Company> findOne(@PathVariable String id) {
        return ResponseEntity.ok(companyService.findOne(id));
    }

    @PostMapping
    public ResponseEntity<Company> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(companyService.create(body));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Company> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(companyService.update(id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable String id) {
        companyService.remove(id);
        return ResponseEntity.noContent().build();
    }
}
