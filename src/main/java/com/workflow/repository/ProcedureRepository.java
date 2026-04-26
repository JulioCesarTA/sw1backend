package com.workflow.repository;

import com.workflow.model.Procedure;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ProcedureRepository extends MongoRepository<Procedure, String> {
    Optional<Procedure> findByCode(String code);
    List<Procedure> findByAssignedUserIdOrRequestedById(String assignedUserId, String requestedById);
    List<Procedure> findByWorkflowId(String workflowId);
    List<Procedure> findByParentProcedureId(String parentProcedureId);
}
