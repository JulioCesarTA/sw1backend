package com.workflow.controller;

import com.workflow.model.Department;
import com.workflow.service.DepartmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    @GetMapping
    public ResponseEntity<List<Department>> findAll(@RequestParam(required = false) String companyId) {
        return ResponseEntity.ok(departmentService.findAll(companyId));
    }

    @PostMapping
    public ResponseEntity<Department> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(departmentService.create(body));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Department> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(departmentService.update(id, body));
    }
}
