package com.workflow.repository;

import com.workflow.model.WorkflowNodo;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

public interface WorkflowNodoRepository extends MongoRepository<WorkflowNodo, String> {
    List<WorkflowNodo> findByWorkflowIdOrderByOrderAsc(String workflowId);
    List<WorkflowNodo> findByWorkflowIdIn(Collection<String> workflowIds);
}
