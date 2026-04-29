package com.workflow.service;

import com.workflow.model.FormDefinition;
import com.workflow.model.Company;
import com.workflow.model.Department;
import com.workflow.model.User;
import com.workflow.model.Workflow;
import com.workflow.model.WorkflowNodo;
import com.workflow.model.WorkflowTransition;
import com.workflow.repository.CompanyRepository;
import com.workflow.repository.DepartmentRepository;
import com.workflow.repository.FormDefinitionRepository;
import com.workflow.repository.WorkflowRepository;
import com.workflow.repository.WorkflowNodoRepository;
import com.workflow.repository.WorkflowTransitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowRepository workflowRepo;
    private final WorkflowNodoRepository nodoRepo;
    private final WorkflowTransitionRepository transitionRepo;
    private final FormDefinitionRepository formRepo;
    private final CompanyRepository companyRepo;
    private final DepartmentRepository departmentRepo;

    public List<Map<String, Object>> findAll(User actor) {
        List<Workflow> workflows;
        if (actor.getRole() == User.Role.SUPERADMIN) {
            workflows = workflowRepo.findAll();
        } else if (actor.getCompanyId() != null && !actor.getCompanyId().isBlank()) {
            workflows = workflowRepo.findByCompanyIdOrderByCreatedAtDesc(actor.getCompanyId());
        } else {
            workflows = List.of();
        }
        return enrichWorkflowList(workflows);
    }

    private List<Map<String, Object>> enrichWorkflowList(List<Workflow> workflows) {
        if (workflows.isEmpty()) return List.of();
        Set<String> wfIds = workflows.stream().map(Workflow::getId).collect(Collectors.toSet());
        Set<String> companyIds = workflows.stream().map(Workflow::getCompanyId).filter(id -> id != null && !id.isBlank()).collect(Collectors.toSet());
        Map<String, String> companyNames = companyRepo.findAllById(companyIds).stream()
                .collect(Collectors.toMap(Company::getId, Company::getName));
        Map<String, List<WorkflowNodo>> nodoByWf = nodoRepo.findByWorkflowIdIn(wfIds).stream()
                .collect(Collectors.groupingBy(WorkflowNodo::getWorkflowId));
        return workflows.stream().map(wf -> {
            List<WorkflowNodo> nodo = nodoByWf.getOrDefault(wf.getId(), List.of());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", wf.getId());
            map.put("name", wf.getName());
            map.put("description", wf.getDescription());
            map.put("companyId", wf.getCompanyId());
            map.put("companyName", wf.getCompanyId() != null ? companyNames.get(wf.getCompanyId()) : null);
            map.put("nodo", nodo);
            map.put("_count", Map.of("tramites", 0, "nodo", nodo.size()));
            return map;
        }).toList();
    }

    public Map<String, Object> findOne(String id, User actor) {
        Workflow workflow = findWorkflow(id);
        validateWorkflowScope(actor, workflow);
        return enrichWorkflowFull(workflow);
    }

    public Workflow create(Map<String, Object> body, User actor) {
        if (actor.getRole() != User.Role.SUPERADMIN) {
            body.put("companyId", actor.getCompanyId());
        }
        Workflow workflow = new Workflow();
        workflow.setName((String) body.get("name"));
        workflow.setDescription((String) body.get("description"));
        workflow.setCompanyId((String) body.get("companyId"));
        return workflowRepo.save(workflow);
    }

    public Workflow update(String id, Map<String, Object> body, User actor) {
        Workflow workflow = findWorkflow(id);
        validateWorkflowScope(actor, workflow);
        if (actor.getRole() != User.Role.SUPERADMIN) {
            body.put("companyId", actor.getCompanyId());
        }
        if (body.containsKey("name")) workflow.setName((String) body.get("name"));
        if (body.containsKey("description")) workflow.setDescription((String) body.get("description"));
        if (body.containsKey("companyId")) workflow.setCompanyId((String) body.get("companyId"));
        return workflowRepo.save(workflow);
    }

    public WorkflowNodo createNodo(Map<String, Object> body) {
        String workflowId = (String) body.get("workflowId");
        if (workflowId == null || workflowId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workflowId es obligatorio");
        }

        WorkflowNodo nodo = new WorkflowNodo();
        applyNodoFields(nodo, body);

        Integer requestedOrder = extractRequestedOrder(body);
        nodo.setOrder(resolveCreateOrder(workflowId, requestedOrder));

        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                WorkflowNodo saved = nodoRepo.save(nodo);
                syncNodoFormDefinition(saved, body);
                return hydrateNodoFormDefinition(saved);
            } catch (DuplicateKeyException ex) {
                nodo.setOrder(resolveCreateOrder(workflowId, nodo.getOrder() + 1));
            }
        }

        throw new ResponseStatusException(HttpStatus.CONFLICT, "No se pudo asignar un orden disponible para la etapa");
    }

    public WorkflowNodo findNodo(String id) {
        return nodoRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Etapa no encontrada"));
    }

    public WorkflowNodo updateNodo(String id, Map<String, Object> body) {
        WorkflowNodo nodo = findNodo(id);
        applyNodoFields(nodo, body);
        WorkflowNodo saved = nodoRepo.save(nodo);
        syncNodoFormDefinition(saved, body);
        return hydrateNodoFormDefinition(saved);
    }

    public void deleteNodo(String id) {
        findNodo(id);
        formRepo.findByNodoId(id).ifPresent(formRepo::delete);
        nodoRepo.deleteById(id);
        transitionRepo.deleteByFromNodoIdOrToNodoId(id, id);
    }

    public WorkflowTransition createTransition(Map<String, Object> body) {
        WorkflowTransition transition = new WorkflowTransition();
        applyTransitionFields(transition, body);
        validateTransitionStructure(transition);
        return transitionRepo.save(transition);
    }

    public WorkflowTransition findTransition(String id) {
        return transitionRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transicion no encontrada"));
    }

    public WorkflowTransition updateTransition(String id, Map<String, Object> body) {
        WorkflowTransition transition = findTransition(id);
        applyTransitionFields(transition, body);
        validateTransitionStructure(transition);
        return transitionRepo.save(transition);
    }

    public void deleteTransition(String id) {
        findTransition(id);
        transitionRepo.deleteById(id);
    }

    private void applyNodoFields(WorkflowNodo nodo, Map<String, Object> body) {
        if (body.containsKey("workflowId")) nodo.setWorkflowId((String) body.get("workflowId"));
        if (body.containsKey("name")) nodo.setName((String) body.get("name"));
        if (body.containsKey("description")) nodo.setDescription((String) body.get("description"));
        if (nodo.getId() != null && body.containsKey("order") && body.get("order") != null) {
            nodo.setOrder(((Number) body.get("order")).intValue());
        }
        if (body.containsKey("responsibleRole")) {
            Object responsibleRole = body.get("responsibleRole");
            if (responsibleRole == null || responsibleRole.toString().isBlank()) {
                nodo.setResponsibleRole(null);
            } else {
                nodo.setResponsibleRole(com.workflow.model.User.Role.valueOf(responsibleRole.toString().toUpperCase()));
            }
        }
        if (body.containsKey("responsibleDepartmentId")) nodo.setResponsibleDepartmentId((String) body.get("responsibleDepartmentId"));
        if (body.containsKey("requiresForm")) nodo.setRequiresForm(Boolean.TRUE.equals(body.get("requiresForm")));
        if (body.containsKey("avgMinutes") && body.get("avgMinutes") != null) {
            nodo.setAvgMinutes(((Number) body.get("avgMinutes")).intValue());
        }
        if (body.containsKey("nodeType")) nodo.setNodeType(normalizarTipoNodo((String) body.get("nodeType")));
        if (body.containsKey("posX") && body.get("posX") != null) {
            nodo.setPosX(((Number) body.get("posX")).doubleValue());
        }
        if (body.containsKey("posY") && body.get("posY") != null) {
            nodo.setPosY(((Number) body.get("posY")).doubleValue());
        }
        if (body.containsKey("responsibleJobRoleId")) nodo.setResponsibleJobRoleId((String) body.get("responsibleJobRoleId"));
        if (body.containsKey("trueLabel")) nodo.setTrueLabel((String) body.get("trueLabel"));
        if (body.containsKey("falseLabel")) nodo.setFalseLabel((String) body.get("falseLabel"));
        if (body.containsKey("condition")) nodo.setCondition((String) body.get("condition"));
    }

    @SuppressWarnings("unchecked")
    private void syncNodoFormDefinition(WorkflowNodo nodo, Map<String, Object> body) {
        if (nodo == null || nodo.getId() == null) {
            return;
        }

        boolean isProcessNodo = "proceso".equalsIgnoreCase(nodo.getNodeType());
        boolean hasFormPayload = body.containsKey("formDefinition");

        if (!isProcessNodo || !nodo.isRequiresForm()) {
            formRepo.findByNodoId(nodo.getId()).ifPresent(formRepo::delete);
            return;
        }

        if (!hasFormPayload) {
            return;
        }

        Object rawFormDefinition = body.get("formDefinition");
        if (!(rawFormDefinition instanceof Map<?, ?> rawFormMap)) {
            formRepo.findByNodoId(nodo.getId()).ifPresent(formRepo::delete);
            return;
        }

        FormDefinition formDefinition = formRepo.findByNodoId(nodo.getId()).orElse(new FormDefinition());
        formDefinition.setNodoId(nodo.getId());
        Object rawTitle = rawFormMap.get("title");
        formDefinition.setTitle(rawTitle == null ? "Formulario" : String.valueOf(rawTitle));
        formDefinition.setFields(mapFormFields((List<Map<String, Object>>) rawFormMap.get("fields")));
        formRepo.save(formDefinition);
    }

    private List<FormDefinition.FormField> mapFormFields(List<Map<String, Object>> rawFields) {
        if (rawFields == null || rawFields.isEmpty()) {
            return List.of();
        }

        List<FormDefinition.FormField> fields = new ArrayList<>();
        for (int index = 0; index < rawFields.size(); index++) {
            Map<String, Object> field = rawFields.get(index);
            if (field == null) {
                continue;
            }

            FormDefinition.FormField mapped = new FormDefinition.FormField();
            mapped.setId((String) field.get("id"));
            mapped.setName((String) field.getOrDefault("name", field.get("id")));
            mapped.setType(parseFieldType(field.get("type")));
            mapped.setColumns(mapGridColumns(field.get("columns")));

            boolean required = Boolean.TRUE.equals(field.get("required")) || Boolean.TRUE.equals(field.get("isRequired"));
            mapped.setRequired(required);

            Object order = field.get("order");
            mapped.setOrder(order instanceof Number number ? number.intValue() : index + 1);
            fields.add(mapped);
        }
        return fields;
    }

    @SuppressWarnings("unchecked")
    private List<FormDefinition.GridColumn> mapGridColumns(Object rawColumns) {
        if (!(rawColumns instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }

        List<FormDefinition.GridColumn> columns = new ArrayList<>();
        for (int index = 0; index < rawList.size(); index++) {
            Object rawColumn = rawList.get(index);
            if (!(rawColumn instanceof Map<?, ?> columnMapRaw)) {
                continue;
            }
            Map<String, Object> columnMap = (Map<String, Object>) columnMapRaw;
            FormDefinition.GridColumn column = new FormDefinition.GridColumn();
            column.setId((String) columnMap.get("id"));
            column.setName((String) columnMap.getOrDefault("name", columnMap.get("id")));
            column.setType(parseGridColumnType(columnMap.get("type")));
            Object order = columnMap.get("order");
            column.setOrder(order instanceof Number number ? number.intValue() : index + 1);
            columns.add(column);
        }
        return columns;
    }

    private FormDefinition.FieldType parseFieldType(Object rawType) {
        if (rawType == null) {
            return FormDefinition.FieldType.TEXT;
        }
        try {
            return FormDefinition.FieldType.valueOf(String.valueOf(rawType).toUpperCase());
        } catch (IllegalArgumentException ex) {
            return FormDefinition.FieldType.TEXT;
        }
    }

    private FormDefinition.FieldType parseGridColumnType(Object rawType) {
        FormDefinition.FieldType parsed = parseFieldType(rawType);
        return parsed == FormDefinition.FieldType.GRID ? FormDefinition.FieldType.TEXT : parsed;
    }

    private String normalizarTipoNodo(String rawNodeType) {
        if (rawNodeType == null || rawNodeType.isBlank()) {
            return rawNodeType;
        }
        return rawNodeType.trim().toLowerCase();
    }

    private Integer extractRequestedOrder(Map<String, Object> body) {
        Object order = body.get("order");
        if (!(order instanceof Number number)) {
            return null;
        }
        int value = number.intValue();
        return value > 0 ? value : null;
    }

    private int resolveCreateOrder(String workflowId, Integer requestedOrder) {
        List<WorkflowNodo> existingNodos = nodoRepo.findByWorkflowIdOrderByOrderAsc(workflowId);
        Set<Integer> usedOrders = new HashSet<>();
        int maxOrder = 0;

        for (WorkflowNodo existingNodo : existingNodos) {
            usedOrders.add(existingNodo.getOrder());
            maxOrder = Math.max(maxOrder, existingNodo.getOrder());
        }

        int candidate = requestedOrder != null ? requestedOrder : (maxOrder + 1);
        if (candidate <= 0) {
            candidate = 1;
        }
        while (usedOrders.contains(candidate)) {
            candidate++;
        }
        return candidate;
    }

    @SuppressWarnings("unchecked")
    private void applyTransitionFields(WorkflowTransition transition, Map<String, Object> body) {
        if (body.containsKey("workflowId")) transition.setWorkflowId((String) body.get("workflowId"));
        if (body.containsKey("fromNodoId")) transition.setFromNodoId((String) body.get("fromNodoId"));
        if (body.containsKey("toNodoId")) transition.setToNodoId((String) body.get("toNodoId"));
        if (body.containsKey("name")) transition.setName((String) body.getOrDefault("name", ""));
        if (body.containsKey("forwardConfig")) {
            Map<String, Object> raw = (Map<String, Object>) body.get("forwardConfig");
            if (raw == null) {
                transition.setForwardConfig(null);
            } else {
                String mode = normalizeForwardMode(raw.get("mode"));
                Object fieldNames = raw.get("fieldNames");
                boolean includeFiles = Boolean.TRUE.equals(raw.get("includeFiles")) || "files-only".equals(mode);
                Map<String, Object> forwardConfig = new LinkedHashMap<>();
                forwardConfig.put("mode", mode);
                forwardConfig.put("fieldNames", "selected".equals(mode) && fieldNames instanceof java.util.List<?> ? fieldNames : java.util.List.of());
                forwardConfig.put("includeFiles", includeFiles);
                transition.setForwardConfig(forwardConfig);
            }
        }
    }

    private String normalizeForwardMode(Object rawMode) {
        String mode = rawMode == null ? "none" : String.valueOf(rawMode).trim().toLowerCase();
        return switch (mode) {
            case "selected", "all", "files-only" -> mode;
            default -> "none";
        };
    }

    private void validateTransitionStructure(WorkflowTransition transition) {
        if (transition.getWorkflowId() == null || transition.getWorkflowId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workflowId es obligatorio");
        }
        if (transition.getFromNodoId() == null || transition.getFromNodoId().isBlank() ||
            transition.getToNodoId() == null || transition.getToNodoId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La conexion requiere origen y destino");
        }

        WorkflowNodo fromNodo = nodoRepo.findById(transition.getFromNodoId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nodo origen no encontrado"));
        WorkflowNodo toNodo = nodoRepo.findById(transition.getToNodoId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nodo destino no encontrado"));

        if (!transition.getWorkflowId().equals(fromNodo.getWorkflowId()) || !transition.getWorkflowId().equals(toNodo.getWorkflowId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Los nodos deben pertenecer al mismo workflow");
        }

        String fromType = normalizarTipoNodo(fromNodo.getNodeType());
        String toType = normalizarTipoNodo(toNodo.getNodeType());
        if ("inicio".equals(fromType) && !"proceso".equals(toType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inicio solo puede conectarse a un Proceso");
        }
        if ("fin".equals(toType) && !"proceso".equals(fromType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fin solo puede recibir conexion desde un Proceso");
        }
    }

    private WorkflowNodo hydrateNodoFormDefinition(WorkflowNodo nodo) {
        if (nodo == null) {
            return null;
        }
        nodo.setFormDefinition(formRepo.findByNodoId(nodo.getId()).orElse(null));
        return nodo;
    }

    private Map<String, Object> enrichWorkflowFull(Workflow workflow) {
        List<WorkflowNodo> nodos = nodoRepo.findByWorkflowIdOrderByOrderAsc(workflow.getId());
        List<WorkflowTransition> transitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(workflow.getId());
        Company company = workflow.getCompanyId() != null ? companyRepo.findById(workflow.getCompanyId()).orElse(null) : null;

        Set<String> deptIds = nodos.stream().map(WorkflowNodo::getResponsibleDepartmentId).filter(id -> id != null && !id.isBlank()).collect(Collectors.toSet());
        Map<String, String> deptNames = departmentRepo.findAllById(deptIds).stream().collect(Collectors.toMap(Department::getId, Department::getName));

        Set<String> nodoIds = nodos.stream().map(WorkflowNodo::getId).collect(Collectors.toSet());
        Map<String, FormDefinition> formByNodo = formRepo.findByNodoIdIn(nodoIds).stream().collect(Collectors.toMap(FormDefinition::getNodoId, f -> f));

        List<Map<String, Object>> nodoMapped = nodos.stream().map(nodo -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", nodo.getId());
            mapped.put("workflowId", nodo.getWorkflowId());
            mapped.put("name", nodo.getName());
            mapped.put("description", nodo.getDescription());
            mapped.put("order", nodo.getOrder());
            mapped.put("responsibleRole", nodo.getResponsibleRole());
            mapped.put("responsibleDepartmentId", nodo.getResponsibleDepartmentId());
            mapped.put("responsibleDepartmentName", nodo.getResponsibleDepartmentId() != null ? deptNames.get(nodo.getResponsibleDepartmentId()) : null);
            mapped.put("requiresForm", nodo.isRequiresForm());
            mapped.put("avgMinutes", nodo.getAvgMinutes());
            mapped.put("nodeType", nodo.getNodeType());
            mapped.put("posX", nodo.getPosX());
            mapped.put("posY", nodo.getPosY());
            mapped.put("responsibleJobRoleId", nodo.getResponsibleJobRoleId());
            FormDefinition formDefinition = formByNodo.get(nodo.getId());
            if (formDefinition != null) mapped.put("formDefinition", formDefinition);
            return mapped;
        }).toList();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", workflow.getId());
        map.put("name", workflow.getName());
        map.put("description", workflow.getDescription());
        map.put("companyId", workflow.getCompanyId());
        map.put("companyName", company != null ? company.getName() : null);
        map.put("nodo", nodoMapped);
        map.put("transitions", transitions);
        map.put("_count", Map.of("tramites", 0, "nodo", nodos.size()));
        return map;
    }

    private Workflow findWorkflow(String id) {
        return workflowRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow no encontrado"));
    }

    private void validateWorkflowScope(User actor, Workflow workflow) {
        if (actor.getRole() == User.Role.ADMIN || actor.getRole() == User.Role.SUPERADMIN) {
            return;
        }
        if (actor.getCompanyId() == null || !actor.getCompanyId().equals(workflow.getCompanyId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a este workflow");
        }
    }

}
