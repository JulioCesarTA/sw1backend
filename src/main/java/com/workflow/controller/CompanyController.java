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
    @PostMapping
    public ResponseEntity<Company> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(companyService.create(body));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Company> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(companyService.update(id, body));
    }

}
