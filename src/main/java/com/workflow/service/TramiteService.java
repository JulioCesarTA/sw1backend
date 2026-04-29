package com.workflow.service;

import com.workflow.model.Department;
import com.workflow.model.FormDefinition;
import com.workflow.model.JobRole;
import com.workflow.model.Tramite;
import com.workflow.model.HistorialTramite;
import com.workflow.model.User;
import com.workflow.model.Workflow;
import com.workflow.model.WorkflowNodo;
import com.workflow.model.WorkflowTransition;
import com.workflow.repository.DepartmentRepository;
import com.workflow.repository.FormDefinitionRepository;
import com.workflow.repository.JobRoleRepository;
import com.workflow.repository.HistorialTramiteRepository;
import com.workflow.repository.TramiteRepository;
import com.workflow.repository.UserRepository;
import com.workflow.repository.WorkflowRepository;
import com.workflow.repository.WorkflowNodoRepository;
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
    private final WorkflowNodoRepository nodoRepo;
    private final WorkflowTransitionRepository transitionRepo;
    private final FormDefinitionRepository formRepo;
    private final JobRoleRepository jobRoleRepo;
    private final DepartmentRepository departmentRepo;
    private final UserRepository userRepository;
    private final WorkflowAiProxyService workflowAiProxyService;
    private final FcmService fcmService;
    private final ReportRealtimeService reportRealtimeService;
    private final List<NodoTipoHandler> nodoTipoHandlers = List.of(
            new NodoDecisionHandler(),
            new NodoIteracionHandler(),
            new NodoBifurcasionHandler(),
            new NodoUnionHandler()
    );

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
                                && c.getCurrentNodoId() != null)
                        .collect(Collectors.toList())
                : List.of();

        Set<String> activeNodoIds = new java.util.HashSet<>();
        WorkflowNodo rootCurrentNodo = isActive && tramite.getCurrentNodoId() != null
                ? nodoRepo.findById(tramite.getCurrentNodoId()).orElse(null)
                : null;
        boolean rootIsWaitingOnPassThrough = rootCurrentNodo != null
                && isPassThroughNode(rootCurrentNodo)
                && !activeClones.isEmpty();
        if (isActive && tramite.getCurrentNodoId() != null && !rootIsWaitingOnPassThrough) {
            activeNodoIds.add(tramite.getCurrentNodoId());
        }
        activeClones.forEach(c -> activeNodoIds.add(c.getCurrentNodoId()));

        Set<String> allNodoIds = history.stream()
                .filter(h -> h.getToNodoId() != null)
                .map(HistorialTramite::getToNodoId)
                .collect(Collectors.toSet());
        allNodoIds.addAll(activeNodoIds);
        Map<String, WorkflowNodo> nodoMap = nodoRepo.findAllById(allNodoIds).stream()
                .collect(Collectors.toMap(WorkflowNodo::getId, s -> s));

        Set<String> deptIds = nodoMap.values().stream()
                .filter(s -> s.getResponsibleDepartmentId() != null)
                .map(WorkflowNodo::getResponsibleDepartmentId)
                .collect(Collectors.toSet());
        Map<String, String> deptNameMap = departmentRepo.findAllById(deptIds).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));

        Set<String> roleIds = nodoMap.values().stream()
                .filter(s -> s.getResponsibleJobRoleId() != null)
                .map(WorkflowNodo::getResponsibleJobRoleId)
                .collect(Collectors.toSet());
        Map<String, String> roleNameMap = jobRoleRepo.findAllById(roleIds).stream()
                .collect(Collectors.toMap(JobRole::getId, JobRole::getName));

        Set<String> currentHistoryIds = new LinkedHashSet<>();
        Set<String> pendingActiveNodoIds = new LinkedHashSet<>(activeNodoIds);
        for (int index = history.size() - 1; index >= 0; index--) {
            HistorialTramite historyEntry = history.get(index);
            String nodoId = historyEntry.getToNodoId();
            if (nodoId != null && pendingActiveNodoIds.remove(nodoId)) {
                currentHistoryIds.add(historyEntry.getId());
            }
            if (pendingActiveNodoIds.isEmpty()) break;
        }

        Set<String> coveredNodoIds = new java.util.HashSet<>();
        List<Map<String, Object>> enrichedHistory = new ArrayList<>();
        for (HistorialTramite h : history) {
            WorkflowNodo nodo = h.getToNodoId() != null ? nodoMap.get(h.getToNodoId()) : null;
            if (nodo != null && isPassThroughNode(nodo)
                    && ("AVANZADO".equals(h.getAction()) || "UNION_COMPLETADA".equals(h.getAction()))) {
                continue;
            }
            if (h.getToNodoId() != null) coveredNodoIds.add(h.getToNodoId());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", h.getId());
            entry.put("action", h.getAction());
            entry.put("fromNodoId", h.getFromNodoId());
            entry.put("toNodoId", h.getToNodoId());
            entry.put("comment", h.getComment());
            entry.put("changedAt", h.getChangedAt());
            entry.put("nodoName", nodo != null ? nodo.getName() : null);
            entry.put("nodeType", nodo != null ? nodo.getNodeType() : null);
            entry.put("departmentName", nodo != null && nodo.getResponsibleDepartmentId() != null
                    ? deptNameMap.get(nodo.getResponsibleDepartmentId()) : null);
            entry.put("jobRoleName", nodo != null && nodo.getResponsibleJobRoleId() != null
                    ? roleNameMap.get(nodo.getResponsibleJobRoleId()) : null);
            entry.put("isCurrent", currentHistoryIds.contains(h.getId()));
            enrichedHistory.add(entry);
        }

        for (Tramite clone : activeClones) {
            String cloneNodoId = clone.getCurrentNodoId();
            if (coveredNodoIds.contains(cloneNodoId)) continue;
            WorkflowNodo nodo = nodoMap.get(cloneNodoId);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", "branch-" + clone.getId());
            entry.put("action", "AVANZADO");
            entry.put("fromNodoId", null);
            entry.put("toNodoId", cloneNodoId);
            entry.put("comment", "Rama paralela en curso");
            entry.put("changedAt", clone.getUpdatedAt() != null ? clone.getUpdatedAt() : clone.getCreatedAt());
            entry.put("nodoName", nodo != null ? nodo.getName() : null);
            entry.put("nodeType", nodo != null ? nodo.getNodeType() : null);
            entry.put("departmentName", nodo != null && nodo.getResponsibleDepartmentId() != null
                    ? deptNameMap.get(nodo.getResponsibleDepartmentId()) : null);
            entry.put("jobRoleName", nodo != null && nodo.getResponsibleJobRoleId() != null
                    ? roleNameMap.get(nodo.getResponsibleJobRoleId()) : null);
            entry.put("isCurrent", true);
            enrichedHistory.add(entry);
            coveredNodoIds.add(cloneNodoId);
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", tramite.getId());
        map.put("code", tramite.getCode());
        map.put("title", tramite.getTitle());
        map.put("description", tramite.getDescription());
        map.put("status", tramite.getStatus());
        map.put("workflowId", tramite.getWorkflowId());
        map.put("currentNodoId", tramite.getCurrentNodoId());
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

        Set<String> nodoIds = tramites.stream().map(Tramite::getCurrentNodoId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, WorkflowNodo> nodoMap = nodoRepo.findAllById(nodoIds).stream()
                .collect(Collectors.toMap(WorkflowNodo::getId, nodo -> nodo));

        return tramites.stream()
                .filter(tramite -> tramite.getStatus() != Tramite.Status.COMPLETADO && tramite.getStatus() != Tramite.Status.RECHAZADO)
                .map(tramite -> Map.entry(tramite, workflowMap.get(tramite.getWorkflowId())))
                .filter(entry -> entry.getValue() != null && hasWorkflowAccess(actor, entry.getValue()))
                .map(entry -> Map.entry(entry.getKey(), nodoMap.get(entry.getKey().getCurrentNodoId())))
                .filter(entry -> entry.getValue() != null
                        && !isPassThroughNode(entry.getValue())
                        && matchesNodoResponsibility(entry.getValue(), actor))
                .map(entry -> {
                    Tramite tramite = entry.getKey();
                    WorkflowNodo nodo = entry.getValue();
                    Workflow workflow = workflowMap.get(tramite.getWorkflowId());
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", tramite.getId());
                    map.put("code", tramite.getCode());
                    map.put("title", tramite.getTitle());
                    map.put("status", tramite.getStatus());
                    map.put("workflowId", workflow.getId());
                    map.put("workflowName", workflow.getName());
                    map.put("currentNodoId", nodo.getId());
                    map.put("currentNodoName", nodo.getName());
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

        WorkflowNodo currentNodo = nodoRepo.findById(tramite.getCurrentNodoId()).orElse(null);
        if (currentNodo == null || isPassThroughNode(currentNodo) || !matchesNodoResponsibility(currentNodo, actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a esta actividad");
        }

        List<HistorialTramite> history = historyRepo.findByTramiteIdOrderByChangedAtAsc(tramite.getId());
        List<WorkflowTransition> transitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(workflow.getId());
        FormDefinition formDefinition = formRepo.findByNodoId(currentNodo.getId()).orElse(null);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", tramite.getId());
        map.put("code", tramite.getCode());
        map.put("title", tramite.getTitle());
        map.put("description", tramite.getDescription());
        map.put("status", tramite.getStatus());
        map.put("workflowId", workflow.getId());
        map.put("workflowName", workflow.getName());
        map.put("currentNodoId", currentNodo.getId());
        map.put("currentNodoName", currentNodo.getName());
        map.put("formData", tramite.getFormData());
        map.put("createdAt", tramite.getCreatedAt());
        map.put("updatedAt", tramite.getUpdatedAt());
        map.put("history", history);
        map.put("formDefinition", formDefinition);
        map.put("availableTransitions", buildAvailableTransitions(currentNodo, transitions));
        map.put("incomingData", buildIncomingData(tramite, currentNodo, transitions));
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

        List<WorkflowNodo> nodos = nodoRepo.findByWorkflowIdOrderByOrderAsc(workflow.getId());
        if (nodos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El workflow no tiene etapas");
        }
        List<WorkflowTransition> transitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(workflow.getId());
        validateWorkflowStartAndEnd(nodos, transitions);
        WorkflowNodo initialNodo = nodos.stream()
                .filter(nodo -> "inicio".equalsIgnoreCase(nodo.getNodeType()))
                .findFirst()
                .orElse(nodos.get(0));

        Tramite tramite = new Tramite();
        tramite.setCode(generateCode());
        tramite.setTitle((String) body.get("title"));
        tramite.setDescription((String) body.get("description"));
        tramite.setWorkflowId(workflow.getId());
        tramite.setCurrentNodoId(initialNodo.getId());
        tramite.setRequestedById(requestedById);
        tramite.setStatus(Tramite.Status.PENDIENTE);

        @SuppressWarnings("unchecked")
        Map<String, Object> formData = (Map<String, Object>) body.getOrDefault("formData", new LinkedHashMap<>());
        tramite.setFormData(new LinkedHashMap<>(formData));

        Tramite saved = tramiteRepo.save(tramite);
        recordHistory(saved.getId(), null, initialNodo.getId(), "CREADO", requestedById, "Tramite creado");
        return saved;
    }

    private void validateWorkflowStartAndEnd(List<WorkflowNodo> nodos, List<WorkflowTransition> transitions) {
        Map<String, WorkflowNodo> nodoById = nodos.stream().collect(Collectors.toMap(WorkflowNodo::getId, nodo -> nodo));

        boolean hasInicioAProceso = transitions.stream().anyMatch(transition -> {
            WorkflowNodo fromNodo = nodoById.get(transition.getFromNodoId());
            WorkflowNodo toNodo = nodoById.get(transition.getToNodoId());
            return fromNodo != null
                    && toNodo != null
                    && "inicio".equalsIgnoreCase(fromNodo.getNodeType())
                    && "proceso".equalsIgnoreCase(toNodo.getNodeType());
        });

        boolean hasProcesoAFin = transitions.stream().anyMatch(transition -> {
            WorkflowNodo fromNodo = nodoById.get(transition.getFromNodoId());
            WorkflowNodo toNodo = nodoById.get(transition.getToNodoId());
            return fromNodo != null
                    && toNodo != null
                    && "proceso".equalsIgnoreCase(fromNodo.getNodeType())
                    && "fin".equalsIgnoreCase(toNodo.getNodeType());
        });

        if (!hasInicioAProceso || !hasProcesoAFin) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El workflow debe tener un Inicio conectado a un Proceso y un Proceso conectado a un Fin"
            );
        }
    }

    public Map<String, Object> advance(String id, Map<String, Object> body, String userId) {
        return advanceInternal(id, body, userId, true);
    }

    public Map<String, Object> parseVoiceFill(String id, Map<String, Object> body, User actor) {
        Tramite tramite = tramiteRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Actividad no encontrada"));
        Workflow workflow = workflowRepo.findById(tramite.getWorkflowId()).orElse(null);
        if (workflow == null || !hasWorkflowAccess(actor, workflow)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a esta actividad");
        }

        WorkflowNodo currentNodo = nodoRepo.findById(tramite.getCurrentNodoId()).orElse(null);
        if (currentNodo == null || isPassThroughNode(currentNodo) || !matchesNodoResponsibility(currentNodo, actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a esta actividad");
        }

        String transcript = String.valueOf(body.getOrDefault("transcript", "")).trim();
        if (transcript.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debes enviar el texto reconocido");
        }

        FormDefinition formDefinition = formRepo.findByNodoId(currentNodo.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "La actividad actual no tiene formulario"));

        @SuppressWarnings("unchecked")
        Map<String, Object> currentFormData = (Map<String, Object>) body.get("formData");
        Map<String, Object> result = parseVoiceTranscript(
                transcript,
                formDefinition,
                currentFormData != null ? currentFormData : tramite.getFormData()
        );
        result.put("activityId", tramite.getId());
        result.put("currentNodoId", currentNodo.getId());
        return result;
    }

    public Map<String, Object> parseVoiceFillForCreate(Map<String, Object> body, User actor) {
        String workflowId = String.valueOf(body.getOrDefault("workflowId", "")).trim();
        if (workflowId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workflowId es obligatorio");
        }

        Workflow workflow = workflowRepo.findById(workflowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow no encontrado"));
        if (!hasWorkflowAccess(actor, workflow)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a este workflow");
        }

        String transcript = String.valueOf(body.getOrDefault("transcript", "")).trim();
        if (transcript.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debes enviar el texto reconocido");
        }

        List<WorkflowNodo> nodos = nodoRepo.findByWorkflowIdOrderByOrderAsc(workflow.getId());
        List<WorkflowTransition> transitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(workflow.getId());
        WorkflowNodo entryNodo = resolveEntryNodo(nodos, transitions);
        if (entryNodo == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El workflow no tiene una etapa inicial valida");
        }

        FormDefinition formDefinition = formRepo.findByNodoId(entryNodo.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "La etapa inicial no tiene formulario"));

        @SuppressWarnings("unchecked")
        Map<String, Object> currentFormData = (Map<String, Object>) body.getOrDefault("formData", Map.of());
        Map<String, Object> result = parseVoiceTranscript(transcript, formDefinition, currentFormData);
        result.put("workflowId", workflow.getId());
        result.put("currentNodoId", entryNodo.getId());
        return result;
    }

    private Map<String, Object> advanceInternal(String id, Map<String, Object> body, String userId, boolean publishReports) {
        Tramite tramite = tramiteRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));

        String transitionId = (String) body.get("transitionId");
        String[] transitionPath = transitionId == null ? new String[0] : transitionId.split(">>");
        WorkflowTransition transition = transitionRepo.findById(transitionPath.length > 0 ? transitionPath[0] : transitionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Transicion no encontrada"));
        List<WorkflowTransition> workflowTransitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(tramite.getWorkflowId());

        if (!transition.getFromNodoId().equals(tramite.getCurrentNodoId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Transicion invalida para la etapa actual");
        }

        String previousNodoId = tramite.getCurrentNodoId();
        AdvanceCursor advanceCursor = new AdvanceCursor(
                transition,
                nodoRepo.findById(transition.getToNodoId()).orElse(null),
                nodoRepo.findById(transition.getToNodoId()).orElse(null),
                "AVANZADO",
                1
        );
        while (advanceCursor.toNodo != null) {
            NodoTipoHandler handler = findNodoTipoHandler(advanceCursor.toNodo);
            if (!handler.consumeAdvance(advanceCursor, transitionPath, workflowTransitions)) {
                break;
            }
        }

        tramite.setCurrentNodoId(advanceCursor.finalTransition.getToNodoId());
        boolean isFinal = advanceCursor.toNodo != null && "fin".equalsIgnoreCase(advanceCursor.toNodo.getNodeType());
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
        String resolvedFromNodoId = advanceCursor.passThroughNodo != null && isPassThroughNode(advanceCursor.passThroughNodo)
                ? advanceCursor.passThroughNodo.getId()
                : previousNodoId;
        boolean recordsEvaluationAndAdvance = "DECISION_RECHAZADA".equals(advanceCursor.transitionHistoryAction)
                || "LOOP_RECHAZADO".equals(advanceCursor.transitionHistoryAction)
                || "LOOP_APROBADO".equals(advanceCursor.transitionHistoryAction)
                || "LOOP_EVALUADO".equals(advanceCursor.transitionHistoryAction);
        if (recordsEvaluationAndAdvance) {
            recordHistory(saved.getId(), previousNodoId, previousNodoId, advanceCursor.transitionHistoryAction, userId, comment);
            recordHistory(saved.getId(), resolvedFromNodoId, advanceCursor.finalTransition.getToNodoId(), "AVANZADO", userId, comment);
        } else {
            recordHistory(
                    saved.getId(),
                    resolvedFromNodoId,
                    advanceCursor.finalTransition.getToNodoId(),
                    advanceCursor.transitionHistoryAction,
                    userId,
                    comment
            );
        }
        if (isFinal) {
            sendStatusNotification(saved, "Trámite completado",
                    "Tu trámite " + saved.getCode() + " ha sido completado exitosamente.");
        }

        String fromNodoForBifurcasion = advanceCursor.bifurcasionPassthroughId != null ? advanceCursor.bifurcasionPassthroughId : previousNodoId;
        handleBifurcasionSplitIfNeeded(saved, fromNodoForBifurcasion, advanceCursor.finalTransition.getId(), userId);

        if (hasNodeType(advanceCursor.toNodo, "union")) {
            handleJoinSyncIfNeeded(saved, advanceCursor.toNodo, userId);
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

    private void handleBifurcasionSplitIfNeeded(Tramite tramite, String fromNodoId, String usedTransitionId, String userId) {
        WorkflowNodo fromNodo = nodoRepo.findById(fromNodoId).orElse(null);
        if (fromNodo == null || !hasNodeType(fromNodo, "bifurcasion")) return;

        List<WorkflowTransition> allTransitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(tramite.getWorkflowId());
        List<WorkflowTransition> otherBranches = allTransitions.stream()
                .filter(t -> fromNodoId.equals(t.getFromNodoId()) && !usedTransitionId.equals(t.getId()))
                .toList();

        for (WorkflowTransition branch : otherBranches) {
            Tramite clone = new Tramite();
            clone.setCode(generateCode());
            clone.setTitle(tramite.getTitle());
            clone.setDescription(tramite.getDescription());
            clone.setWorkflowId(tramite.getWorkflowId());
            clone.setCurrentNodoId(branch.getToNodoId());
            clone.setRequestedById(tramite.getRequestedById());
            clone.setAssignedUserId(tramite.getAssignedUserId());
            clone.setStatus(Tramite.Status.EN_PROGRESO);
            String rootId = tramite.getParentTramiteId() != null ? tramite.getParentTramiteId() : tramite.getId();
            clone.setParentTramiteId(rootId);
            if (tramite.getFormData() != null) clone.setFormData(new LinkedHashMap<>(tramite.getFormData()));

            Tramite savedClone = tramiteRepo.save(clone);
            recordHistory(savedClone.getId(), fromNodoId, branch.getToNodoId(),
                    "BIFURCACION", userId, "Rama creada por bifurcacion desde " + fromNodo.getName());
            recordHistory(rootId, fromNodoId, branch.getToNodoId(), "AVANZADO", userId, "Rama paralela en curso");
        }
    }

    public Tramite reject(String id, Map<String, Object> body, String userId) {
        Tramite tramite = tramiteRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado"));
        tramite.setStatus(Tramite.Status.RECHAZADO);
        Tramite saved = tramiteRepo.save(tramite);
        String reason = (String) body.getOrDefault("reason", "Rechazado");
        recordHistory(saved.getId(), tramite.getCurrentNodoId(), null, "RECHAZADO", userId, reason);
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
        List<WorkflowNodo> nodo = nodoRepo.findByWorkflowIdOrderByOrderAsc(tramite.getWorkflowId());
        WorkflowNodo nodoInicio = nodo.stream()
                .filter(s -> "inicio".equalsIgnoreCase(s.getNodeType()))
                .findFirst()
                .orElse(nodo.isEmpty() ? null : nodo.get(0));
        if (nodoInicio == null) return null;

        List<WorkflowTransition> transitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(tramite.getWorkflowId());
        WorkflowTransition transicionInicio = transitions.stream()
                .filter(t -> nodoInicio.getId().equals(t.getFromNodoId()))
                .findFirst().orElse(null);
        if (transicionInicio == null) return null;

        FormDefinition form = formRepo.findByNodoId(transicionInicio.getToNodoId()).orElse(null);
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

    private void recordHistory(String tramiteId, String fromNodoId, String toNodoId,
                               String action, String changedById, String comment) {
        HistorialTramite history = new HistorialTramite();
        history.setTramiteId(tramiteId);
        history.setFromNodoId(fromNodoId);
        history.setToNodoId(toNodoId);
        history.setAction(action);
        history.setChangedById(changedById);
        history.setComment(comment);
        historyRepo.save(history);
    }

    private List<Map<String, Object>> buildAvailableTransitions(WorkflowNodo currentNodo, List<WorkflowTransition> transitions) {
        List<Map<String, Object>> available = new ArrayList<>();
        for (WorkflowTransition transition : transitions) {
            if (!currentNodo.getId().equals(transition.getFromNodoId())) continue;
            WorkflowNodo directTarget = nodoRepo.findById(transition.getToNodoId()).orElse(null);
            List<Map<String, Object>> expandedOptions = findNodoTipoHandler(directTarget)
                    .buildTransitionOptions(transition, directTarget, transitions);
            if (!expandedOptions.isEmpty()) {
                available.addAll(expandedOptions);
                continue;
            }
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", transition.getId());
            option.put("name", transition.getName());
            option.put("label", transition.getName());
            option.put("fromNodoId", transition.getFromNodoId());
            option.put("toNodoId", transition.getToNodoId());
            option.put("targetNodoName", directTarget != null ? directTarget.getName() : transition.getToNodoId());
            option.put("tipo", "transicion");
            available.add(option);
        }
        return available;
    }

    private List<Map<String, Object>> buildIncomingData(Tramite tramite, WorkflowNodo currentNodo, List<WorkflowTransition> transitions) {
        Map<String, Object> tramiteData = tramite.getFormData() == null ? Map.of() : tramite.getFormData();
        List<Map<String, Object>> incomingData = new ArrayList<>();
        for (WorkflowTransition transition : transitions) {
            if (!currentNodo.getId().equals(transition.getToNodoId())) continue;
            WorkflowNodo sourceNodo = nodoRepo.findById(transition.getFromNodoId()).orElse(null);
            if (sourceNodo == null) continue;
            List<Map<String, Object>> fields = buildSharedFields(sourceNodo, transition, tramiteData, transitions, new LinkedHashSet<>());
            if (fields.isEmpty()) continue;
            Map<String, Object> incoming = new LinkedHashMap<>();
            incoming.put("transitionId", transition.getId());
            incoming.put("transitionName", transition.getName());
            incoming.put("fromNodoName", sourceNodo.getName());
            incoming.put("fields", fields);
            incomingData.add(incoming);
        }
        return incomingData;
    }

    private WorkflowNodo resolveEntryNodo(List<WorkflowNodo> nodos, List<WorkflowTransition> transitions) {
        if (nodos == null || nodos.isEmpty()) {
            return null;
        }
        WorkflowNodo initialNodo = nodos.stream()
                .filter(nodo -> "inicio".equalsIgnoreCase(nodo.getNodeType()))
                .findFirst()
                .orElse(null);
        WorkflowNodo firstWorkNodo = nodos.stream()
                .filter(nodo -> !"inicio".equalsIgnoreCase(nodo.getNodeType()))
                .findFirst()
                .orElse(initialNodo);
        if (initialNodo == null) {
            return firstWorkNodo;
        }
        WorkflowTransition startTransition = transitions.stream()
                .filter(transition -> initialNodo.getId().equals(transition.getFromNodoId()))
                .findFirst()
                .orElse(null);
        if (startTransition == null) {
            return firstWorkNodo;
        }
        return nodos.stream()
                .filter(nodo -> startTransition.getToNodoId().equals(nodo.getId()))
                .findFirst()
                .orElse(firstWorkNodo);
    }

    private Map<String, Object> parseVoiceTranscript(String transcript,
                                                     FormDefinition formDefinition,
                                                     Map<String, Object> currentFormData) {
        Map<String, Object> current = currentFormData == null ? Map.of() : currentFormData;
        Map<String, Object> aiResult = workflowAiProxyService.formVoiceFill(Map.of(
                "transcript", transcript,
                "formDefinition", toFormDefinitionPayload(formDefinition),
                "currentFormData", current
        ));
        return normalizeAiVoiceResult(transcript, current, aiResult);
    }

    private Map<String, Object> normalizeAiVoiceResult(String transcript,
                                                       Map<String, Object> currentFormData,
                                                       Map<String, Object> aiResult) {
        Map<String, Object> mergedFormData = new LinkedHashMap<>();
        mergedFormData.putAll(currentFormData);

        Map<String, Object> fieldValues = extractObjectMap(aiResult.get("fieldValues"));
        mergedFormData.putAll(fieldValues);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("transcript", transcript);
        response.put("formData", mergedFormData);
        response.put("appliedFields", extractAppliedFields(aiResult.get("appliedFields"), fieldValues));
        response.put("warnings", extractStringList(aiResult.get("warnings")));
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractObjectMap(Object rawValue) {
        if (!(rawValue instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> mapped.put(String.valueOf(key), value));
        return mapped;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractAppliedFields(Object rawValue, Map<String, Object> fieldValues) {
        if (rawValue instanceof List<?> rawList) {
            return rawList.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .map(item -> {
                        Map<String, Object> mapped = new LinkedHashMap<>();
                        mapped.put("field", String.valueOf(item.getOrDefault("field", "")));
                        mapped.put("value", item.get("value"));
                        return mapped;
                    })
                    .filter(item -> !String.valueOf(item.get("field")).isBlank())
                    .toList();
        }
        return fieldValues.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> mapped = new LinkedHashMap<>();
                    mapped.put("field", entry.getKey());
                    mapped.put("value", entry.getValue());
                    return mapped;
                })
                .toList();
    }

    private List<String> extractStringList(Object rawValue) {
        if (!(rawValue instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream()
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private Map<String, Object> toFormDefinitionPayload(FormDefinition formDefinition) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", formDefinition.getId());
        payload.put("nodoId", formDefinition.getNodoId());
        payload.put("title", formDefinition.getTitle());
        payload.put("fields", formDefinition.getFields() == null ? List.of() : formDefinition.getFields().stream()
                .map(field -> {
                    Map<String, Object> mapped = new LinkedHashMap<>();
                    mapped.put("id", field.getId());
                    mapped.put("name", field.getName());
                    mapped.put("type", field.getType() != null ? field.getType().name() : "TEXT");
                    mapped.put("isRequired", field.isRequired());
                    mapped.put("order", field.getOrder());
                    mapped.put("columns", field.getColumns() == null ? List.of() : field.getColumns().stream()
                            .map(column -> {
                                Map<String, Object> mappedColumn = new LinkedHashMap<>();
                                mappedColumn.put("id", column.getId());
                                mappedColumn.put("name", column.getName());
                                mappedColumn.put("type", column.getType() != null ? column.getType().name() : "TEXT");
                                mappedColumn.put("order", column.getOrder());
                                return mappedColumn;
                            })
                            .toList());
                    return mapped;
                })
                .toList());
        return payload;
    }

    private List<Map<String, Object>> buildSharedFields(WorkflowNodo sourceNodo, WorkflowTransition transition,
                                                        Map<String, Object> tramiteData,
                                                        List<WorkflowTransition> transitions, Set<String> visitedNodoIds) {
        List<FormDefinition.FormField> sourceFields = getForwardableFields(sourceNodo, transitions, visitedNodoIds);
        Map<String, Object> forwardConfig = transition.getForwardConfig();
        String mode = resolveForwardMode(forwardConfig);
        Set<String> selectedFieldNames = resolveSelectedFields(forwardConfig);
        boolean includeFiles = resolveIncludeFiles(forwardConfig);

        return sourceFields.stream()
                .filter(field -> shouldIncludeField(field, mode, selectedFieldNames, includeFiles))
                .map(field -> {
                    Object value = tramiteData.get(field.getName());
                    if (!hasMeaningfulValue(value)) return null;
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("label", field.getName());
                    map.put("name", field.getName());
                    map.put("type", field.getType());
                    map.put("columns", field.getColumns());
                    map.put("value", value);
                    return map;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private List<FormDefinition.FormField> getForwardableFields(WorkflowNodo nodo, List<WorkflowTransition> transitions, Set<String> visitedNodoIds) {
        if (nodo == null || nodo.getId() == null || !visitedNodoIds.add(nodo.getId())) return List.of();
        if (!isPassThroughNode(nodo)) {
            FormDefinition form = formRepo.findByNodoId(nodo.getId()).orElse(null);
            if (form == null || form.getFields() == null) return List.of();
            return dedupeFields(form.getFields());
        }
        List<FormDefinition.FormField> aggregated = new ArrayList<>();
        for (WorkflowTransition incoming : transitions) {
            if (!nodo.getId().equals(incoming.getToNodoId())) continue;
            WorkflowNodo upstreamNodo = nodoRepo.findById(incoming.getFromNodoId()).orElse(null);
            if (upstreamNodo == null) continue;
            aggregated.addAll(buildForwardedFieldDefinitions(upstreamNodo, incoming, transitions, new LinkedHashSet<>(visitedNodoIds)));
        }
        return dedupeFields(aggregated);
    }

    private List<FormDefinition.FormField> buildForwardedFieldDefinitions(WorkflowNodo sourceNodo, WorkflowTransition transition,
                                                                          List<WorkflowTransition> transitions, Set<String> visitedNodoIds) {
        List<FormDefinition.FormField> sourceFields = getForwardableFields(sourceNodo, transitions, visitedNodoIds);
        Map<String, Object> forwardConfig = transition.getForwardConfig();
        return sourceFields.stream()
                .filter(field -> shouldIncludeField(
                        field,
                        resolveForwardMode(forwardConfig),
                        resolveSelectedFields(forwardConfig),
                        resolveIncludeFiles(forwardConfig)
                ))
                .toList();
    }

    private String resolveForwardMode(Map<String, Object> forwardConfig) {
        if (forwardConfig == null) return "none";
        String mode = String.valueOf(forwardConfig.get("mode")).trim().toLowerCase();
        return switch (mode) {
            case "selected", "all", "files-only" -> mode;
            default -> "none";
        };
    }

    private Set<String> resolveSelectedFields(Map<String, Object> forwardConfig) {
        Set<String> selected = new LinkedHashSet<>();
        if (forwardConfig != null && forwardConfig.get("fieldNames") instanceof List<?> fieldNames) {
            fieldNames.stream().map(String::valueOf).forEach(selected::add);
        }
        return selected;
    }

    private boolean resolveIncludeFiles(Map<String, Object> forwardConfig) {
        if (forwardConfig == null) return false;
        Object includeFiles = forwardConfig.get("includeFiles");
        if (includeFiles instanceof Boolean value) {
            return value;
        }
        return "files-only".equals(resolveForwardMode(forwardConfig));
    }

    private List<FormDefinition.FormField> dedupeFields(List<FormDefinition.FormField> fields) {
        Map<String, FormDefinition.FormField> deduped = new LinkedHashMap<>();
        for (FormDefinition.FormField field : fields) {
            if (field == null || field.getName() == null || field.getName().isBlank()) continue;
            deduped.putIfAbsent(field.getName(), field);
        }
        return new ArrayList<>(deduped.values());
    }

    private boolean shouldIncludeField(FormDefinition.FormField field, String mode, Set<String> selectedFieldNames, boolean includeFiles) {
        if (field == null) return false;
        boolean isFileField = FormDefinition.FieldType.FILE.equals(field.getType());
        if ("none".equalsIgnoreCase(mode)) return false;
        if ("files-only".equalsIgnoreCase(mode)) return isFileField;
        if ("all".equalsIgnoreCase(mode)) return includeFiles || !isFileField;
        if ("selected".equalsIgnoreCase(mode)) {
            return selectedFieldNames.contains(field.getName()) || (includeFiles && isFileField);
        }
        return false;
    }

    private boolean hasMeaningfulValue(Object value) {
        if (value == null) return false;
        if (value instanceof CharSequence text) return !text.toString().isBlank();
        if (value instanceof List<?> list) return !list.isEmpty();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        return true;
    }

    private boolean hasWorkflowAccess(User actor, Workflow workflow) {
        if (actor.getRole() == User.Role.SUPERADMIN) return true;
        return actor.getCompanyId() != null && actor.getCompanyId().equals(workflow.getCompanyId());
    }

    private boolean matchesNodoResponsibility(WorkflowNodo nodo, User actor) {
        boolean hasJobRole = nodo.getResponsibleJobRoleId() != null && !nodo.getResponsibleJobRoleId().isBlank();
        boolean hasDepartment = nodo.getResponsibleDepartmentId() != null && !nodo.getResponsibleDepartmentId().isBlank();
        boolean hasRole = nodo.getResponsibleRole() != null;

        if (hasJobRole) {
            boolean matchesJobRole = nodo.getResponsibleJobRoleId().equals(actor.getJobRoleId());
            if (!matchesJobRole) return false;
            return !hasDepartment || (actor.getDepartmentId() != null && actor.getDepartmentId().equals(nodo.getResponsibleDepartmentId()));
        }
        if (hasDepartment) {
            return actor.getDepartmentId() != null && actor.getDepartmentId().equals(nodo.getResponsibleDepartmentId());
        }
        if (hasRole) {
            return actor.getRole() == nodo.getResponsibleRole();
        }
        return false;
    }

    private void handleJoinSyncIfNeeded(Tramite tramite, WorkflowNodo joinNodo, String userId) {
        List<WorkflowTransition> allTransitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(tramite.getWorkflowId());
        long expectedBranches = allTransitions.stream()
                .filter(t -> joinNodo.getId().equals(t.getToNodoId()))
                .count();

        String rootId = tramite.getParentTramiteId() != null ? tramite.getParentTramiteId() : tramite.getId();
        Tramite root = tramiteRepo.findById(rootId).orElse(null);
        if (root == null) return;

        List<Tramite> clones = tramiteRepo.findByParentTramiteId(rootId);
        long arrivedCount = (joinNodo.getId().equals(root.getCurrentNodoId()) ? 1 : 0)
                + clones.stream().filter(c -> joinNodo.getId().equals(c.getCurrentNodoId())).count();

        if (arrivedCount < expectedBranches) return;

        Map<String, Object> merged = new LinkedHashMap<>();
        if (root.getFormData() != null) merged.putAll(root.getFormData());
        for (Tramite clone : clones) {
            if (clone.getFormData() != null) merged.putAll(clone.getFormData());
        }
        root.setFormData(merged);

        WorkflowTransition nextTransition = allTransitions.stream()
                .filter(t -> joinNodo.getId().equals(t.getFromNodoId()))
                .findFirst().orElse(null);

        if (nextTransition != null) {
            WorkflowNodo nextNodo = nodoRepo.findById(nextTransition.getToNodoId()).orElse(null);
            boolean isFinal = nextNodo != null && "fin".equalsIgnoreCase(nextNodo.getNodeType());
            root.setCurrentNodoId(nextTransition.getToNodoId());
            root.setStatus(isFinal ? Tramite.Status.COMPLETADO : Tramite.Status.EN_PROGRESO);
            tramiteRepo.save(root);
            recordHistory(root.getId(), joinNodo.getId(), nextTransition.getToNodoId(), "UNION_COMPLETADA", userId, "Todas las ramas completadas");
        }

        clones.forEach(clone -> tramiteRepo.deleteById(clone.getId()));
    }

    private boolean isPassThroughNode(WorkflowNodo nodo) {
        return findNodoTipoHandler(nodo).isPassThrough();
    }

    private boolean hasNodeType(WorkflowNodo nodo, String... nodeTypes) {
        if (nodo == null || nodo.getNodeType() == null) return false;
        String value = nodo.getNodeType().toLowerCase();
        for (String nodeType : nodeTypes) {
            if (value.equals(nodeType)) return true;
        }
        return false;
    }

    private String resolveBranchOutcome(WorkflowNodo decisionNodo, WorkflowTransition branch) {
        if (decisionNodo == null || branch == null) return null;
        String name = branch.getName() == null ? "" : branch.getName().trim().toLowerCase();
        if (hasNodeType(decisionNodo, "iteracion")) {
            if (name.equals("repetir")) return "rechazo";
            if (name.equals("salir")) return "aceptacion";
        } else if (hasNodeType(decisionNodo, "decision")) {
            if (name.equals("si") || name.equals("sí") || name.equals("aprobado") || name.equals("aceptado")) return "aceptacion";
            if (name.equals("no") || name.equals("rechazado") || name.equals("rechazar")) return "rechazo";
        }
        return null;
    }

    private NodoTipoHandler findNodoTipoHandler(WorkflowNodo nodo) {
        if (nodo == null) {
            return NodoTipoHandler.DEFAULT;
        }
        return nodoTipoHandlers.stream()
                .filter(handler -> handler.supports(nodo))
                .findFirst()
                .orElse(NodoTipoHandler.DEFAULT);
    }

    private static final class AdvanceCursor {
        private WorkflowTransition finalTransition;
        private WorkflowNodo passThroughNodo;
        private WorkflowNodo toNodo;
        private String bifurcasionPassthroughId;
        private String transitionHistoryAction;
        private int transitionPathIndex;

        private AdvanceCursor(WorkflowTransition finalTransition,
                              WorkflowNodo passThroughNodo,
                              WorkflowNodo toNodo,
                              String transitionHistoryAction,
                              int transitionPathIndex) {
            this.finalTransition = finalTransition;
            this.passThroughNodo = passThroughNodo;
            this.toNodo = toNodo;
            this.transitionHistoryAction = transitionHistoryAction;
            this.transitionPathIndex = transitionPathIndex;
        }
    }

    private abstract static class NodoTipoHandler {
        private static final NodoTipoHandler DEFAULT = new NodoTipoHandler() {
            @Override
            boolean supports(WorkflowNodo nodo) {
                return true;
            }
        };

        abstract boolean supports(WorkflowNodo nodo);

        boolean isPassThrough() {
            return false;
        }

        boolean consumeAdvance(AdvanceCursor cursor, String[] transitionPath, List<WorkflowTransition> workflowTransitions) {
            return false;
        }

        List<Map<String, Object>> buildTransitionOptions(WorkflowTransition transition, WorkflowNodo directTarget,
                                                         List<WorkflowTransition> transitions) {
            return List.of();
        }

        String resolveBranchOutcome(WorkflowTransition branch) {
            return null;
        }
    }

    private abstract class NodoDecisionBaseHandler extends NodoTipoHandler {
        @Override
        boolean isPassThrough() {
            return true;
        }

        @Override
        boolean consumeAdvance(AdvanceCursor cursor, String[] transitionPath, List<WorkflowTransition> workflowTransitions) {
            if (transitionPath.length <= cursor.transitionPathIndex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debes elegir una rama de la decision");
            }
            cursor.passThroughNodo = cursor.toNodo;
            cursor.finalTransition = transitionRepo.findById(transitionPath[cursor.transitionPathIndex])
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rama de decision no encontrada"));
            cursor.transitionPathIndex++;
            if (!cursor.passThroughNodo.getId().equals(cursor.finalTransition.getFromNodoId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rama de decision invalida");
            }
            cursor.transitionHistoryAction = resolveTransitionHistoryAction(cursor.finalTransition);
            cursor.toNodo = nodoRepo.findById(cursor.finalTransition.getToNodoId()).orElse(null);
            return true;
        }

        @Override
        List<Map<String, Object>> buildTransitionOptions(WorkflowTransition transition, WorkflowNodo directTarget,
                                                         List<WorkflowTransition> transitions) {
            List<Map<String, Object>> options = new ArrayList<>();
            for (WorkflowTransition branch : transitions) {
                if (!directTarget.getId().equals(branch.getFromNodoId())) continue;
                WorkflowNodo finalTarget = nodoRepo.findById(branch.getToNodoId()).orElse(null);
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("id", transition.getId() + ">>" + branch.getId());
                option.put("name", branch.getName());
                option.put("label", branch.getName());
                option.put("fromNodoId", transition.getFromNodoId());
                option.put("toNodoId", branch.getToNodoId());
                option.put("nodoDecisionId", directTarget.getId());
                option.put("nodoDecisionNombre", directTarget.getName());
                option.put("tipoNodoDecision", directTarget.getNodeType());
                option.put("resultadoRama", resolveBranchOutcome(branch));
                option.put("targetNodoName", finalTarget != null ? finalTarget.getName() : branch.getToNodoId());
                option.put("tipo", "rama-decision");
                options.add(option);
            }
            return options;
        }

        abstract String resolveTransitionHistoryAction(WorkflowTransition transition);
    }

    private final class NodoDecisionHandler extends NodoDecisionBaseHandler {
        @Override
        boolean supports(WorkflowNodo nodo) {
            return hasNodeType(nodo, "decision");
        }

        @Override
        String resolveTransitionHistoryAction(WorkflowTransition transition) {
            return "rechazo".equals(resolveBranchOutcome(transition)) ? "DECISION_RECHAZADA" : "AVANZADO";
        }

        @Override
        String resolveBranchOutcome(WorkflowTransition branch) {
            String name = branch.getName() == null ? "" : branch.getName().trim().toLowerCase();
            if (name.equals("si") || name.equals("sí") || name.equals("aprobado") || name.equals("aceptado")) return "aceptacion";
            if (name.equals("no") || name.equals("rechazado") || name.equals("rechazar")) return "rechazo";
            return null;
        }
    }

    private final class NodoIteracionHandler extends NodoDecisionBaseHandler {
        @Override
        boolean supports(WorkflowNodo nodo) {
            return hasNodeType(nodo, "iteracion");
        }

        @Override
        String resolveTransitionHistoryAction(WorkflowTransition transition) {
            String name = transition.getName() == null ? "" : transition.getName().trim().toLowerCase();
            if (name.equals("repetir")) return "LOOP_RECHAZADO";
            if (name.equals("salir")) return "LOOP_APROBADO";
            return "LOOP_EVALUADO";
        }

        @Override
        String resolveBranchOutcome(WorkflowTransition branch) {
            String name = branch.getName() == null ? "" : branch.getName().trim().toLowerCase();
            if (name.equals("repetir")) return "rechazo";
            if (name.equals("salir")) return "aceptacion";
            return null;
        }
    }

    private final class NodoBifurcasionHandler extends NodoTipoHandler {
        @Override
        boolean supports(WorkflowNodo nodo) {
            return hasNodeType(nodo, "bifurcasion");
        }

        @Override
        boolean isPassThrough() {
            return true;
        }

        @Override
        boolean consumeAdvance(AdvanceCursor cursor, String[] transitionPath, List<WorkflowTransition> workflowTransitions) {
            cursor.bifurcasionPassthroughId = cursor.toNodo.getId();
            WorkflowTransition firstBranch = workflowTransitions.stream()
                    .filter(t -> cursor.toNodo.getId().equals(t.getFromNodoId()))
                    .findFirst()
                    .orElse(null);
            if (firstBranch == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La bifurcacion no tiene ramas configuradas");
            }
            cursor.finalTransition = firstBranch;
            cursor.toNodo = nodoRepo.findById(firstBranch.getToNodoId()).orElse(null);
            return true;
        }
    }

    private final class NodoUnionHandler extends NodoTipoHandler {
        @Override
        boolean supports(WorkflowNodo nodo) {
            return hasNodeType(nodo, "union");
        }

        @Override
        boolean isPassThrough() {
            return true;
        }
    }

}
