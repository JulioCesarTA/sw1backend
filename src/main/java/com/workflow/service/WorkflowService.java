package com.workflow.service;

import com.workflow.model.FormDefinition;
import com.workflow.model.Company;
import com.workflow.model.Department;
import com.workflow.model.User;
import com.workflow.model.Workflow;
import com.workflow.model.WorkflowStage;
import com.workflow.model.WorkflowTransition;
import com.workflow.repository.CompanyRepository;
import com.workflow.repository.DepartmentRepository;
import com.workflow.repository.FormDefinitionRepository;
import com.workflow.repository.WorkflowRepository;
import com.workflow.repository.WorkflowStageRepository;
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
    private final WorkflowStageRepository stageRepo;
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
        Map<String, List<WorkflowStage>> stagesByWf = stageRepo.findByWorkflowIdIn(wfIds).stream()
                .collect(Collectors.groupingBy(WorkflowStage::getWorkflowId));
        return workflows.stream().map(wf -> {
            List<WorkflowStage> stages = stagesByWf.getOrDefault(wf.getId(), List.of());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", wf.getId());
            map.put("name", wf.getName());
            map.put("description", wf.getDescription());
            map.put("companyId", wf.getCompanyId());
            map.put("companyName", wf.getCompanyId() != null ? companyNames.get(wf.getCompanyId()) : null);
            map.put("stages", stages);
            map.put("_count", Map.of("procedures", 0, "stages", stages.size()));
            return map;
        }).toList();
    }

    public Map<String, Object> findOne(String id, User actor) {
        Workflow workflow = workflowRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow no encontrado"));
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
        Workflow workflow = workflowRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow no encontrado"));
        validateWorkflowScope(actor, workflow);
        if (actor.getRole() != User.Role.SUPERADMIN) {
            body.put("companyId", actor.getCompanyId());
        }
        if (body.containsKey("name")) workflow.setName((String) body.get("name"));
        if (body.containsKey("description")) workflow.setDescription((String) body.get("description"));
        if (body.containsKey("companyId")) workflow.setCompanyId((String) body.get("companyId"));
        return workflowRepo.save(workflow);
    }

    public void remove(String id, User actor) {
        Workflow workflow = workflowRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow no encontrado"));
        validateWorkflowScope(actor, workflow);
        workflowRepo.deleteById(id);
    }

    public WorkflowStage createStage(Map<String, Object> body) {
        String workflowId = (String) body.get("workflowId");
        if (workflowId == null || workflowId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workflowId es obligatorio");
        }

        WorkflowStage stage = new WorkflowStage();
        applyStageFields(stage, body);

        Integer requestedOrder = extractRequestedOrder(body);
        stage.setOrder(resolveCreateOrder(workflowId, requestedOrder));

        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                WorkflowStage saved = stageRepo.save(stage);
                syncStageFormDefinition(saved, body);
                return saved;
            } catch (DuplicateKeyException ex) {
                stage.setOrder(resolveCreateOrder(workflowId, stage.getOrder() + 1));
            }
        }

        throw new ResponseStatusException(HttpStatus.CONFLICT, "No se pudo asignar un orden disponible para la etapa");
    }

    public List<WorkflowStage> findStagesByWorkflow(String workflowId) {
        return stageRepo.findByWorkflowIdOrderByOrderAsc(workflowId);
    }

    public List<WorkflowStage> findStagesByWorkflow(String workflowId, User actor) {
        return findStagesByWorkflow(workflowId);
    }

    public WorkflowStage findStage(String id) {
        return stageRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Etapa no encontrada"));
    }

    public WorkflowStage updateStage(String id, Map<String, Object> body) {
        WorkflowStage stage = findStage(id);
        applyStageFields(stage, body);
        WorkflowStage saved = stageRepo.save(stage);
        syncStageFormDefinition(saved, body);
        return saved;
    }

    public WorkflowStage updateStage(String id, Map<String, Object> body, User actor) {
        WorkflowStage stage = findStage(id);
        applyStageFields(stage, body);
        WorkflowStage saved = stageRepo.save(stage);
        syncStageFormDefinition(saved, body);
        return saved;
    }

    public void deleteStage(String id) {
        findStage(id);
        formRepo.findByStageId(id).ifPresent(formRepo::delete);
        stageRepo.deleteById(id);
        transitionRepo.deleteByFromStageIdOrToStageId(id, id);
    }

    public WorkflowTransition createTransition(Map<String, Object> body) {
        WorkflowTransition transition = new WorkflowTransition();
        applyTransitionFields(transition, body);
        return transitionRepo.save(transition);
    }

    public WorkflowTransition findTransition(String id) {
        return transitionRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transicion no encontrada"));
    }

    public WorkflowTransition updateTransition(String id, Map<String, Object> body) {
        WorkflowTransition transition = findTransition(id);
        applyTransitionFields(transition, body);
        return transitionRepo.save(transition);
    }

    public WorkflowTransition updateTransition(String id, Map<String, Object> body, User actor) {
        WorkflowTransition transition = findTransition(id);
        applyTransitionFields(transition, body);
        return transitionRepo.save(transition);
    }

    public void deleteTransition(String id) {
        findTransition(id);
        transitionRepo.deleteById(id);
    }

    public List<WorkflowTransition> getTransitions(String workflowId) {
        return transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(workflowId);
    }

    private void applyStageFields(WorkflowStage stage, Map<String, Object> body) {
        if (body.containsKey("workflowId")) stage.setWorkflowId((String) body.get("workflowId"));
        if (body.containsKey("name")) stage.setName((String) body.get("name"));
        if (body.containsKey("description")) stage.setDescription((String) body.get("description"));
        if (stage.getId() != null && body.containsKey("order") && body.get("order") != null) {
            stage.setOrder(((Number) body.get("order")).intValue());
        }
        if (body.containsKey("responsibleRole")) {
            Object responsibleRole = body.get("responsibleRole");
            if (responsibleRole == null || responsibleRole.toString().isBlank()) {
                stage.setResponsibleRole(null);
            } else {
                stage.setResponsibleRole(com.workflow.model.User.Role.valueOf(responsibleRole.toString().toUpperCase()));
            }
        }
        if (body.containsKey("responsibleDepartmentId")) stage.setResponsibleDepartmentId((String) body.get("responsibleDepartmentId"));
        if (body.containsKey("requiresForm")) stage.setRequiresForm(Boolean.TRUE.equals(body.get("requiresForm")));
        if (body.containsKey("slaHours") && body.get("slaHours") != null) {
            stage.setSlaHours(((Number) body.get("slaHours")).intValue());
        }
        if (body.containsKey("nodeType")) stage.setNodeType((String) body.get("nodeType"));
        if (body.containsKey("isConditional")) stage.setConditional(Boolean.TRUE.equals(body.get("isConditional")));
        if (body.containsKey("condition")) stage.setCondition((String) body.get("condition"));
        if (body.containsKey("trueLabel")) stage.setTrueLabel((String) body.get("trueLabel"));
        if (body.containsKey("falseLabel")) stage.setFalseLabel((String) body.get("falseLabel"));
        if (body.containsKey("posX") && body.get("posX") != null) {
            stage.setPosX(((Number) body.get("posX")).doubleValue());
        }
        if (body.containsKey("posY") && body.get("posY") != null) {
            stage.setPosY(((Number) body.get("posY")).doubleValue());
        }
        if (body.containsKey("responsibleJobRoleId")) stage.setResponsibleJobRoleId((String) body.get("responsibleJobRoleId"));
    }

    @SuppressWarnings("unchecked")
    private void syncStageFormDefinition(WorkflowStage stage, Map<String, Object> body) {
        if (stage == null || stage.getId() == null) {
            return;
        }

        boolean isProcessStage = "process".equalsIgnoreCase(stage.getNodeType());
        boolean hasFormPayload = body.containsKey("formDefinition");

        if (!isProcessStage || !stage.isRequiresForm()) {
            formRepo.findByStageId(stage.getId()).ifPresent(formRepo::delete);
            return;
        }

        if (!hasFormPayload) {
            return;
        }

        Object rawFormDefinition = body.get("formDefinition");
        if (!(rawFormDefinition instanceof Map<?, ?> rawFormMap)) {
            formRepo.findByStageId(stage.getId()).ifPresent(formRepo::delete);
            return;
        }

        FormDefinition formDefinition = formRepo.findByStageId(stage.getId()).orElse(new FormDefinition());
        formDefinition.setStageId(stage.getId());
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

            Object options = field.get("options");
            if (options instanceof List<?> optionList) {
                mapped.setOptions(optionList.stream().map(String::valueOf).toList());
            }

            boolean required = Boolean.TRUE.equals(field.get("required")) || Boolean.TRUE.equals(field.get("isRequired"));
            mapped.setRequired(required);

            Object order = field.get("order");
            mapped.setOrder(order instanceof Number number ? number.intValue() : index + 1);
            fields.add(mapped);
        }
        return fields;
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

    private Integer extractRequestedOrder(Map<String, Object> body) {
        Object order = body.get("order");
        if (!(order instanceof Number number)) {
            return null;
        }
        int value = number.intValue();
        return value > 0 ? value : null;
    }

    private int resolveCreateOrder(String workflowId, Integer requestedOrder) {
        List<WorkflowStage> existingStages = stageRepo.findByWorkflowIdOrderByOrderAsc(workflowId);
        Set<Integer> usedOrders = new HashSet<>();
        int maxOrder = 0;

        for (WorkflowStage existingStage : existingStages) {
            usedOrders.add(existingStage.getOrder());
            maxOrder = Math.max(maxOrder, existingStage.getOrder());
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
        if (body.containsKey("fromStageId")) transition.setFromStageId((String) body.get("fromStageId"));
        if (body.containsKey("toStageId")) transition.setToStageId((String) body.get("toStageId"));
        if (body.containsKey("name")) transition.setName((String) body.getOrDefault("name", ""));
        if (body.containsKey("condition")) transition.setCondition((String) body.get("condition"));
        if (body.containsKey("forwardConfig")) {
            transition.setForwardConfig((Map<String, Object>) body.get("forwardConfig"));
        }
    }

    private Map<String, Object> enrichWorkflowFull(Workflow workflow) {
        List<WorkflowStage> stages = stageRepo.findByWorkflowIdOrderByOrderAsc(workflow.getId());
        List<WorkflowTransition> transitions = transitionRepo.findByWorkflowIdOrderByCreatedAtAsc(workflow.getId());
        Company company = workflow.getCompanyId() != null ? companyRepo.findById(workflow.getCompanyId()).orElse(null) : null;

        Set<String> deptIds = stages.stream().map(WorkflowStage::getResponsibleDepartmentId).filter(id -> id != null && !id.isBlank()).collect(Collectors.toSet());
        Map<String, String> deptNames = departmentRepo.findAllById(deptIds).stream().collect(Collectors.toMap(Department::getId, Department::getName));

        Set<String> stageIds = stages.stream().map(WorkflowStage::getId).collect(Collectors.toSet());
        Map<String, FormDefinition> formByStage = formRepo.findByStageIdIn(stageIds).stream().collect(Collectors.toMap(FormDefinition::getStageId, f -> f));

        List<Map<String, Object>> stagesMapped = stages.stream().map(stage -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", stage.getId());
            mapped.put("workflowId", stage.getWorkflowId());
            mapped.put("name", stage.getName());
            mapped.put("description", stage.getDescription());
            mapped.put("order", stage.getOrder());
            mapped.put("responsibleRole", stage.getResponsibleRole());
            mapped.put("responsibleDepartmentId", stage.getResponsibleDepartmentId());
            mapped.put("responsibleDepartmentName", stage.getResponsibleDepartmentId() != null ? deptNames.get(stage.getResponsibleDepartmentId()) : null);
            mapped.put("requiresForm", stage.isRequiresForm());
            mapped.put("slaHours", stage.getSlaHours());
            mapped.put("nodeType", stage.getNodeType());
            mapped.put("isConditional", stage.isConditional());
            mapped.put("condition", stage.getCondition());
            mapped.put("trueLabel", stage.getTrueLabel());
            mapped.put("falseLabel", stage.getFalseLabel());
            mapped.put("posX", stage.getPosX());
            mapped.put("posY", stage.getPosY());
            mapped.put("responsibleJobRoleId", stage.getResponsibleJobRoleId());
            FormDefinition formDefinition = formByStage.get(stage.getId());
            if (formDefinition != null) mapped.put("formDefinition", formDefinition);
            return mapped;
        }).toList();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", workflow.getId());
        map.put("name", workflow.getName());
        map.put("description", workflow.getDescription());
        map.put("companyId", workflow.getCompanyId());
        map.put("companyName", company != null ? company.getName() : null);
        map.put("stages", stagesMapped);
        map.put("transitions", transitions);
        map.put("_count", Map.of("procedures", 0, "stages", stages.size()));
        return map;
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
