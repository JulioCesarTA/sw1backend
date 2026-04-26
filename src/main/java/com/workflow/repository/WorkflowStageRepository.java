package com.workflow.repository;

import com.workflow.model.WorkflowStage;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

public interface WorkflowStageRepository extends MongoRepository<WorkflowStage, String> {
    List<WorkflowStage> findByWorkflowIdOrderByOrderAsc(String workflowId);
    List<WorkflowStage> findByWorkflowIdIn(Collection<String> workflowIds);
}
