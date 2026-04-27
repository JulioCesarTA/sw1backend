package com.workflow.service;

import com.workflow.model.Company;
import com.workflow.model.Department;
import com.workflow.repository.CompanyRepository;
import com.workflow.repository.DepartmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentRepository departmentRepo;
    private final CompanyRepository companyRepo;

    public List<Department> findAll(String companyId) {
        if (companyId != null && !companyId.isBlank()) {
            return departmentRepo.findByCompanyIdOrderByNameAsc(companyId);
        }
        return departmentRepo.findAll();
    }

    public Department create(Map<String, Object> body) {
        String companyId = (String) body.get("companyId");
        Company company = companyRepo.findById(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empresa no encontrada"));
        Department department = new Department();
        department.setCompanyId(company.getId());
        department.setName((String) body.get("name"));
        return departmentRepo.save(department);
    }

    public Department update(String id, Map<String, Object> body) {
        Department department = departmentRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Departamento no encontrado"));
        if (body.containsKey("companyId")) {
            String companyId = (String) body.get("companyId");
            companyRepo.findById(companyId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empresa no encontrada"));
            department.setCompanyId(companyId);
        }
        if (body.containsKey("name")) department.setName((String) body.get("name"));
        return departmentRepo.save(department);
    }
}
