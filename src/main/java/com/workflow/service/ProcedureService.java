package com.workflow.service;

import com.workflow.model.Department;
import com.workflow.model.FormDefinition;
import com.workflow.model.JobRole;
import com.workflow.model.Procedure;
import com.workflow.model.ProcedureHistory;
import com.workflow.model.User;
import com.workflow.model.Workflow;
import com.workflow.model.WorkflowStage;
import com.workflow.model.WorkflowTransition;
import com.workflow.repository.DepartmentRepository;
import com.workflow.repository.FormDefinitionRepository;
import com.workflow.repository.JobRoleRepository;
import com.workflow.repository.ProcedureHistoryRepository;
import com.workflow.repository.ProcedureRepository;
import com.workflow.repository.UserRepository;
import com.workflow.repository.WorkflowRepository;
import com.workflow.repository.WorkflowStageRepository;
import com.workflow.repository.WorkflowTransitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProcedureService {

    private final ProcedureRepository procedureRepo;
    private final ProcedureHistoryRepository historyRepo;
    private final WorkflowRepository workflowRepo;
    private final WorkflowStageRepository stageRepo;
    private final WorkflowTransitionRepository transitionRepo;
    private final FormDefinitionRepository formRepo;
    private final JobRoleRepository jobRoleRepo;
    private final DepartmentRepository departmentRepo;
    private final UserRepository userRepository;
    private final FcmService fcmService;
    private final ReportRealtimeService reportRealtimeService;

    public List<Procedure> findAll(User user) {
        if (user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.SUPERADMIN) {
            return procedureRepo.findAll();
        }
        if (user.getRole() == User.Role.CLIENTE) {
            String email = user.getEmail();
            if (email == null || email.isBlank()) return List.of();
            return procedureRepo.findAll().stream()
                    .filter(p -> p.getFormData() != null &&
                            p.getFormData().values().stream()
                                    .anyMatch(v -> email.equalsIgnoreCase(v != null ? v.toString() : null)))
                    .toList();
        }
        return procedureRepo.findByAssignedUserIdOrRequestedById(user.getId(), user.getId());
    }

    public Map<String, Object> findOne(String id) {
        Procedure procedure = procedureRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));
        List<ProcedureHistory> history = historyRepo.findByProcedureIdOrderByChangedAtAsc(procedure.getId());

        // Find active parallel branch clones (only for root procedures)
        boolean isActive = procedure.getStatus() != Procedure.Status.COMPLETED
                && procedure.getStatus() != Procedure.Status.REJECTED;
        List<Procedure> activeClones = procedure.getParentProcedureId() == null && isActive
                ? procedureRepo.findByParentProcedureId(procedure.getId()).stream()
                        .filter(c -> c.getStatus() != Procedure.Status.COMPLETED
                                && c.getStatus() != Procedure.Status.REJECTED
                                && c.getCurrentStageId() != null)
                        .collect(Collectors.toList())
                : List.of();

        // Collect all active stage IDs: root current + each active clone's current.
        // If the root is parked on a pass-through node while parallel branches are still
        // running, prefer showing the real branch stages as active instead of the logical node.
        Set<String> activeStageIds = new java.util.HashSet<>();
        WorkflowStage rootCurrentStage = isActive && procedure.getCurrentStageId() != null
                ? stageRepo.findById(procedure.getCurrentStageId()).orElse(null)
                : null;
        boolean rootIsWaitingOnPassThrough = rootCurrentStage != null
                && isPassThroughNode(rootCurrentStage)
                && !activeClones.isEmpty();
        if (isActive && procedure.getCurrentStageId() != null && !rootIsWaitingOnPassThrough) {
            activeStageIds.add(procedure.getCurrentStageId());
        }
        activeClones.forEach(c -> activeStageIds.add(c.getCurrentStageId()));

        // Bulk-load all stages needed (history + active stages)
        Set<String> allStageIds = history.stream()
                .filter(h -> h.getToStageId() != null)
                .map(ProcedureHistory::getToStageId)
                .collect(Collectors.toSet());
        allStageIds.addAll(activeStageIds);
        Map<String, WorkflowStage> stageMap = stageRepo.findAllById(allStageIds).stream()
                .collect(Collectors.toMap(WorkflowStage::getId, s -> s));

        Set<String> deptIds = stageMap.values().stream()
                .filter(s -> s.getResponsibleDepartmentId() != null)
                .map(WorkflowStage::getResponsibleDepartmentId)
                .collect(Collectors.toSet());
        Map<String, String> deptNameMap = departmentRepo.findAllById(deptIds).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));

        Set<String> roleIds = stageMap.values().stream()
                .filter(s -> s.getResponsibleJobRoleId() != null)
                .map(WorkflowStage::getResponsibleJobRoleId)
                .collect(Collectors.toSet());
        Map<String, String> roleNameMap = jobRoleRepo.findAllById(roleIds).stream()
                .collect(Collectors.toMap(JobRole::getId, JobRole::getName));

        Set<String> currentHistoryIds = new LinkedHashSet<>();
        Set<String> pendingActiveStageIds = new LinkedHashSet<>(activeStageIds);
        for (int index = history.size() - 1; index >= 0; index--) {
            ProcedureHistory historyEntry = history.get(index);
            String stageId = historyEntry.getToStageId();
            if (stageId != null && pendingActiveStageIds.remove(stageId)) {
                currentHistoryIds.add(historyEntry.getId());
            }
            if (pendingActiveStageIds.isEmpty()) {
                break;
            }
        }

        // Build enriched history entries from root history
        Set<String> coveredStageIds = new java.util.HashSet<>();
        List<Map<String, Object>> enrichedHistory = new ArrayList<>();
        for (ProcedureHistory h : history) {
            WorkflowStage stage = h.getToStageId() != null ? stageMap.get(h.getToStageId()) : null;
            boolean hidePassThroughEntry = stage != null
                    && isPassThroughNode(stage)
                    && ("ADVANCED".equals(h.getAction()) || "JOIN_ADVANCED".equals(h.getAction()));
            if (hidePassThroughEntry) {
                continue;
            }
            boolean isCurrent = currentHistoryIds.contains(h.getId());
            if (h.getToStageId() != null) coveredStageIds.add(h.getToStageId());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", h.getId());
            entry.put("action", h.getAction());
            entry.put("fromStageId", h.getFromStageId());
            entry.put("toStageId", h.getToStageId());
            entry.put("comment", h.getComment());
            entry.put("changedAt", h.getChangedAt());
            entry.put("stageName", stage != null ? stage.getName() : null);
            entry.put("nodeType", stage != null ? stage.getNodeType() : null);
            entry.put("departmentName", stage != null && stage.getResponsibleDepartmentId() != null
                    ? deptNameMap.get(stage.getResponsibleDepartmentId()) : null);
            entry.put("jobRoleName", stage != null && stage.getResponsibleJobRoleId() != null
                    ? roleNameMap.get(stage.getResponsibleJobRoleId()) : null);
            entry.put("isCurrent", isCurrent);
            enrichedHistory.add(entry);
        }

        // Add synthetic entries for active clone-branch stages not already in root history
        for (Procedure clone : activeClones) {
            String cloneStageId = clone.getCurrentStageId();
            if (coveredStageIds.contains(cloneStageId)) continue;
            WorkflowStage stage = stageMap.get(cloneStageId);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", "branch-" + clone.getId());
            entry.put("action", "ADVANCED");
            entry.put("fromStageId", null);
            entry.put("toStageId", cloneStageId);
            entry.put("comment", "Rama paralela en curso");
            entry.put("changedAt", clone.getUpdatedAt() != null ? clone.getUpdatedAt() : clone.getCreatedAt());
            entry.put("stageName", stage != null ? stage.getName() : null);
            entry.put("nodeType", stage != null ? stage.getNodeType() : null);
            entry.put("departmentName", stage != null && stage.getResponsibleDepartmentId() != null
                    ? deptNameMap.get(stage.getResponsibleDepartmentId()) : null);
            entry.put("jobRoleName", stage != null && stage.getResponsibleJobRoleId() != null
                    ? roleNameMap.get(stage.getResponsibleJobRoleId()) : null);
            entry.put("isCurrent", true);
            enrichedHistory.add(entry);
            coveredStageIds.add(cloneStageId);
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", procedure.getId());
        map.put("code", procedure.getCode());
        map.put("title", procedure.getTitle());
        map.put("description", procedure.getDescription());
        map.put("status", procedure.getStatus());
        map.put("workflowId", procedure.getWorkflowId());
        map.put("currentStageId", procedure.getCurrentStageId());
        map.put("requestedById", procedure.getRequestedById());
        map.put("assignedUserId", procedure.getAssignedUserId());
        map.put("formData", procedure.getFormData());
        map.put("createdAt", procedure.getCreatedAt());
        map.put("updatedAt", procedure.getUpdatedAt());
        map.put("history", enrichedHistory);
        return map;
    }

    public List<Map<String, Object>> listActivities(User actor) {
        String userJobRoleId = resolveUserJobRoleId(actor);
        List<Procedure> procedures = procedureRepo.findAll();

        Set<String> workflowIds = procedures.stream().map(Procedure::getWorkflowId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, Workflow> workflowMap = workflowRepo.findAllById(workflowIds).stream()
                .collect(Collectors.toMap(Workflow::getId, w -> w));

        Set<String> stageIds = procedures.stream().map(Procedure::getCurrentStageId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, WorkflowStage> stageMap = stageRepo.findAllById(stageIds).stream()
                .collect(Collectors.toMap(WorkflowStage::getId, s -> s));

        return procedures.stream()
                .map(p -> toActivitySummary(p, actor, userJobRoleId, workflowMap, stageMap))
                .filter(Objects::nonNull)
                .toList();
    }

    public Map<String, Object> findActivity(String id, User actor) {
        Procedure procedure = procedureRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Actividad no encontrada"));
        String userJobRoleId = resolveUserJobRoleId(actor);
        Map<String, Object> detail = toActivityDetail(procedure, actor, userJobRoleId);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a esta actividad");
        }
        return detail;
    }

    public Procedure create(Map<String, Object> body, String requestedById) {
        return createInternal(body, requestedById, true);
    }

    public Map<String, Object> createAndSubmit(Map<String, Object> body, String requestedById) {
        Procedure created = createInternal(body, requestedById, false);
        @SuppressWarnings("unchecked")
        List<String> autoTransitionIds = (List<String>) body.getOrDefault("autoTransitionIds", List.of());

        if (autoTransitionIds == null || autoTransitionIds.isEmpty()) {
            reportRealtimeService.scheduleDashboardUpdate();
            return findOne(created.getId());
        }

        Map<String, Object> latest = null;
        for (String transitionId : autoTransitionIds) {
            if (transitionId == null || transitionId.isBlank()) continue;
            Map<String, Object> advanceBody = new LinkedHashMap<>();
            advanceBody.put("transitionId", transitionId);
            advanceBody.put("comment", body.getOrDefault("comment", "Envio automatico del tramite"));
            if (Objects.equals(transitionId, autoTransitionIds.get(autoTransitionIds.size() - 1))) {
                advanceBody.put("formData", body.get("formData"));
            }
            latest = advanceInternal(created.getId(), advanceBody, requestedById, false);
        }

        reportRealtimeService.scheduleDashboardUpdate();
        return latest != null ? latest : findOne(created.getId());
    }

    private Procedure createInternal(Map<String, Object> body, String requestedById, boolean publishReports) {
        Workflow workflow = workflowRepo.findById((String) body.get("workflowId"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Workflow no encontrado"));

        List<WorkflowStage> stages = stageRepo.findByWorkflowIdOrderByOrderAsc(workflow.getId());
        if (stages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El workflow no tiene etapas");
        }

        Procedure procedure = new Procedure();
        procedure.setCode(generateCode());
        procedure.setTitle((String) body.get("title"));
        procedure.setDescription((String) body.get("description"));
        procedure.setWorkflowId(workflow.getId());
        procedure.setCurrentStageId(stages.get(0).getId());
        procedure.setRequestedById(requestedById);
        procedure.setStatus(Procedure.Status.PENDING);

        @SuppressWarnings("unchecked")
        Map<String, Object> formData = (Map<String, Object>) body.getOrDefault("formData", new LinkedHashMap<>());
        procedure.setFormData(new LinkedHashMap<>(formData));

        Procedure saved = procedureRepo.save(procedure);
        recordHistory(saved.getId(), null, stages.get(0).getId(), "CREATED", requestedById, "Tramite creado");
        if (publishReports) {
            reportRealtimeService.scheduleDashboardUpdate();
        }
        return saved;
    }

    public Map<String, Object> advance(String id, Map<String, Object> body, String userId) {
        return advanceInternal(id, body, userId, true);
    }

    private Map<String, Object> advanceInternal(String id, Map<String, Object> body, String userId, boolean publishReports) {
        Procedure procedure = procedureRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));

        String transitionId = (String) body.get("transitionId");
        String[] transitionPath = transitionId == null ? new String[0] : transitionId.split(">>");
        WorkflowTransition transition = transitionRepo.findById(transitionPath.length > 0 ? transitionPath[0] : transitionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Transicion no encontrada"));
        List<WorkflowTransition> workflowTransitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(procedure.getWorkflowId());

        if (!transition.getFromStageId().equals(procedure.getCurrentStageId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Transicion invalida para la etapa actual");
        }

        String previousStageId = procedure.getCurrentStageId();
        WorkflowTransition finalTransition = transition;
        WorkflowStage passThroughStage = stageRepo.findById(transition.getToStageId()).orElse(null);
        WorkflowStage toStage = passThroughStage;
        String forkPassthroughId = null;
        String transitionHistoryAction = "ADVANCED";
        int transitionPathIndex = 1;
        while (toStage != null) {
            if (hasNodeType(toStage, "decision", "loop")) {
                if (transitionPath.length <= transitionPathIndex) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debes elegir una rama de la decision");
                }
                passThroughStage = toStage;
                finalTransition = transitionRepo.findById(transitionPath[transitionPathIndex])
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rama de decision no encontrada"));
                transitionPathIndex++;
                if (!passThroughStage.getId().equals(finalTransition.getFromStageId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rama de decision invalida");
                }
                if (hasNodeType(passThroughStage, "loop")) {
                    transitionHistoryAction = resolveLoopHistoryAction(finalTransition);
                } else if (hasNodeType(passThroughStage, "decision")
                        && "reject".equals(resolveBranchOutcome(passThroughStage, finalTransition))) {
                    transitionHistoryAction = "DECISION_REJECTED";
                }
                toStage = stageRepo.findById(finalTransition.getToStageId()).orElse(null);
                continue;
            }
            if (hasNodeType(toStage, "fork")) {
                // Auto-advance through fork: el trámite no se detiene en el nodo fork,
                // va directo a la primera rama y handleForkSplitIfNeeded crea clones para las demás.
                forkPassthroughId = toStage.getId();
                final String forkId = toStage.getId();
                WorkflowTransition firstBranch = workflowTransitions.stream()
                    .filter(t -> forkId.equals(t.getFromStageId()))
                    .findFirst().orElse(null);
                if (firstBranch == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La bifurcacion no tiene ramas configuradas");
                }
                finalTransition = firstBranch;
                toStage = stageRepo.findById(firstBranch.getToStageId()).orElse(null);
                continue;
            }
            break;
        }

        procedure.setCurrentStageId(finalTransition.getToStageId());
        boolean isFinal = toStage != null && "END".equalsIgnoreCase(toStage.getNodeType());
        Procedure.Status newStatus = isFinal ? Procedure.Status.COMPLETED : Procedure.Status.IN_PROGRESS;
        procedure.setStatus(newStatus);

        @SuppressWarnings("unchecked")
        Map<String, Object> formData = (Map<String, Object>) body.get("formData");
        if (formData != null) {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (procedure.getFormData() != null) {
                merged.putAll(procedure.getFormData());
            }
            merged.putAll(formData);
            procedure.setFormData(merged);
        }

        String comment = (String) body.getOrDefault("comment", "");
        Procedure saved = procedureRepo.save(procedure);
        recordHistory(
                saved.getId(),
                passThroughStage != null && isPassThroughNode(passThroughStage) ? passThroughStage.getId() : previousStageId,
                finalTransition.getToStageId(),
                transitionHistoryAction,
                userId,
                comment
        );
        if (isFinal) {
            sendStatusNotification(saved, "Trámite completado",
                    "Tu trámite " + saved.getCode() + " ha sido completado exitosamente.");
        }

        // Si el siguiente nodo era un FORK (auto-advance) o si veníamos de un FORK, crear ramas paralelas
        String fromStageForFork = forkPassthroughId != null ? forkPassthroughId : previousStageId;
        handleForkSplitIfNeeded(saved, fromStageForFork, finalTransition.getId(), userId);

        // Si llegamos a un nodo JOIN/UNION, sincronizar ramas paralelas
        if (hasNodeType(toStage, "join")) {
            handleJoinSyncIfNeeded(saved, toStage, userId);
        }

        if (publishReports) {
            reportRealtimeService.scheduleDashboardUpdate();
        }
        String responseProcedureId = saved.getId();
        if (saved.getParentProcedureId() != null && !procedureRepo.existsById(saved.getId())) {
            responseProcedureId = saved.getParentProcedureId();
        }
        return findOne(responseProcedureId);
    }

    /**
     * Si el stage anterior era un nodo FORK (bifurcación), crea trámites paralelos
     * para cada transición saliente del fork que no haya sido usada todavía.
     */
    private void handleForkSplitIfNeeded(Procedure procedure, String fromStageId, String usedTransitionId, String userId) {
        WorkflowStage fromStage = stageRepo.findById(fromStageId).orElse(null);
        if (fromStage == null || !hasNodeType(fromStage, "fork")) {
            return;
        }

        List<WorkflowTransition> allTransitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(procedure.getWorkflowId());
        List<WorkflowTransition> otherBranches = allTransitions.stream()
                .filter(t -> fromStageId.equals(t.getFromStageId()) && !usedTransitionId.equals(t.getId()))
                .toList();

        for (WorkflowTransition branch : otherBranches) {
            Procedure clone = new Procedure();
            clone.setCode(generateCode());
            clone.setTitle(procedure.getTitle());
            clone.setDescription(procedure.getDescription());
            clone.setWorkflowId(procedure.getWorkflowId());
            clone.setCurrentStageId(branch.getToStageId());
            clone.setRequestedById(procedure.getRequestedById());
            clone.setAssignedUserId(procedure.getAssignedUserId());
            clone.setStatus(Procedure.Status.IN_PROGRESS);
            // Vincula el clon al trámite raíz para sincronización en el JOIN
            String rootId = procedure.getParentProcedureId() != null ? procedure.getParentProcedureId() : procedure.getId();
            clone.setParentProcedureId(rootId);
            if (procedure.getFormData() != null) {
                clone.setFormData(new LinkedHashMap<>(procedure.getFormData()));
            }

            Procedure savedClone = procedureRepo.save(clone);
            recordHistory(savedClone.getId(), fromStageId, branch.getToStageId(),
                    "FORK_BRANCH", userId, "Rama creada por bifurcacion desde " + fromStage.getName());
            recordHistory(rootId, fromStageId, branch.getToStageId(),
                    "ADVANCED", userId, "Rama paralela en curso");

            WorkflowStage branchStage = stageRepo.findById(branch.getToStageId()).orElse(null);
            if (branchStage != null) {
            }
        }
    }

    public Procedure reject(String id, Map<String, Object> body, String userId) {
        Procedure procedure = procedureRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));
        procedure.setStatus(Procedure.Status.REJECTED);
        Procedure saved = procedureRepo.save(procedure);
        String reason = (String) body.getOrDefault("reason", "Rechazado");
        recordHistory(saved.getId(), procedure.getCurrentStageId(), null, "REJECTED", userId, reason);
        sendStatusNotification(saved, "Trámite rechazado",
                "Tu trámite " + saved.getCode() + " ha sido rechazado.");
        reportRealtimeService.scheduleDashboardUpdate();
        return saved;
    }

    public void remove(String id) {
        Procedure procedure = procedureRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));
        procedureRepo.deleteById(id);
        reportRealtimeService.scheduleDashboardUpdate();
    }

    public List<ProcedureHistory> getHistory(String procedureId) {
        return historyRepo.findByProcedureIdOrderByChangedAtAsc(procedureId);
    }

    private void sendStatusNotification(Procedure procedure, String title, String body) {
        String email = findCorreoEmailFromProcedure(procedure);
        if (email == null || email.isBlank()) return;
        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.getFcmToken() != null && !user.getFcmToken().isBlank()) {
                fcmService.sendNotification(user.getFcmToken(), title, body);
            }
        });
    }

    private String findCorreoEmailFromProcedure(Procedure procedure) {
        List<WorkflowStage> stages = stageRepo.findByWorkflowIdOrderByOrderAsc(procedure.getWorkflowId());

        WorkflowStage startStage = stages.stream()
                .filter(s -> "start".equalsIgnoreCase(s.getNodeType()))
                .findFirst()
                .orElse(stages.isEmpty() ? null : stages.get(0));

        if (startStage == null) return null;

        List<WorkflowTransition> transitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(procedure.getWorkflowId());
        String startId = startStage.getId();
        WorkflowTransition fromStart = transitions.stream()
                .filter(t -> startId.equals(t.getFromStageId()))
                .findFirst().orElse(null);

        if (fromStart == null) return null;

        FormDefinition form = formRepo.findByStageId(fromStart.getToStageId()).orElse(null);
        if (form == null || form.getFields() == null) return null;

        FormDefinition.FormField correoField = form.getFields().stream()
                .filter(f -> FormDefinition.FieldType.CORREO.equals(f.getType()))
                .findFirst().orElse(null);

        if (correoField == null) return null;

        if (procedure.getFormData() == null) return null;
        Object emailValue = procedure.getFormData().get(correoField.getName());
        return emailValue != null ? emailValue.toString() : null;
    }

    private String generateCode() {
        long count = procedureRepo.count() + 1;
        return "TRM" + String.format("%05d", count);
    }

    private void recordHistory(String procedureId, String fromStageId, String toStageId,
                               String action, String changedById, String comment) {
        ProcedureHistory history = new ProcedureHistory();
        history.setProcedureId(procedureId);
        history.setFromStageId(fromStageId);
        history.setToStageId(toStageId);
        history.setAction(action);
        history.setChangedById(changedById);
        history.setComment(comment);
        history.setUserId(changedById);
        history.setObservation(comment);
        historyRepo.save(history);
    }


    private Map<String, Object> toActivitySummary(Procedure procedure, User actor, String userJobRoleId,
                                                   Map<String, Workflow> workflowMap, Map<String, WorkflowStage> stageMap) {
        if (procedure.getStatus() == Procedure.Status.COMPLETED || procedure.getStatus() == Procedure.Status.REJECTED) {
            return null;
        }

        Workflow workflow = workflowMap.get(procedure.getWorkflowId());
        if (workflow == null || !hasWorkflowAccess(actor, workflow)) {
            return null;
        }

        WorkflowStage currentStage = procedure.getCurrentStageId() != null ? stageMap.get(procedure.getCurrentStageId()) : null;
        if (currentStage == null || isPassThroughNode(currentStage) || !matchesStageResponsibility(currentStage, actor, userJobRoleId)) {
            return null;
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", procedure.getId());
        map.put("code", procedure.getCode());
        map.put("title", procedure.getTitle());
        map.put("status", procedure.getStatus());
        map.put("workflowId", workflow.getId());
        map.put("workflowName", workflow.getName());
        map.put("currentStageId", currentStage.getId());
        map.put("currentStageName", currentStage.getName());
        map.put("createdAt", procedure.getCreatedAt());
        map.put("updatedAt", procedure.getUpdatedAt());
        return map;
    }

    private Map<String, Object> toActivityDetail(Procedure procedure, User actor, String userJobRoleId) {
        Workflow workflow = workflowRepo.findById(procedure.getWorkflowId()).orElse(null);
        if (workflow == null || !hasWorkflowAccess(actor, workflow)) {
            return null;
        }

        WorkflowStage currentStage = stageRepo.findById(procedure.getCurrentStageId()).orElse(null);
        if (currentStage == null || isPassThroughNode(currentStage) || !matchesStageResponsibility(currentStage, actor, userJobRoleId)) {
            return null;
        }

        List<ProcedureHistory> history = historyRepo.findByProcedureIdOrderByChangedAtAsc(procedure.getId());
        List<WorkflowTransition> transitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(workflow.getId());
        FormDefinition formDefinition = formRepo.findByStageId(currentStage.getId()).orElse(null);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", procedure.getId());
        map.put("code", procedure.getCode());
        map.put("title", procedure.getTitle());
        map.put("description", procedure.getDescription());
        map.put("status", procedure.getStatus());
        map.put("workflowId", workflow.getId());
        map.put("workflowName", workflow.getName());
        map.put("currentStageId", currentStage.getId());
        map.put("currentStageName", currentStage.getName());
        map.put("formData", procedure.getFormData());
        map.put("createdAt", procedure.getCreatedAt());
        map.put("updatedAt", procedure.getUpdatedAt());
        map.put("history", history);
        map.put("formDefinition", formDefinition);
        map.put("availableTransitions", buildAvailableTransitions(currentStage, transitions));
        map.put("incomingData", buildIncomingData(procedure, currentStage, transitions));
        return map;
    }

    private List<Map<String, Object>> buildAvailableTransitions(WorkflowStage currentStage,
                                                                List<WorkflowTransition> transitions) {
        List<Map<String, Object>> available = new ArrayList<>();

        for (WorkflowTransition transition : transitions) {
            if (!currentStage.getId().equals(transition.getFromStageId())) {
                continue;
            }

            WorkflowStage directTarget = stageRepo.findById(transition.getToStageId()).orElse(null);
            if (hasNodeType(directTarget, "decision", "loop")) {
                for (WorkflowTransition branch : transitions) {
                    if (!directTarget.getId().equals(branch.getFromStageId())) {
                        continue;
                    }
                    WorkflowStage finalTarget = stageRepo.findById(branch.getToStageId()).orElse(null);
                    Map<String, Object> option = new LinkedHashMap<>();
                    option.put("id", transition.getId() + ">>" + branch.getId());
                    option.put("name", branch.getName());
                    option.put("label", branch.getName());
                    option.put("fromStageId", transition.getFromStageId());
                    option.put("toStageId", branch.getToStageId());
                    option.put("decisionStageId", directTarget.getId());
                    option.put("decisionStageName", directTarget.getName());
                    option.put("decisionNodeType", directTarget.getNodeType());
                    option.put("branchOutcome", resolveBranchOutcome(directTarget, branch));
                    option.put("targetStageName", finalTarget != null ? finalTarget.getName() : branch.getToStageId());
                    option.put("kind", "decision-branch");
                    available.add(option);
                }
                continue;
            }

            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", transition.getId());
            option.put("name", transition.getName());
            option.put("label", transition.getName());
            option.put("fromStageId", transition.getFromStageId());
            option.put("toStageId", transition.getToStageId());
            option.put("targetStageName", directTarget != null ? directTarget.getName() : transition.getToStageId());
            option.put("kind", "transition");
            available.add(option);
        }

        return available;
    }

    private List<Map<String, Object>> buildIncomingData(Procedure procedure, WorkflowStage currentStage,
                                                        List<WorkflowTransition> transitions) {
        Map<String, Object> procedureData = procedure.getFormData() == null ? Map.of() : procedure.getFormData();
        List<Map<String, Object>> incomingData = new ArrayList<>();

        for (WorkflowTransition transition : transitions) {
            if (!currentStage.getId().equals(transition.getToStageId())) {
                continue;
            }

            WorkflowStage sourceStage = stageRepo.findById(transition.getFromStageId()).orElse(null);
            if (sourceStage == null) {
                continue;
            }

            List<Map<String, Object>> fields = buildSharedFields(sourceStage, transition, procedureData, transitions, new LinkedHashSet<>());
            if (fields.isEmpty()) {
                continue;
            }

            Map<String, Object> incoming = new LinkedHashMap<>();
            incoming.put("transitionId", transition.getId());
            incoming.put("transitionName", transition.getName());
            incoming.put("fromStageName", sourceStage.getName());
            incoming.put("fields", fields);
            incomingData.add(incoming);
        }

        return incomingData;
    }

    private List<Map<String, Object>> buildSharedFields(WorkflowStage sourceStage, WorkflowTransition transition,
                                                        Map<String, Object> procedureData,
                                                        List<WorkflowTransition> transitions,
                                                        Set<String> visitedStageIds) {
        List<FormDefinition.FormField> sourceFields = getForwardableFields(sourceStage, transitions, visitedStageIds);
        Map<String, Object> forwardConfig = transition.getForwardConfig();
        String mode = resolveForwardMode(forwardConfig);
        boolean includeFiles = includeForwardFiles(forwardConfig);
        Set<String> selectedFieldNames = resolveSelectedFields(forwardConfig);

        return sourceFields.stream()
                .filter(field -> shouldIncludeField(field, mode, selectedFieldNames, includeFiles))
                .map(field -> {
                    Object value = procedureData.get(field.getName());
                    if (value == null || String.valueOf(value).isBlank()) {
                        return null;
                    }
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("label", field.getName());
                    map.put("name", field.getName());
                    map.put("type", field.getType());
                    map.put("value", value);
                    return map;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private List<FormDefinition.FormField> getForwardableFields(WorkflowStage stage,
                                                                List<WorkflowTransition> transitions,
                                                                Set<String> visitedStageIds) {
        if (stage == null || stage.getId() == null) {
            return List.of();
        }
        if (!visitedStageIds.add(stage.getId())) {
            return List.of();
        }

        if (!isPassThroughNode(stage)) {
            FormDefinition form = formRepo.findByStageId(stage.getId()).orElse(null);
            if (form == null || form.getFields() == null) {
                return List.of();
            }
            return dedupeFields(form.getFields());
        }

        List<FormDefinition.FormField> aggregated = new ArrayList<>();
        for (WorkflowTransition incoming : transitions) {
            if (!stage.getId().equals(incoming.getToStageId())) {
                continue;
            }
            WorkflowStage upstreamStage = stageRepo.findById(incoming.getFromStageId()).orElse(null);
            if (upstreamStage == null) {
                continue;
            }
            aggregated.addAll(buildForwardedFieldDefinitions(upstreamStage, incoming, transitions, new LinkedHashSet<>(visitedStageIds)));
        }
        return dedupeFields(aggregated);
    }

    private List<FormDefinition.FormField> buildForwardedFieldDefinitions(WorkflowStage sourceStage,
                                                                          WorkflowTransition transition,
                                                                          List<WorkflowTransition> transitions,
                                                                          Set<String> visitedStageIds) {
        List<FormDefinition.FormField> sourceFields = getForwardableFields(sourceStage, transitions, visitedStageIds);
        Map<String, Object> forwardConfig = transition.getForwardConfig();
        String mode = resolveForwardMode(forwardConfig);
        boolean includeFiles = includeForwardFiles(forwardConfig);
        Set<String> selectedFieldNames = resolveSelectedFields(forwardConfig);

        return sourceFields.stream()
                .filter(field -> shouldIncludeField(field, mode, selectedFieldNames, includeFiles))
                .toList();
    }

    private String resolveForwardMode(Map<String, Object> forwardConfig) {
        return forwardConfig != null && forwardConfig.get("mode") != null
                ? String.valueOf(forwardConfig.get("mode"))
                : "all";
    }

    private boolean includeForwardFiles(Map<String, Object> forwardConfig) {
        return forwardConfig != null && Boolean.TRUE.equals(forwardConfig.get("includeFiles"));
    }

    private Set<String> resolveSelectedFields(Map<String, Object> forwardConfig) {
        Set<String> selected = new LinkedHashSet<>();
        if (forwardConfig != null && forwardConfig.get("fieldNames") instanceof List<?> fieldNames) {
            fieldNames.stream().map(String::valueOf).forEach(selected::add);
        }
        return selected;
    }

    private List<FormDefinition.FormField> dedupeFields(List<FormDefinition.FormField> fields) {
        Map<String, FormDefinition.FormField> deduped = new LinkedHashMap<>();
        for (FormDefinition.FormField field : fields) {
            if (field == null || field.getName() == null || field.getName().isBlank()) {
                continue;
            }
            deduped.putIfAbsent(field.getName(), field);
        }
        return new ArrayList<>(deduped.values());
    }

    /**
     * Sincroniza ramas paralelas en un nodo JOIN/UNION.
     * Cuando todas las ramas llegan al JOIN, fusiona los formData y avanza el trámite raíz.
     * Los clones se eliminan (soft-delete).
     */
    private void handleJoinSyncIfNeeded(Procedure procedure, WorkflowStage joinStage, String userId) {
        List<WorkflowTransition> allTransitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(procedure.getWorkflowId());
        long expectedBranches = allTransitions.stream()
                .filter(t -> joinStage.getId().equals(t.getToStageId()))
                .count();

        // Identificar el trámite raíz y todos sus clones
        String rootId = procedure.getParentProcedureId() != null ? procedure.getParentProcedureId() : procedure.getId();
        Procedure root = procedureRepo.findById(rootId).orElse(null);
        if (root == null) return;

        List<Procedure> clones = procedureRepo.findByParentProcedureId(rootId);

        // Contar cuántos de la familia están en el JOIN ahora mismo
        long arrivedCount = 0;
        if (joinStage.getId().equals(root.getCurrentStageId())) arrivedCount++;
        arrivedCount += clones.stream()
                .filter(c -> joinStage.getId().equals(c.getCurrentStageId()))
                .count();

        if (arrivedCount < expectedBranches) {
            return; // Faltan ramas — esperar
        }

        // Todas las ramas llegaron: fusionar formData en el raíz
        Map<String, Object> merged = new LinkedHashMap<>();
        if (root.getFormData() != null) merged.putAll(root.getFormData());
        for (Procedure clone : clones) {
            if (clone.getFormData() != null) merged.putAll(clone.getFormData());
        }
        root.setFormData(merged);

        // Avanzar el raíz al siguiente nodo después del JOIN
        WorkflowTransition nextTransition = allTransitions.stream()
                .filter(t -> joinStage.getId().equals(t.getFromStageId()))
                .findFirst().orElse(null);

        if (nextTransition != null) {
            WorkflowStage nextStage = stageRepo.findById(nextTransition.getToStageId()).orElse(null);
            boolean isFinal = nextStage != null && "END".equalsIgnoreCase(nextStage.getNodeType());
            root.setCurrentStageId(nextTransition.getToStageId());
            root.setStatus(isFinal ? Procedure.Status.COMPLETED : Procedure.Status.IN_PROGRESS);
            procedureRepo.save(root);
            recordHistory(root.getId(), joinStage.getId(), nextTransition.getToStageId(), "JOIN_ADVANCED", userId, "Todas las ramas completadas");
            if (!isFinal && nextStage != null) {
            }
        }

        for (Procedure clone : clones) {
            procedureRepo.deleteById(clone.getId());
        }
    }

    private boolean isPassThroughNode(WorkflowStage stage) {
        return hasNodeType(stage, "decision", "fork", "join", "loop");
    }

    private boolean hasNodeType(WorkflowStage stage, String... nodeTypes) {
        if (stage == null || stage.getNodeType() == null) {
            return false;
        }
        String value = stage.getNodeType().toLowerCase();
        for (String nodeType : nodeTypes) {
            if (value.equals(nodeType)) {
                return true;
            }
        }
        return false;
    }

    private String resolveLoopHistoryAction(WorkflowTransition transition) {
        String branchName = transition.getName() == null ? "" : transition.getName().trim().toLowerCase();
        if (branchName.equals("repetir")) {
            return "LOOP_REJECTED";
        }
        if (branchName.equals("salir")) {
            return "LOOP_APPROVED";
        }
        return "LOOP_EVALUATED";
    }

    private String resolveBranchOutcome(WorkflowStage decisionStage, WorkflowTransition branch) {
        if (decisionStage == null || branch == null) {
            return null;
        }
        String branchName = branch.getName() == null ? "" : branch.getName().trim().toLowerCase();
        if (hasNodeType(decisionStage, "loop")) {
            if (branchName.equals("repetir")) {
                return "reject";
            }
            if (branchName.equals("salir")) {
                return "accept";
            }
        } else if (hasNodeType(decisionStage, "decision")) {
            if (branchName.equals("si") || branchName.equals("sí") || branchName.equals("aprobado")
                    || branchName.equals("aceptado")) {
                return "accept";
            }
            if (branchName.equals("no") || branchName.equals("rechazado") || branchName.equals("rechazar")) {
                return "reject";
            }
        }
        return null;
    }

    private boolean shouldIncludeField(FormDefinition.FormField field, String mode, Set<String> selectedFieldNames,
                                       boolean includeFiles) {
        if ("none".equalsIgnoreCase(mode)) {
            return false;
        }
        boolean isFile = field.getType() == FormDefinition.FieldType.FILE;

        if ("selected".equalsIgnoreCase(mode)) {
            return selectedFieldNames.contains(field.getName()) || (includeFiles && isFile);
        }
        if ("files".equalsIgnoreCase(mode) || "files-only".equalsIgnoreCase(mode)) {
            return isFile && includeFiles;
        }
        if (isFile) {
            return includeFiles;
        }
        return true;
    }

    private boolean hasWorkflowAccess(User actor, Workflow workflow) {
        if (actor.getRole() == User.Role.SUPERADMIN) {
            return true;
        }
        return actor.getCompanyId() != null && actor.getCompanyId().equals(workflow.getCompanyId());
    }

    private boolean matchesStageResponsibility(WorkflowStage stage, User actor, String userJobRoleId) {
        if (stage.getResponsibleJobRoleId() != null && !stage.getResponsibleJobRoleId().isBlank()) {
            return stage.getResponsibleJobRoleId().equals(userJobRoleId);
        }
        if (stage.getResponsibleDepartmentId() != null && !stage.getResponsibleDepartmentId().isBlank()) {
            return actor.getDepartmentId() != null && actor.getDepartmentId().equals(stage.getResponsibleDepartmentId());
        }
        if (stage.getResponsibleRole() != null) {
            return actor.getRole() == stage.getResponsibleRole();
        }
        return false;
    }

    private String resolveUserJobRoleId(User actor) {
        if (actor.getDepartmentId() == null || actor.getDepartmentId().isBlank()
                || actor.getJobTitle() == null || actor.getJobTitle().isBlank()) {
            return null;
        }
        return jobRoleRepo.findByDepartmentIdOrderByNameAsc(actor.getDepartmentId()).stream()
                .filter(jobRole -> actor.getJobTitle().equalsIgnoreCase(jobRole.getName()))
                .map(JobRole::getId)
                .findFirst()
                .orElse(null);
    }
}
