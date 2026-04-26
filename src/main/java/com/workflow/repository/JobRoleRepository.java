package com.workflow.repository;

import com.workflow.model.JobRole;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface JobRoleRepository extends MongoRepository<JobRole, String> {
    List<JobRole> findByCompanyIdOrderByNameAsc(String companyId);
    List<JobRole> findByDepartmentIdOrderByNameAsc(String departmentId);
}
