package com.workflow.service;

import com.workflow.model.Company;
import com.workflow.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepo;

    public List<Company> findAll() {
        return companyRepo.findAll();
    }

    public Company findOne(String id) {
        return companyRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada"));
    }

    public Company create(Map<String, Object> body) {
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        }
        if (companyRepo.existsByName(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La empresa ya existe");
        }
        Company company = new Company();
        company.setName(name);
        company.setDescription((String) body.get("description"));
        return companyRepo.save(company);
    }

    public Company update(String id, Map<String, Object> body) {
        Company company = findOne(id);
        if (body.containsKey("name")) company.setName((String) body.get("name"));
        if (body.containsKey("description")) company.setDescription((String) body.get("description"));
        return companyRepo.save(company);
    }

    public void remove(String id) {
        findOne(id);
        companyRepo.deleteById(id);
    }
}
