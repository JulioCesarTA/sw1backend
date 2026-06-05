package com.workflow.repository;

import com.workflow.model.CollabDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface CollabDocumentRepository extends MongoRepository<CollabDocument, String> {
    List<CollabDocument> findByTramiteIdOrderByCreatedAtDesc(String tramiteId);
    List<CollabDocument> findByCompanyIdOrderByUpdatedAtDesc(String companyId);
    Optional<CollabDocument> findByTramiteIdAndFileStoredName(String tramiteId, String fileStoredName);
}
