package com.workflow.repository;

import com.workflow.model.FormDefinition;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FormDefinitionRepository extends MongoRepository<FormDefinition, String> {
    Optional<FormDefinition> findByNodoId(String nodoId);
    List<FormDefinition> findByNodoIdIn(Collection<String> nodoIds);
}
