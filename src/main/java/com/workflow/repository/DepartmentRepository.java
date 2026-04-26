package com.workflow.repository;

import com.workflow.model.Department;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface DepartmentRepository extends MongoRepository<Department, String> {
    List<Department> findByCompanyIdOrderByNameAsc(String companyId);
}
