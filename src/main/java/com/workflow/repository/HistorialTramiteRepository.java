package com.workflow.repository;

import com.workflow.model.HistorialTramite;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

public interface HistorialTramiteRepository extends MongoRepository<HistorialTramite, String> {
    List<HistorialTramite> findByTramiteIdOrderByChangedAtAsc(String tramiteId);
    List<HistorialTramite> findByTramiteIdIn(Collection<String> tramiteIds);
}
