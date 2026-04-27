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

    public Company create(Map<String, Object> body) {
        String name = (String) body.get("name");
        Company company = new Company();
        company.setName(name);
        return companyRepo.save(company);
    }

    public Company update(String id, Map<String, Object> body) {
        Company company = companyRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada"));
        if (body.containsKey("name")) company.setName((String) body.get("name"));
        return companyRepo.save(company);
    }
}
