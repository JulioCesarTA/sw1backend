package com.workflow.service;

import com.workflow.model.Department;
import com.workflow.model.FormDefinition;
import com.workflow.model.JobRole;
import com.workflow.model.Tramite;
import com.workflow.model.HistorialTramite;
import com.workflow.model.User;
import com.workflow.model.Workflow;
import com.workflow.model.WorkflowStage;
import com.workflow.model.WorkflowTransition;
import com.workflow.repository.DepartmentRepository;
import com.workflow.repository.FormDefinitionRepository;
import com.workflow.repository.JobRoleRepository;
import com.workflow.repository.HistorialTramiteRepository;
import com.workflow.repository.TramiteRepository;
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
public class TramiteService {

    private final TramiteRepository tramiteRepo;
    private final HistorialTramiteRepository historyRepo;
    private final WorkflowRepository workflowRepo;
    private final WorkflowStageRepository stageRepo;
    private final WorkflowTransitionRepository transitionRepo;
    private final FormDefinitionRepository formRepo;
    private final JobRoleRepository jobRoleRepo;
    private final DepartmentRepository departmentRepo;
    private final UserRepository userRepository;
    private final FcmService fcmService;
    private final ReportRealtimeService reportRealtimeService;

    public List<Tramite> findAll(User user) {
        if (user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.SUPERADMIN) {
            return tramiteRepo.findAll();
        }
        if (user.getRole() == User.Role.CLIENTE) {
            String email = user.getEmail();
            if (email == null || email.isBlank()) return List.of();
            return tramiteRepo.findAll().stream()
                    .filter(p -> p.getFormData() != null &&
                            p.getFormData().values().stream()
                                    .anyMatch(v -> email.equalsIgnoreCase(v != null ? v.toString() : null)))
                    .toList();
        }
        return tramiteRepo.findByAssignedUserIdOrRequestedById(user.getId(), user.getId());
    }

