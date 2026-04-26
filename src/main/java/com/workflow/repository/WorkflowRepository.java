package com.workflow.repository;

import com.workflow.model.Workflow;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface WorkflowRepository extends MongoRepository<Workflow, String> {
    List<Workflow> findByCompanyIdOrderByCreatedAtDesc(String companyId);
}
