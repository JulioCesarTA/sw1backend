package com.workflow.service;

import com.workflow.model.Department;
import com.workflow.model.JobRole;
import com.workflow.repository.DepartmentRepository;
import com.workflow.repository.JobRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JobRoleService {

    private final JobRoleRepository jobRoleRepository;
    private final DepartmentRepository departmentRepository;

    public List<JobRole> findAll(String departmentId) {
        if (departmentId != null && !departmentId.isBlank()) {
            return jobRoleRepository.findByDepartmentIdOrderByNameAsc(departmentId);
        }
        return jobRoleRepository.findAll();
    }

    public JobRole create(Map<String, Object> body) {
        String departmentId = (String) body.get("departmentId");
        Department department = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Departamento no encontrado"));
        JobRole jobRole = new JobRole();
        jobRole.setCompanyId(department.getCompanyId());
        jobRole.setDepartmentId(department.getId());
        jobRole.setName((String) body.get("name"));
        return jobRoleRepository.save(jobRole);
    }

    public JobRole update(String id, Map<String, Object> body) {
        JobRole jobRole = jobRoleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rol no encontrado"));
        if (body.containsKey("departmentId")) {
            String departmentId = (String) body.get("departmentId");
            Department department = departmentRepository.findById(departmentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Departamento no encontrado"));
            jobRole.setDepartmentId(department.getId());
            jobRole.setCompanyId(department.getCompanyId());
        }
        if (body.containsKey("name")) jobRole.setName((String) body.get("name"));
        return jobRoleRepository.save(jobRole);
    }
}