    public Map<String, Object> findOne(String id) {
        Tramite tramite = tramiteRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));
        List<HistorialTramite> history = historyRepo.findByTramiteIdOrderByChangedAtAsc(tramite.getId());

        boolean isActive = tramite.getStatus() != Tramite.Status.COMPLETADO
                && tramite.getStatus() != Tramite.Status.RECHAZADO;
        List<Tramite> activeClones = tramite.getParentTramiteId() == null && isActive
                ? tramiteRepo.findByParentTramiteId(tramite.getId()).stream()
                        .filter(c -> c.getStatus() != Tramite.Status.COMPLETADO
                                && c.getStatus() != Tramite.Status.RECHAZADO
                                && c.getCurrentStageId() != null)
                        .collect(Collectors.toList())
                : List.of();

        Set<String> activeStageIds = new java.util.HashSet<>();
        WorkflowStage rootCurrentStage = isActive && tramite.getCurrentStageId() != null
                ? stageRepo.findById(tramite.getCurrentStageId()).orElse(null)
                : null;
        boolean rootIsWaitingOnPassThrough = rootCurrentStage != null
                && isPassThroughNode(rootCurrentStage)
                && !activeClones.isEmpty();
        if (isActive && tramite.getCurrentStageId() != null && !rootIsWaitingOnPassThrough) {
            activeStageIds.add(tramite.getCurrentStageId());
        }
        activeClones.forEach(c -> activeStageIds.add(c.getCurrentStageId()));

        Set<String> allStageIds = history.stream()
                .filter(h -> h.getToStageId() != null)
                .map(HistorialTramite::getToStageId)
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
            HistorialTramite historyEntry = history.get(index);
            String stageId = historyEntry.getToStageId();
            if (stageId != null && pendingActiveStageIds.remove(stageId)) {
                currentHistoryIds.add(historyEntry.getId());
            }
            if (pendingActiveStageIds.isEmpty()) break;
        }

        Set<String> coveredStageIds = new java.util.HashSet<>();
        List<Map<String, Object>> enrichedHistory = new ArrayList<>();
        for (HistorialTramite h : history) {
            WorkflowStage stage = h.getToStageId() != null ? stageMap.get(h.getToStageId()) : null;
            if (stage != null && isPassThroughNode(stage)
                    && ("AVANZADO".equals(h.getAction()) || "UNION_COMPLETADA".equals(h.getAction()))) {
                continue;
            }
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
            entry.put("isCurrent", currentHistoryIds.contains(h.getId()));
            enrichedHistory.add(entry);
        }

        for (Tramite clone : activeClones) {
            String cloneStageId = clone.getCurrentStageId();
            if (coveredStageIds.contains(cloneStageId)) continue;
            WorkflowStage stage = stageMap.get(cloneStageId);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", "branch-" + clone.getId());
            entry.put("action", "AVANZADO");
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
        map.put("id", tramite.getId());
        map.put("code", tramite.getCode());
        map.put("title", tramite.getTitle());
        map.put("description", tramite.getDescription());
        map.put("status", tramite.getStatus());
        map.put("workflowId", tramite.getWorkflowId());
        map.put("currentStageId", tramite.getCurrentStageId());
        map.put("requestedById", tramite.getRequestedById());
        map.put("assignedUserId", tramite.getAssignedUserId());
        map.put("formData", tramite.getFormData());
        map.put("createdAt", tramite.getCreatedAt());
        map.put("updatedAt", tramite.getUpdatedAt());
        map.put("history", enrichedHistory);
        return map;
    }

    public List<Map<String, Object>> listActivities(User actor) {
        List<Tramite> tramites = tramiteRepo.findAll();

        Set<String> workflowIds = tramites.stream().map(Tramite::getWorkflowId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, Workflow> workflowMap = workflowRepo.findAllById(workflowIds).stream()
                .collect(Collectors.toMap(Workflow::getId, workflow -> workflow));

        Set<String> stageIds = tramites.stream().map(Tramite::getCurrentStageId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, WorkflowStage> stageMap = stageRepo.findAllById(stageIds).stream()
                .collect(Collectors.toMap(WorkflowStage::getId, stage -> stage));

        return tramites.stream()
                .filter(tramite -> tramite.getStatus() != Tramite.Status.COMPLETADO && tramite.getStatus() != Tramite.Status.RECHAZADO)
                .map(tramite -> Map.entry(tramite, workflowMap.get(tramite.getWorkflowId())))
                .filter(entry -> entry.getValue() != null && hasWorkflowAccess(actor, entry.getValue()))
                .map(entry -> Map.entry(entry.getKey(), stageMap.get(entry.getKey().getCurrentStageId())))
                .filter(entry -> entry.getValue() != null
                        && !isPassThroughNode(entry.getValue())
                        && matchesStageResponsibility(entry.getValue(), actor))
                .map(entry -> {
                    Tramite tramite = entry.getKey();
                    WorkflowStage stage = entry.getValue();
                    Workflow workflow = workflowMap.get(tramite.getWorkflowId());
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", tramite.getId());
                    map.put("code", tramite.getCode());
                    map.put("title", tramite.getTitle());
                    map.put("status", tramite.getStatus());
                    map.put("workflowId", workflow.getId());
                    map.put("workflowName", workflow.getName());
                    map.put("currentStageId", stage.getId());
                    map.put("currentStageName", stage.getName());
                    map.put("createdAt", tramite.getCreatedAt());
                    map.put("updatedAt", tramite.getUpdatedAt());
                    return map;
                })
                .toList();
    }

    public Map<String, Object> findActivity(String id, User actor) {
        Tramite tramite = tramiteRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Actividad no encontrada"));
        Workflow workflow = workflowRepo.findById(tramite.getWorkflowId()).orElse(null);
        if (workflow == null || !hasWorkflowAccess(actor, workflow)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a esta actividad");
        }

        WorkflowStage currentStage = stageRepo.findById(tramite.getCurrentStageId()).orElse(null);
        if (currentStage == null || isPassThroughNode(currentStage) || !matchesStageResponsibility(currentStage, actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a esta actividad");
        }

        List<HistorialTramite> history = historyRepo.findByTramiteIdOrderByChangedAtAsc(tramite.getId());
        List<WorkflowTransition> transitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(workflow.getId());
        FormDefinition formDefinition = formRepo.findByStageId(currentStage.getId()).orElse(null);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", tramite.getId());
        map.put("code", tramite.getCode());
        map.put("title", tramite.getTitle());
        map.put("description", tramite.getDescription());
        map.put("status", tramite.getStatus());
        map.put("workflowId", workflow.getId());
        map.put("workflowName", workflow.getName());
        map.put("currentStageId", currentStage.getId());
        map.put("currentStageName", currentStage.getName());
        map.put("formData", tramite.getFormData());
        map.put("createdAt", tramite.getCreatedAt());
        map.put("updatedAt", tramite.getUpdatedAt());
        map.put("history", history);
        map.put("formDefinition", formDefinition);
        map.put("availableTransitions", buildAvailableTransitions(currentStage, transitions));
        map.put("incomingData", buildIncomingData(tramite, currentStage, transitions));
        return map;
    }

    public Map<String, Object> createAndSubmit(Map<String, Object> body, String requestedById) {
        Tramite created = createInternal(body, requestedById);
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

    private Tramite createInternal(Map<String, Object> body, String requestedById) {
        Workflow workflow = workflowRepo.findById((String) body.get("workflowId"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Workflow no encontrado"));

        List<WorkflowStage> stages = stageRepo.findByWorkflowIdOrderByOrderAsc(workflow.getId());
        if (stages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El workflow no tiene etapas");
        }
        WorkflowStage initialStage = stages.stream()
                .filter(stage -> "start".equalsIgnoreCase(stage.getNodeType()))
                .findFirst()
                .orElse(stages.get(0));

        Tramite tramite = new Tramite();
        tramite.setCode(generateCode());
        tramite.setTitle((String) body.get("title"));
        tramite.setDescription((String) body.get("description"));
        tramite.setWorkflowId(workflow.getId());
        tramite.setCurrentStageId(initialStage.getId());
        tramite.setRequestedById(requestedById);
        tramite.setStatus(Tramite.Status.PENDIENTE);

        @SuppressWarnings("unchecked")
        Map<String, Object> formData = (Map<String, Object>) body.getOrDefault("formData", new LinkedHashMap<>());
        tramite.setFormData(new LinkedHashMap<>(formData));

        Tramite saved = tramiteRepo.save(tramite);
        recordHistory(saved.getId(), null, initialStage.getId(), "CREADO", requestedById, "Tramite creado");
        return saved;
    }

    public Map<String, Object> advance(String id, Map<String, Object> body, String userId) {
        return advanceInternal(id, body, userId, true);
    }

    private Map<String, Object> advanceInternal(String id, Map<String, Object> body, String userId, boolean publishReports) {
        Tramite tramite = tramiteRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));

        String transitionId = (String) body.get("transitionId");
        String[] transitionPath = transitionId == null ? new String[0] : transitionId.split(">>");
        WorkflowTransition transition = transitionRepo.findById(transitionPath.length > 0 ? transitionPath[0] : transitionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Transicion no encontrada"));
        List<WorkflowTransition> workflowTransitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(tramite.getWorkflowId());

        if (!transition.getFromStageId().equals(tramite.getCurrentStageId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Transicion invalida para la etapa actual");
        }

        String previousStageId = tramite.getCurrentStageId();
        WorkflowTransition finalTransition = transition;
        WorkflowStage passThroughStage = stageRepo.findById(transition.getToStageId()).orElse(null);
        WorkflowStage toStage = passThroughStage;
        String bifurcasionPassthroughId = null;
        String transitionHistoryAction = "AVANZADO";
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
                    transitionHistoryAction = "DECISION_RECHAZADA";
                }
                toStage = stageRepo.findById(finalTransition.getToStageId()).orElse(null);
                continue;
            }
            if (hasNodeType(toStage, "bifurcasion")) {
                bifurcasionPassthroughId = toStage.getId();
                final String bifurcasionId = toStage.getId();
                WorkflowTransition firstBranch = workflowTransitions.stream()
                    .filter(t -> bifurcasionId.equals(t.getFromStageId()))
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

        tramite.setCurrentStageId(finalTransition.getToStageId());
        boolean isFinal = toStage != null && "END".equalsIgnoreCase(toStage.getNodeType());
        tramite.setStatus(isFinal ? Tramite.Status.COMPLETADO : Tramite.Status.EN_PROGRESO);

        @SuppressWarnings("unchecked")
        Map<String, Object> formData = (Map<String, Object>) body.get("formData");
        if (formData != null) {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (tramite.getFormData() != null) merged.putAll(tramite.getFormData());
            merged.putAll(formData);
            tramite.setFormData(merged);
        }

        String comment = (String) body.getOrDefault("comment", "");
        Tramite saved = tramiteRepo.save(tramite);
        String resolvedFromStageId = passThroughStage != null && isPassThroughNode(passThroughStage)
                ? passThroughStage.getId()
                : previousStageId;
        boolean recordsEvaluationAndAdvance = "DECISION_RECHAZADA".equals(transitionHistoryAction)
                || "LOOP_RECHAZADO".equals(transitionHistoryAction)
                || "LOOP_APROBADO".equals(transitionHistoryAction)
                || "LOOP_EVALUADO".equals(transitionHistoryAction);
        if (recordsEvaluationAndAdvance) {
            recordHistory(saved.getId(), previousStageId, previousStageId, transitionHistoryAction, userId, comment);
            recordHistory(saved.getId(), resolvedFromStageId, finalTransition.getToStageId(), "AVANZADO", userId, comment);
        } else {
            recordHistory(
                    saved.getId(),
                    resolvedFromStageId,
                    finalTransition.getToStageId(),
                    transitionHistoryAction,
                    userId,
                    comment
            );
        }
        if (isFinal) {
            sendStatusNotification(saved, "Trámite completado",
                    "Tu trámite " + saved.getCode() + " ha sido completado exitosamente.");
        }

        String fromStageForBifurcasion = bifurcasionPassthroughId != null ? bifurcasionPassthroughId : previousStageId;
        handleBifurcasionSplitIfNeeded(saved, fromStageForBifurcasion, finalTransition.getId(), userId);

        if (hasNodeType(toStage, "join")) {
            handleJoinSyncIfNeeded(saved, toStage, userId);
        }

        if (publishReports) {
            reportRealtimeService.scheduleDashboardUpdate();
        }
        String responseTramiteId = saved.getId();
        if (saved.getParentTramiteId() != null && !tramiteRepo.existsById(saved.getId())) {
            responseTramiteId = saved.getParentTramiteId();
        }
        return findOne(responseTramiteId);
    }

    private void handleBifurcasionSplitIfNeeded(Tramite tramite, String fromStageId, String usedTransitionId, String userId) {
        WorkflowStage fromStage = stageRepo.findById(fromStageId).orElse(null);
        if (fromStage == null || !hasNodeType(fromStage, "bifurcasion")) return;

        List<WorkflowTransition> allTransitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(tramite.getWorkflowId());
        List<WorkflowTransition> otherBranches = allTransitions.stream()
                .filter(t -> fromStageId.equals(t.getFromStageId()) && !usedTransitionId.equals(t.getId()))
                .toList();

        for (WorkflowTransition branch : otherBranches) {
            Tramite clone = new Tramite();
            clone.setCode(generateCode());
            clone.setTitle(tramite.getTitle());
            clone.setDescription(tramite.getDescription());
            clone.setWorkflowId(tramite.getWorkflowId());
            clone.setCurrentStageId(branch.getToStageId());
            clone.setRequestedById(tramite.getRequestedById());
            clone.setAssignedUserId(tramite.getAssignedUserId());
            clone.setStatus(Tramite.Status.EN_PROGRESO);
            String rootId = tramite.getParentTramiteId() != null ? tramite.getParentTramiteId() : tramite.getId();
            clone.setParentTramiteId(rootId);
            if (tramite.getFormData() != null) clone.setFormData(new LinkedHashMap<>(tramite.getFormData()));

            Tramite savedClone = tramiteRepo.save(clone);
            recordHistory(savedClone.getId(), fromStageId, branch.getToStageId(),
                    "BIFURCACION", userId, "Rama creada por bifurcacion desde " + fromStage.getName());
            recordHistory(rootId, fromStageId, branch.getToStageId(), "AVANZADO", userId, "Rama paralela en curso");
        }
    }

    public Tramite reject(String id, Map<String, Object> body, String userId) {
        Tramite tramite = tramiteRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));
        tramite.setStatus(Tramite.Status.RECHAZADO);
        Tramite saved = tramiteRepo.save(tramite);
        String reason = (String) body.getOrDefault("reason", "Rechazado");
        recordHistory(saved.getId(), tramite.getCurrentStageId(), null, "RECHAZADO", userId, reason);
        sendStatusNotification(saved, "Trámite rechazado", "Tu trámite " + saved.getCode() + " ha sido rechazado.");
        reportRealtimeService.scheduleDashboardUpdate();
        return saved;
    }

    private void sendStatusNotification(Tramite tramite, String title, String body) {
        String email = findEmailFromTramite(tramite);
        if (email == null || email.isBlank()) return;
        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.getFcmToken() != null && !user.getFcmToken().isBlank()) {
                fcmService.sendNotification(user.getFcmToken(), title, body);
            }
        });
    }

    private String findEmailFromTramite(Tramite tramite) {
        List<WorkflowStage> stages = stageRepo.findByWorkflowIdOrderByOrderAsc(tramite.getWorkflowId());
        WorkflowStage startStage = stages.stream()
                .filter(s -> "start".equalsIgnoreCase(s.getNodeType()))
                .findFirst()
                .orElse(stages.isEmpty() ? null : stages.get(0));
        if (startStage == null) return null;

        List<WorkflowTransition> transitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(tramite.getWorkflowId());
        WorkflowTransition fromStart = transitions.stream()
                .filter(t -> startStage.getId().equals(t.getFromStageId()))
                .findFirst().orElse(null);
        if (fromStart == null) return null;

        FormDefinition form = formRepo.findByStageId(fromStart.getToStageId()).orElse(null);
        if (form == null || form.getFields() == null) return null;

        FormDefinition.FormField emailField = form.getFields().stream()
                .filter(f -> FormDefinition.FieldType.EMAIL.equals(f.getType()))
                .findFirst().orElse(null);
        if (emailField == null) return null;

        if (tramite.getFormData() == null) return null;
        Object emailValue = tramite.getFormData().get(emailField.getName());
        return emailValue != null ? emailValue.toString() : null;
    }

    private String generateCode() {
        long count = tramiteRepo.count() + 1;
        return "TRM" + String.format("%05d", count);
    }

    private void recordHistory(String tramiteId, String fromStageId, String toStageId,
                               String action, String changedById, String comment) {
        HistorialTramite history = new HistorialTramite();
        history.setTramiteId(tramiteId);
        history.setFromStageId(fromStageId);
        history.setToStageId(toStageId);
        history.setAction(action);
        history.setChangedById(changedById);
        history.setComment(comment);
        historyRepo.save(history);
    }

    private List<Map<String, Object>> buildAvailableTransitions(WorkflowStage currentStage, List<WorkflowTransition> transitions) {
        List<Map<String, Object>> available = new ArrayList<>();
        for (WorkflowTransition transition : transitions) {
            if (!currentStage.getId().equals(transition.getFromStageId())) continue;
            WorkflowStage directTarget = stageRepo.findById(transition.getToStageId()).orElse(null);
            if (hasNodeType(directTarget, "decision", "loop")) {
                for (WorkflowTransition branch : transitions) {
                    if (!directTarget.getId().equals(branch.getFromStageId())) continue;
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

    private List<Map<String, Object>> buildIncomingData(Tramite tramite, WorkflowStage currentStage, List<WorkflowTransition> transitions) {
        Map<String, Object> tramiteData = tramite.getFormData() == null ? Map.of() : tramite.getFormData();
        List<Map<String, Object>> incomingData = new ArrayList<>();
        for (WorkflowTransition transition : transitions) {
            if (!currentStage.getId().equals(transition.getToStageId())) continue;
            WorkflowStage sourceStage = stageRepo.findById(transition.getFromStageId()).orElse(null);
            if (sourceStage == null) continue;
            List<Map<String, Object>> fields = buildSharedFields(sourceStage, transition, tramiteData, transitions, new LinkedHashSet<>());
            if (fields.isEmpty()) continue;
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
                                                        Map<String, Object> tramiteData,
                                                        List<WorkflowTransition> transitions, Set<String> visitedStageIds) {
        List<FormDefinition.FormField> sourceFields = getForwardableFields(sourceStage, transitions, visitedStageIds);
        Map<String, Object> forwardConfig = transition.getForwardConfig();
        String mode = resolveForwardMode(forwardConfig);
        Set<String> selectedFieldNames = resolveSelectedFields(forwardConfig);

        return sourceFields.stream()
                .filter(field -> shouldIncludeField(field, mode, selectedFieldNames))
                .map(field -> {
                    Object value = tramiteData.get(field.getName());
                    if (value == null || String.valueOf(value).isBlank()) return null;
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

    private List<FormDefinition.FormField> getForwardableFields(WorkflowStage stage, List<WorkflowTransition> transitions, Set<String> visitedStageIds) {
        if (stage == null || stage.getId() == null || !visitedStageIds.add(stage.getId())) return List.of();
        if (!isPassThroughNode(stage)) {
            FormDefinition form = formRepo.findByStageId(stage.getId()).orElse(null);
            if (form == null || form.getFields() == null) return List.of();
            return dedupeFields(form.getFields());
        }
        List<FormDefinition.FormField> aggregated = new ArrayList<>();
        for (WorkflowTransition incoming : transitions) {
            if (!stage.getId().equals(incoming.getToStageId())) continue;
            WorkflowStage upstreamStage = stageRepo.findById(incoming.getFromStageId()).orElse(null);
            if (upstreamStage == null) continue;
            aggregated.addAll(buildForwardedFieldDefinitions(upstreamStage, incoming, transitions, new LinkedHashSet<>(visitedStageIds)));
        }
        return dedupeFields(aggregated);
    }

    private List<FormDefinition.FormField> buildForwardedFieldDefinitions(WorkflowStage sourceStage, WorkflowTransition transition,
                                                                          List<WorkflowTransition> transitions, Set<String> visitedStageIds) {
        List<FormDefinition.FormField> sourceFields = getForwardableFields(sourceStage, transitions, visitedStageIds);
        Map<String, Object> forwardConfig = transition.getForwardConfig();
        return sourceFields.stream()
                .filter(field -> shouldIncludeField(field, resolveForwardMode(forwardConfig), resolveSelectedFields(forwardConfig)))
                .toList();
    }

    private String resolveForwardMode(Map<String, Object> forwardConfig) {
        return forwardConfig != null && "selected".equals(String.valueOf(forwardConfig.get("mode"))) ? "selected" : "none";
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
            if (field == null || field.getName() == null || field.getName().isBlank()) continue;
            deduped.putIfAbsent(field.getName(), field);
        }
        return new ArrayList<>(deduped.values());
    }

    private boolean shouldIncludeField(FormDefinition.FormField field, String mode, Set<String> selectedFieldNames) {
        if ("none".equalsIgnoreCase(mode)) return false;
        return "selected".equalsIgnoreCase(mode) && selectedFieldNames.contains(field.getName());
    }

    private boolean hasWorkflowAccess(User actor, Workflow workflow) {
        if (actor.getRole() == User.Role.SUPERADMIN) return true;
        return actor.getCompanyId() != null && actor.getCompanyId().equals(workflow.getCompanyId());
    }

    private boolean matchesStageResponsibility(WorkflowStage stage, User actor) {
        boolean hasJobRole = stage.getResponsibleJobRoleId() != null && !stage.getResponsibleJobRoleId().isBlank();
        boolean hasDepartment = stage.getResponsibleDepartmentId() != null && !stage.getResponsibleDepartmentId().isBlank();
        boolean hasRole = stage.getResponsibleRole() != null;

        if (hasJobRole) {
            boolean matchesJobRole = stage.getResponsibleJobRoleId().equals(actor.getJobRoleId());
            if (!matchesJobRole) return false;
            return !hasDepartment || (actor.getDepartmentId() != null && actor.getDepartmentId().equals(stage.getResponsibleDepartmentId()));
        }
        if (hasDepartment) {
            return actor.getDepartmentId() != null && actor.getDepartmentId().equals(stage.getResponsibleDepartmentId());
        }
        if (hasRole) {
            return actor.getRole() == stage.getResponsibleRole();
        }
        return false;
    }

    private void handleJoinSyncIfNeeded(Tramite tramite, WorkflowStage joinStage, String userId) {
        List<WorkflowTransition> allTransitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(tramite.getWorkflowId());
        long expectedBranches = allTransitions.stream()
                .filter(t -> joinStage.getId().equals(t.getToStageId()))
                .count();

        String rootId = tramite.getParentTramiteId() != null ? tramite.getParentTramiteId() : tramite.getId();
        Tramite root = tramiteRepo.findById(rootId).orElse(null);
        if (root == null) return;

        List<Tramite> clones = tramiteRepo.findByParentTramiteId(rootId);
        long arrivedCount = (joinStage.getId().equals(root.getCurrentStageId()) ? 1 : 0)
                + clones.stream().filter(c -> joinStage.getId().equals(c.getCurrentStageId())).count();

        if (arrivedCount < expectedBranches) return;

        Map<String, Object> merged = new LinkedHashMap<>();
        if (root.getFormData() != null) merged.putAll(root.getFormData());
        for (Tramite clone : clones) {
            if (clone.getFormData() != null) merged.putAll(clone.getFormData());
        }
        root.setFormData(merged);

        WorkflowTransition nextTransition = allTransitions.stream()
                .filter(t -> joinStage.getId().equals(t.getFromStageId()))
                .findFirst().orElse(null);

        if (nextTransition != null) {
            WorkflowStage nextStage = stageRepo.findById(nextTransition.getToStageId()).orElse(null);
            boolean isFinal = nextStage != null && "END".equalsIgnoreCase(nextStage.getNodeType());
            root.setCurrentStageId(nextTransition.getToStageId());
            root.setStatus(isFinal ? Tramite.Status.COMPLETADO : Tramite.Status.EN_PROGRESO);
            tramiteRepo.save(root);
            recordHistory(root.getId(), joinStage.getId(), nextTransition.getToStageId(), "UNION_COMPLETADA", userId, "Todas las ramas completadas");
        }

        clones.forEach(clone -> tramiteRepo.deleteById(clone.getId()));
    }

    private boolean isPassThroughNode(WorkflowStage stage) {
        return hasNodeType(stage, "decision", "bifurcasion", "join", "loop");
    }

    private boolean hasNodeType(WorkflowStage stage, String... nodeTypes) {
        if (stage == null || stage.getNodeType() == null) return false;
        String value = stage.getNodeType().toLowerCase();
        for (String nodeType : nodeTypes) {
            if (value.equals(nodeType)) return true;
        }
        return false;
    }

    private String resolveLoopHistoryAction(WorkflowTransition transition) {
        String name = transition.getName() == null ? "" : transition.getName().trim().toLowerCase();
        if (name.equals("repetir")) return "LOOP_RECHAZADO";
        if (name.equals("salir")) return "LOOP_APROBADO";
        return "LOOP_EVALUADO";
    }

    private String resolveBranchOutcome(WorkflowStage decisionStage, WorkflowTransition branch) {
        if (decisionStage == null || branch == null) return null;
        String name = branch.getName() == null ? "" : branch.getName().trim().toLowerCase();
        if (hasNodeType(decisionStage, "loop")) {
            if (name.equals("repetir")) return "reject";
            if (name.equals("salir")) return "accept";
        } else if (hasNodeType(decisionStage, "decision")) {
            if (name.equals("si") || name.equals("sí") || name.equals("aprobado") || name.equals("aceptado")) return "accept";
            if (name.equals("no") || name.equals("rechazado") || name.equals("rechazar")) return "reject";
        }
        return null;
    }

}
