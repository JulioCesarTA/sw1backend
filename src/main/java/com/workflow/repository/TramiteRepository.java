package com.workflow.repository;

import com.workflow.model.Tramite;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TramiteRepository extends MongoRepository<Tramite, String> {
    List<Tramite> findByAssignedUserIdOrRequestedById(String assignedUserId, String requestedById);
    List<Tramite> findByParentTramiteId(String parentTramiteId);
}
