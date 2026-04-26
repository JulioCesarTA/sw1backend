package com.workflow.repository;

import com.workflow.model.Company;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CompanyRepository extends MongoRepository<Company, String> {
    boolean existsByName(String name);
}
