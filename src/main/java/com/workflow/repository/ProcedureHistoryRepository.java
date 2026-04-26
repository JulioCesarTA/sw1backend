package com.workflow.repository;

import com.workflow.model.ProcedureHistory;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

public interface ProcedureHistoryRepository extends MongoRepository<ProcedureHistory, String> {
    List<ProcedureHistory> findByProcedureIdOrderByChangedAtAsc(String procedureId);
    List<ProcedureHistory> findByToStageId(String stageId);
    List<ProcedureHistory> findByProcedureIdIn(Collection<String> procedureIds);
}
