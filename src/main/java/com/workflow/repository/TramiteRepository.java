package com.workflow.repository;

import com.workflow.model.Tramite;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface TramiteRepository extends MongoRepository<Tramite, String> {
    Optional<Tramite> findByCode(String code);
    List<Tramite> findByAssignedUserIdOrRequestedById(String assignedUserId, String requestedById);
    List<Tramite> findByWorkflowId(String workflowId);
    List<Tramite> findByParentTramiteId(String parentTramiteId);
}
