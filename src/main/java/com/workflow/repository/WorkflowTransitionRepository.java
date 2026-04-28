package com.workflow.repository;

import com.workflow.model.WorkflowTransition;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface WorkflowTransitionRepository extends MongoRepository<WorkflowTransition, String> {
    List<WorkflowTransition> findByWorkflowIdOrderByCreatedAtAsc(String workflowId);
    java.util.Optional<WorkflowTransition> findByIdAndWorkflowId(String id, String workflowId);
    void deleteByFromNodoIdOrToNodoId(String fromNodoId, String toNodoId);
}
