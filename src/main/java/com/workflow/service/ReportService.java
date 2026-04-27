package com.workflow.service;

import com.workflow.model.Department;
import com.workflow.model.JobRole;
import com.workflow.model.Tramite;
import com.workflow.model.HistorialTramite;
import com.workflow.model.WorkflowStage;
import com.workflow.repository.TramiteRepository;
import com.workflow.repository.DepartmentRepository;
import com.workflow.repository.JobRoleRepository;
import com.workflow.repository.HistorialTramiteRepository;
import com.workflow.repository.WorkflowStageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final TramiteRepository tramiteRepo;
    private final HistorialTramiteRepository historialTramiteRepo;
    private final WorkflowStageRepository workflowStageRepo;
    private final DepartmentRepository departmentRepo;
    private final JobRoleRepository jobRoleRepo;

    public Map<String, Object> getDashboardStats() {
        List<Tramite> tramites = tramiteRepo.findAll();
        List<HistorialTramite> histories = historialTramiteRepo.findByTramiteIdIn(tramites.stream().map(Tramite::getId).toList());
        Set<String> stageIds = collectStageReferenceIds(histories.stream().map(HistorialTramite::getToStageId).toList());
        Map<String, WorkflowStage> stagesById = workflowStageRepo.findAllById(stageIds).stream()
                .collect(Collectors.toMap(WorkflowStage::getId, stage -> stage, (left, right) -> left));
        Set<String> departmentIds = collectStageReferenceIds(stagesById.values().stream().map(WorkflowStage::getResponsibleDepartmentId).toList());
        Set<String> jobRoleIds = collectStageReferenceIds(stagesById.values().stream().map(WorkflowStage::getResponsibleJobRoleId).toList());
        Map<String, String> departmentNames = departmentRepo.findAllById(departmentIds).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        Map<String, String> jobRoleNames = jobRoleRepo.findAllById(jobRoleIds).stream()
                .collect(Collectors.toMap(JobRole::getId, JobRole::getName));
        Map<String, Long> byStatus = countByStatus(tramites);

        Map<String, RolePerformanceAccumulator> rolePerformance = new LinkedHashMap<>();
        Map<String, Long> departmentFlow = new LinkedHashMap<>();

        for (HistorialTramite history : histories) {
            String stageId = history.getToStageId();
            if (stageId == null || stageId.isBlank()) continue;
            WorkflowStage stage = stagesById.get(stageId);
            if (stage == null) continue;

            String departmentId = stage.getResponsibleDepartmentId();
            String jobRoleId = stage.getResponsibleJobRoleId();
            String departmentName = departmentNames.getOrDefault(departmentId, "Sin departamento");
            String jobRoleName = jobRoleNames.getOrDefault(jobRoleId, "Sin rol");

            if (departmentId != null && !departmentId.isBlank()) {
                departmentFlow.merge(departmentName, 1L, Long::sum);
            }

            Integer durationInStage = history.getDurationInStage();
            if (durationInStage == null || durationInStage <= 0 || jobRoleId == null || jobRoleId.isBlank()) {
                continue;
            }

            String key = departmentName + "|" + jobRoleName;
            RolePerformanceAccumulator acc = rolePerformance.computeIfAbsent(key, ignored ->
                    new RolePerformanceAccumulator(departmentName, jobRoleName));
            acc.totalCompleted += 1;
            acc.totalDurationHours += durationInStage;
            acc.totalAvgHours += stage.getAvgHours();
            if (durationInStage <= stage.getAvgHours()) {
                acc.finishedEarly += 1;
            } else {
                acc.finishedLate += 1;
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalTramites", tramites.size());
        stats.put("byStatus", byStatus);
        stats.put("rolePerformance", rolePerformance.values().stream()
                .sorted(Comparator
                        .comparingInt(RolePerformanceAccumulator::lateCount).reversed()
                        .thenComparing(RolePerformanceAccumulator::departmentName)
                        .thenComparing(RolePerformanceAccumulator::jobRoleName))
                .map(RolePerformanceAccumulator::toMap)
                .toList());
        stats.put("departmentFlow", departmentFlow.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("departmentName", entry.getKey());
                    item.put("total", entry.getValue());
                    return item;
                })
                .toList());
        return stats;
    }

    private Set<String> collectStageReferenceIds(List<String> ids) {
        return ids.stream().filter(id -> id != null && !id.isBlank()).collect(Collectors.toSet());
    }

    private static final Set<Tramite.Status> VISIBLE_STATUSES = Set.of(
            Tramite.Status.PENDIENTE, Tramite.Status.EN_PROGRESO, Tramite.Status.COMPLETADO);

    private Map<String, Long> countByStatus(List<Tramite> tramites) {
        Map<Tramite.Status, Long> counts = tramites.stream()
                .collect(Collectors.groupingBy(Tramite::getStatus, () -> new EnumMap<>(Tramite.Status.class), Collectors.counting()));
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Tramite.Status status : VISIBLE_STATUSES) {
            byStatus.put(status.name(), counts.getOrDefault(status, 0L));
        }
        return byStatus;
    }

    private static final class RolePerformanceAccumulator {
        private final String departmentName;
        private final String jobRoleName;
        private int finishedEarly = 0;
        private int finishedLate = 0;
        private int totalCompleted = 0;
        private double totalDurationHours = 0D;
        private double totalAvgHours = 0D;

        private RolePerformanceAccumulator(String departmentName, String jobRoleName) {
            this.departmentName = departmentName;
            this.jobRoleName = jobRoleName;
        }

        private int lateCount() {
            return finishedLate;
        }

        private String departmentName() {
            return departmentName;
        }

        private String jobRoleName() {
            return jobRoleName;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("departmentName", departmentName);
            item.put("jobRoleName", jobRoleName);
            item.put("finishedEarly", finishedEarly);
            item.put("finishedLate", finishedLate);
            item.put("totalCompleted", totalCompleted);
            item.put("averageDurationHours", totalCompleted == 0 ? 0D : round(totalDurationHours / totalCompleted));
            item.put("averageAvgHours", totalCompleted == 0 ? 0D : round(totalAvgHours / totalCompleted));
            return item;
        }

        private double round(double value) {
            return Math.round(value * 100.0) / 100.0;
        }
    }
}
