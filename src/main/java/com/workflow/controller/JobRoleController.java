package com.workflow.controller;

import com.workflow.model.JobRole;
import com.workflow.service.JobRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/job-roles")
@RequiredArgsConstructor
public class JobRoleController {

    private final JobRoleService jobRoleService;

    @GetMapping
    public ResponseEntity<List<JobRole>> findAll(@RequestParam(name = "departmentId", required = false) String departmentId) {
        return ResponseEntity.ok(jobRoleService.findAll(departmentId));
    }

    @PostMapping
    public ResponseEntity<JobRole> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(jobRoleService.create(body));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<JobRole> update(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(jobRoleService.update(id, body));
    }
}
