package com.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.model.Procedure;
import com.workflow.model.ProcedureHistory;
import com.workflow.repository.ProcedureHistoryRepository;
import com.workflow.repository.ProcedureRepository;
import lombok.RequiredArgsConstructor;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AiService {

    @Value("${app.claude.api-key:}")
    private String claudeApiKey;

    private static final String CLAUDE_URL = "https://api.anthropic.com/v1/messages";
    private static final String DIAGRAM_MODEL = "claude-sonnet-4-6";
    private static final String HAIKU_MODEL   = "claude-haiku-4-5-20251001";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProcedureRepository procedureRepository;
    private final ProcedureHistoryRepository procedureHistoryRepository;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(20))
            .writeTimeout(Duration.ofSeconds(90))
            .readTimeout(Duration.ofSeconds(90))
            .callTimeout(Duration.ofSeconds(120))
            .build();

    private static final String DIAGRAM_SYSTEM_PROMPT = """
            Eres un asistente experto en diseño de flujos de trabajo (workflows).
            El usuario te dará instrucciones en español para modificar el diagrama.
            Responde SIEMPRE con un JSON válido con la siguiente estructura:
            {
              "actions": [ { "type": "create_stage|update_stage|delete_stage|connect_stages|disconnect_stages|show_diagram", ...campos } ],
              "interpretation": "texto explicando qué se hará",
              "affectedNodes": ["id1","id2"],
              "changes": "resumen de cambios"
            }
            Tipos de acción:
            - create_stage: { type, name, description, nodeType, order }
            - update_stage: { type, stageId, name?, description?, slaHours? }
            - delete_stage: { type, stageId }
            - connect_stages: { type, fromStageId, toStageId, name? }
            - disconnect_stages: { type, transitionId }
            - show_diagram: { type } — solo muestra el estado actual
            """;

    private static final String BOTTLENECK_SYSTEM_PROMPT = """
            Eres un experto en optimización de procesos y análisis de flujos de trabajo.
            Recibirás datos de un workflow con métricas de nodos (indegree, outdegree, SLA, etc).
            Identifica cuellos de botella y oportunidades de mejora.
            Debes responder de forma accionable: indica el nodo exacto, por qu?? se congestiona y una soluci??n coherente con el editor de workflows.
            Prioriza recomendaciones como:
            - insertar una decision/condicional para separar casos simples vs complejos
            - usar bifurcacion y union cuando exista trabajo paralelo real
            - redistribuir carga por roles del mismo departamento
            - derivar casos por monto, riesgo, complejidad, tipo de tramite o prioridad
            - reservar el rol principal para casos complejos y enviar casos simples a un rol secundario/auxiliar
            Responde SIEMPRE con JSON:
            {
              "bottlenecks": [
                {
                  "stageId": "id",
                  "stageName": "nombre",
                  "type": "sla_violation|fan_in|role_overload|single_path|parallelization|critical_path",
                  "severity": "high|medium|low",
                  "description": "explicación",
                  "recommendation": "solución propuesta"
                }
              ],
              "summary": "resumen ejecutivo",
              "parallelizationOpportunities": [ { "stageIds": ["id1","id2"], "reason": "..." } ]
            }
            """;

    private static final String WORKFLOW_GENERATION_PROMPT = """
            Eres un experto en diseño de workflows UML con carriles (swimlanes).
            Responde SIEMPRE con un objeto JSON puro, sin bloques markdown, sin texto extra.

            ESTRUCTURA DE RESPUESTA:
            {
              "actions": [...],
              "interpretation": "descripcion breve",
              "affectedNodes": [],
              "changes": "resumen"
            }

            ACCIONES PERMITIDAS:

            create_stage:
            {
              "type": "create_stage",
              "placeholderId": "id_unico_sin_espacios",
              "name": "Nombre del nodo",
              "description": "descripcion opcional",
              "nodeType": "start|end|process|decision|fork|join|loop",
              "order": <numero entero, incremental>,
              "responsibleDepartmentName": "nombre exacto del departamento o null",
              "responsibleJobRoleName": "nombre exacto del rol o null",
              "trueLabel": "etiqueta si es decision o loop (decision: Si/Aprobado/Cumple, loop: Repetir)",
              "falseLabel": "etiqueta si es decision o loop (decision: No/Rechazado/No cumple, loop: Salir)",
              "posX": <numero pixeles>,
              "posY": <numero pixeles>
            }

            connect_stages:
            {
              "type": "connect_stages",
              "fromStageId": "placeholderId o id existente",
              "toStageId": "placeholderId o id existente",
              "name": "etiqueta de la flecha (Si/No para decisiones, vacio para el resto)"
            }

            update_stage: { "type":"update_stage", "stageId":"id", "name":?, "description":?, "slaHours":?, "responsibleDepartmentName":?, "responsibleJobRoleName":? }
            delete_stage:  { "type":"delete_stage", "stageId":"id" }

            TIPOS DE NODO (nodeType):
            - "start"    : nodo de inicio; sin actor; exactamente 1 por workflow
            - "end"      : nodo de fin; puede haber varios
            - "process"  : paso manual realizado por un actor (asigna departamento y rol)
            - "decision" : bifurcacion condicional; EXACTAMENTE 2 conexiones salientes: una con name=trueLabel, otra con name=falseLabel
            - "fork"     : bifurcacion paralela real; EXACTAMENTE 1 entrada y 2 o mas salidas hacia procesos/ramas distintas que se ejecutan simultaneamente; sin actor
            - "join"     : union/sincronizacion paralela real; 2 o mas entradas desde ramas distintas y EXACTAMENTE 1 salida hacia un unico proceso posterior; sin actor; DEBE esperar a TODAS las ramas del fork correspondiente
            - "loop"     : nodo de iteracion/retrabajo; EXACTAMENTE 2 conexiones salientes: una con name=trueLabel para repetir/reintentar/reinspeccionar y otra con name=falseLabel para salir del ciclo y continuar el flujo

            REGLAS CRITICAS:
            1. Cada create_stage DEBE tener un placeholderId unico, sin espacios ni caracteres especiales.
            2. En connect_stages usa SIEMPRE los placeholderIds definidos en create_stage.
            3. Genera PRIMERO todos los create_stage y DESPUES todos los connect_stages.
            4. Para flujo paralelo: predecessor -> fork -> rama1 y predecessor -> fork -> rama2 NO es valido como doble entrada al fork; la forma correcta es predecessor -> fork, luego fork -> rama1 y fork -> rama2; despues rama1 -> join y rama2 -> join; finalmente join -> sucesor.
            4.1. Un fork SOLO puede recibir una conexion entrante.
            4.2. Un fork DEBE repartir el flujo a 2 o mas ramas salientes diferentes.
            4.3. Un join DEBE recibir 2 o mas conexiones entrantes desde ramas paralelas.
            4.4. Un join SOLO puede tener una conexion saliente, hacia un unico proceso o nodo sucesor.
            4.5. NO conectes directamente varios nodos hacia un fork.
            4.6. NO hagas que un join distribuya el flujo a varios nodos; join une, no bifurca.
            4.7. Si no existe paralelismo real, NO uses fork ni join.
            4.8. Si quieres separar caminos alternativos usa decision, no fork.
            4.9. Todo fork debe tener su join de cierre correspondiente, salvo que el usuario pida explicitamente ramas paralelas abiertas.
            5. Para decision: decision -> nodo_si (name=trueLabel), decision -> nodo_no (name=falseLabel).
            5.1. Para loop: etapa_evaluada_o_retrabajo -> loop; loop -> etapa_a_repetir (name=trueLabel, normalmente "Repetir"); loop -> siguiente_etapa_fuera_del_ciclo (name=falseLabel, normalmente "Salir").
            5.2. Usa loop cuando el proceso pueda repetirse hasta cumplir una condicion: observaciones, rechazo tecnico, reinspeccion, retrabajo, correccion, volver a revisar, volver a intentar o repetir una etapa.
            5.3. NO uses decision para modelar retrabajo repetitivo. Usa decision para ramas de negocio alternativas y loop para volver a una etapa previa o de correccion.
            5.4. En nodos loop usa por defecto trueLabel="Repetir" y falseLabel="Salir", salvo que el usuario pida etiquetas equivalentes mas especificas.
            6. Un nodo process NO debe quedar colgado: si una decision o loop apunta a un process, ese process debe continuar hacia otro nodo, loop, join o end.
            6.1. Un process normal NO debe usarse como nodo de union informal de varias ramas. Si dos o mas ramas deben converger, usa join. Si una rama debe volver a una etapa previa para rehacerla, usa loop.
            6.2. Evita conectar dos procesos distintos hacia un mismo process, salvo en un caso de retorno de loop claramente intencional. La convergencia general de varias ramas debe resolverse con join, no con process.
            6.3. Un process normalmente debe tener una sola salida. Si necesitas varias salidas, usa decision o fork segun corresponda.
            6.4. Despues de una decision, cada rama debe terminar en end o reincorporarse correctamente al flujo mediante loop o join; NO dejes ramas muertas.
            7. Usa responsibleDepartmentName y responsibleJobRoleName con el nombre EXACTO del contexto.
            8. NUNCA dejes un nodo sin conexion de entrada (excepto start) ni sin conexion de salida (excepto end).

            LAYOUT SWIMLANE (posX/posY):
            - Asigna una columna por departamento. Separa columnas 300px (col0=50, col1=350, col2=650, col3=950).
            - Apila nodos del mismo departamento verticalmente, separados 180px por fila de flujo (row0=50, row1=230, row2=410, ...).
            - Nodos fork/join: colocalos en la columna del nodo que los antecede o sucede; si cruzan carriles, usa la columna central entre las ramas.
            - El nodo start va en row=50. El nodo end va en la fila mas baja del flujo.
            - Si hay ramas paralelas, ponlas en la misma fila pero en columnas diferentes.
            """;

    private static final String WORKY_ASSISTANT_PROMPT = """
            Eres "Worky", un asistente experto en diagramas de workflow UML con swimlanes.
            Analiza el workflow actual y responde SIEMPRE con JSON puro, sin markdown.

            ESTRUCTURA:
            {
              "assistantName": "Worky",
              "summary": "resumen corto",
              "suggestions": [
                {
                  "id": "missing_start|disconnected_processes|missing_end|decision_missing_branches|missing_join_after_fork|unclear_names|no_closure|next_step_help|too_linear",
                  "message": "mensaje exacto o muy cercano al catalogo",
                  "reason": "explicacion breve",
                  "priority": "high|medium|low",
                  "actions": [
                    { "type": "create_stage|update_stage|delete_stage|connect_stages|disconnect_stages", "...": "..." }
                  ]
                }
              ]
            }

            CATALOGO DE MENSAJES ESPERADOS:
            - "Recuerda que el nodo Inicio debe estar conectado con un proceso. Quieres que te agregue ese proceso?"
            - "Recuerda poner el nodo Fin para concluir tu workflow. Quieres que lo agregue?"
            - "Tu flujo no tiene un nodo de inicio. ¿Deseas que lo agregue automáticamente?"
            - "Detecté procesos sin conexión. ¿Quieres que los conecte para mantener un flujo continuo?"
            - "Tu diagrama no tiene un nodo final. ¿Deseas agregar uno automáticamente?"
            - "Recuerda que una decisión debe tener al menos dos salidas (Sí/No). ¿Quieres que las cree?"
            - "Has creado una bifurcación pero no una unión. ¿Deseas que agregue la unión para cerrar el flujo paralelo?"
            - "Algunos procesos no tienen nombres claros. ¿Deseas que sugiera nombres más descriptivos?"
            - "Tu workflow no tiene un cierre definido. ¿Deseas agregar un nodo de fin?"
            - "¿Necesitas ayuda? Puedo sugerirte el siguiente paso en tu diagrama."
            - "Este flujo tiene muchos pasos lineales. ¿Quieres que sugiera una decisión para hacerlo más dinámico?"

            REGLAS:
            - Comportate como un asistente contextual del editor: observa lo que hay en el canvas y ayuda al usuario a entender el siguiente paso logico del software.
            - Devuelve solo sugerencias RELEVANTES al diagrama actual.
            - No dupliques sugerencias equivalentes.
            - Prioriza problemas estructurales graves: inicio, fin, nodos desconectados, decisiones incompletas, fork sin join.
            - Tambien puedes devolver recordatorios pedagogicos en tiempo real aunque el diagrama aun no este "mal", por ejemplo: Inicio sin proceso siguiente, falta de Fin, ramas incompletas o proceso aislado.
            - Si propones acciones, deben ser ejecutables por el editor actual.
            - Acciones permitidas:
              create_stage:
              {
                "type":"create_stage",
                "placeholderId":"id_unico",
                "name":"Nombre",
                "description":"descripcion opcional",
                "nodeType":"start|end|process|decision|fork|join|loop",
                "order":1,
                "responsibleDepartmentName":"nombre exacto o null",
                "responsibleJobRoleName":"nombre exacto o null",
                "trueLabel":"Si",
                "falseLabel":"No",
                "posX":50,
                "posY":50
              }
              connect_stages: { "type":"connect_stages", "fromStageId":"id_o_placeholder", "toStageId":"id_o_placeholder", "name":"Si|No|..." }
              update_stage: { "type":"update_stage", "stageId":"id", "name":"...", "description":"..." }
              delete_stage: { "type":"delete_stage", "stageId":"id" }
              disconnect_stages: { "type":"disconnect_stages", "transitionId":"id" }
            - Si falta un start, crea exactamente uno y conectalo al primer nodo logico.
            - Si existe un start pero no esta conectado a ningun proceso, sugiere conectar el start al primer process disponible o crear un process inicial.
            - Si falta un end/cierre, crea uno y conecta los nodos hoja.
            - Si una decision no tiene dos salidas, crea salidas "Si" y "No". Si no existen destinos, crea nodos end o process simples.
            - Si detectas retrabajo, reinspeccion, correccion o repeticion hasta cumplir una condicion, sugiere un nodo loop en vez de una decision comun. El loop debe tener 2 salidas: "Repetir" hacia la etapa que se rehace o reevalua y "Salir" hacia la siguiente etapa fuera del ciclo.
            - Usa fork/join SOLO si hay trabajo en paralelo real.
            - En fork debe entrar un solo nodo y salir hacia varias ramas distintas.
            - En join deben entrar varias ramas distintas y salir hacia un solo nodo posterior.
            - No conectes varias ramas directamente a un process comun para "juntarlas"; usa join.
            - No dejes un process creado desde una rama de decision sin continuidad; si notificas, corriges o revisas algo, luego conecta ese process al siguiente paso logico, a un loop o a end.
            - Si hay fork sin join, crea un join y conecta las ramas activas hacia ese join.
            - Si los nombres son vagos ("Etapa 1", "Proceso", "Nodo"), usa update_stage para proponer nombres mas claros.
            - Si el flujo es muy lineal, puedes sugerir insertar un nodo decision, pero solo si tiene sentido.
            """;


    @SuppressWarnings("unchecked")
    public Map<String, Object> processDiagramCommand(Map<String, Object> body) {
        String command = (String) body.get("command");
        List<Map<String, Object>> stages = (List<Map<String, Object>>) body.getOrDefault("stages", List.of());
        List<Map<String, Object>> transitions = (List<Map<String, Object>>) body.getOrDefault("transitions", List.of());
        List<Map<String, Object>> history = (List<Map<String, Object>>) body.getOrDefault("history", List.of());
        List<Map<String, Object>> departments = (List<Map<String, Object>>) body.getOrDefault("departments", List.of());
        List<Map<String, Object>> jobRoles = (List<Map<String, Object>>) body.getOrDefault("jobRoles", List.of());
        String workflowName = (String) body.getOrDefault("workflowName", "");

        String context = "=== CONTEXTO DEL WORKFLOW ===\n"
                + "Nombre: " + workflowName + "\n"
                + "Departamentos disponibles (usa el 'name' exacto en responsibleDepartmentName):\n"
                + toJson(departments) + "\n"
                + "Roles disponibles por departamento (usa el 'name' exacto en responsibleJobRoleName):\n"
                + toJson(jobRoles) + "\n"
                + "Etapas actuales del diagrama:\n"
                + toJson(stages) + "\n"
                + "Transiciones actuales:\n"
                + toJson(transitions);

        List<Map<String, Object>> messages = new ArrayList<>();
        int start = Math.max(0, history.size() - 6);
        for (int i = start; i < history.size(); i++) {
            messages.add(history.get(i));
        }
        messages.add(Map.of("role", "user", "content", command + "\n\n" + context));

        String response = callClaude(WORKFLOW_GENERATION_PROMPT, DIAGRAM_MODEL, 8192, messages);
        return parseJsonResponse(response);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyzeBottlenecks(Map<String, Object> body) {
        List<Map<String, Object>> stages = (List<Map<String, Object>>) body.getOrDefault("stages", List.of());
        List<Map<String, Object>> transitions = (List<Map<String, Object>>) body.getOrDefault("transitions", List.of());
        String workflowId = Objects.toString(body.getOrDefault("workflowId", ""), "");
        Map<String, Double> averageHoursByStage = computeAverageHoursByStage(workflowId, stages);
        Map<String, Object> localAnalysis = buildLocalBottleneckAnalysis(workflowId, stages, transitions, averageHoursByStage);
        List<Map<String, Object>> enriched = (List<Map<String, Object>>) localAnalysis.getOrDefault("enrichedStages", List.of());

        String prompt = "Analiza este workflow:\nEtapas enriquecidas: " + toJson(enriched)
                + "\nTransiciones: " + toJson(transitions)
                + "\nAnalisis heuristico base: " + toJson(localAnalysis)
                + "\nPrioriza detectar nodos lentos con muchas entradas, cuellos despues de bifurcaciones y nodos lentos compartidos por todos los caminos.";
        try {
            String response = callClaude(BOTTLENECK_SYSTEM_PROMPT, HAIKU_MODEL, 4096, List.of(Map.of("role", "user", "content", prompt)));
            Map<String, Object> parsed = parseJsonResponse(response);
            return mergeBottleneckAnalysis(localAnalysis, parsed);
        } catch (Exception ignored) {
            return stripEnrichedStages(localAnalysis);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyzeWorkyAssistant(Map<String, Object> body) {
        List<Map<String, Object>> stages = (List<Map<String, Object>>) body.getOrDefault("stages", List.of());
        List<Map<String, Object>> transitions = (List<Map<String, Object>>) body.getOrDefault("transitions", List.of());
        List<Map<String, Object>> departments = (List<Map<String, Object>>) body.getOrDefault("departments", List.of());
        List<Map<String, Object>> jobRoles = (List<Map<String, Object>>) body.getOrDefault("jobRoles", List.of());
        String workflowName = (String) body.getOrDefault("workflowName", "");

        String context = "=== WORKFLOW ===\n"
                + "Nombre: " + workflowName + "\n"
                + "Departamentos:\n" + toJson(departments) + "\n"
                + "Roles:\n" + toJson(jobRoles) + "\n"
                + "Etapas:\n" + toJson(stages) + "\n"
                + "Transiciones:\n" + toJson(transitions) + "\n";

        try {
            String response = callClaude(
                    WORKY_ASSISTANT_PROMPT,
                    HAIKU_MODEL,
                    4096,
                    List.of(Map.of("role", "user", "content", context))
            );
            Map<String, Object> parsed = parseJsonResponse(response);
            return normalizeWorkySuggestions(parsed);
        } catch (Exception ignored) {
            return buildLocalWorkySuggestions(workflowName, stages, transitions);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeWorkySuggestions(Map<String, Object> parsed) {
        List<Map<String, Object>> rawSuggestions = (List<Map<String, Object>>) parsed.getOrDefault("suggestions", List.of());
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> suggestions = new ArrayList<>();

        for (Map<String, Object> suggestion : rawSuggestions) {
            String id = Objects.toString(suggestion.get("id"), "").trim();
            if (id.isBlank() || !seen.add(id)) continue;
            suggestions.add(suggestion);
        }

        return Map.of(
                "assistantName", Objects.toString(parsed.getOrDefault("assistantName", "Worky")),
                "summary", Objects.toString(parsed.getOrDefault("summary", "Worky revisó tu diagrama.")),
                "suggestions", suggestions
        );
    }

    private Map<String, Object> buildLocalWorkySuggestions(String workflowName,
                                                           List<Map<String, Object>> stages,
                                                           List<Map<String, Object>> transitions) {
        List<Map<String, Object>> suggestions = new ArrayList<>();

        Map<String, Long> indegree = new HashMap<>();
        Map<String, Long> outdegree = new HashMap<>();
        Map<String, Map<String, Object>> stagesById = new LinkedHashMap<>();
        for (Map<String, Object> stage : stages) {
            stagesById.put(Objects.toString(stage.get("id"), ""), stage);
        }
        for (Map<String, Object> t : transitions) {
            String from = Objects.toString(t.get("fromStageId"), "");
            String to = Objects.toString(t.get("toStageId"), "");
            if (!from.isBlank()) outdegree.merge(from, 1L, Long::sum);
            if (!to.isBlank()) indegree.merge(to, 1L, Long::sum);
        }

        List<Map<String, Object>> startNodes = stages.stream().filter(s -> "start".equalsIgnoreCase(Objects.toString(s.get("nodeType"), ""))).toList();
        List<Map<String, Object>> endNodes = stages.stream().filter(s -> "end".equalsIgnoreCase(Objects.toString(s.get("nodeType"), ""))).toList();
        List<Map<String, Object>> rootNodes = stages.stream()
                .filter(s -> !"start".equalsIgnoreCase(Objects.toString(s.get("nodeType"), "")))
                .filter(s -> indegree.getOrDefault(Objects.toString(s.get("id"), ""), 0L) == 0L)
                .sorted(Comparator.comparingInt(s -> ((Number) s.getOrDefault("order", 0)).intValue()))
                .toList();
        List<Map<String, Object>> leafNodes = stages.stream()
                .filter(s -> !"end".equalsIgnoreCase(Objects.toString(s.get("nodeType"), "")))
                .filter(s -> outdegree.getOrDefault(Objects.toString(s.get("id"), ""), 0L) == 0L)
                .toList();

        if (startNodes.isEmpty()) {
            List<Map<String, Object>> actions = new ArrayList<>();
            actions.add(workyAction(
                    "type", "create_stage",
                    "placeholderId", "worky_start",
                    "name", "Inicio",
                    "description", "Nodo de inicio agregado por Worky",
                    "nodeType", "start",
                    "order", 1,
                    "responsibleDepartmentName", null,
                    "responsibleJobRoleName", null,
                    "posX", 50,
                    "posY", 50
            ));
            if (!rootNodes.isEmpty()) {
                actions.add(Map.of(
                        "type", "connect_stages",
                        "fromStageId", "worky_start",
                        "toStageId", Objects.toString(rootNodes.get(0).get("id"), ""),
                        "name", ""
                ));
            }
            suggestions.add(Map.of(
                    "id", "missing_start",
                    "message", "Tu flujo no tiene un nodo de inicio. ¿Deseas que lo agregue automáticamente?",
                    "reason", "El diagrama necesita un punto claro de entrada para iniciar el flujo.",
                    "priority", "high",
                    "actions", actions
            ));
        }

        if (!startNodes.isEmpty()) {
            Map<String, Object> start = startNodes.get(0);
            String startId = Objects.toString(start.get("id"), "");
            if (outdegree.getOrDefault(startId, 0L) == 0L) {
                List<Map<String, Object>> actions = new ArrayList<>();
                if (!rootNodes.isEmpty()) {
                    actions.add(Map.of(
                            "type", "connect_stages",
                            "fromStageId", startId,
                            "toStageId", Objects.toString(rootNodes.get(0).get("id"), ""),
                            "name", ""
                    ));
                }
                suggestions.add(Map.of(
                        "id", "start_needs_process",
                        "message", "Recuerda que el nodo Inicio debe estar conectado con un proceso. Quieres que te agregue ese proceso?",
                        "reason", "El nodo de inicio debe conducir al primer paso operativo del workflow.",
                        "priority", "high",
                        "actions", actions
                ));
            }
        }

        if (endNodes.isEmpty() && !leafNodes.isEmpty()) {
            List<Map<String, Object>> actions = new ArrayList<>();
            actions.add(workyAction(
                    "type", "create_stage",
                    "placeholderId", "worky_end",
                    "name", "Fin",
                    "description", "Nodo final agregado por Worky",
                    "nodeType", "end",
                    "order", stages.size() + 1,
                    "responsibleDepartmentName", null,
                    "responsibleJobRoleName", null,
                    "posX", 950,
                    "posY", 650
            ));
            for (Map<String, Object> leaf : leafNodes) {
                actions.add(Map.of(
                        "type", "connect_stages",
                        "fromStageId", Objects.toString(leaf.get("id"), ""),
                        "toStageId", "worky_end",
                        "name", ""
                ));
            }
            suggestions.add(Map.of(
                    "id", "missing_end",
                    "message", "Tu diagrama no tiene un nodo final. ¿Deseas agregar uno automáticamente?",
                    "reason", "Sin un nodo final, el flujo queda abierto y no expresa un cierre claro.",
                    "priority", "high",
                    "actions", actions
            ));
        }

        List<Map<String, Object>> disconnected = stages.stream()
                .filter(s -> !"start".equalsIgnoreCase(Objects.toString(s.get("nodeType"), "")))
                .filter(s -> !"end".equalsIgnoreCase(Objects.toString(s.get("nodeType"), "")))
                .filter(s -> indegree.getOrDefault(Objects.toString(s.get("id"), ""), 0L) == 0L
                        && outdegree.getOrDefault(Objects.toString(s.get("id"), ""), 0L) == 0L)
                .sorted(Comparator.comparingInt(s -> ((Number) s.getOrDefault("order", 0)).intValue()))
                .toList();
        if (!disconnected.isEmpty() && disconnected.size() >= 2) {
            List<Map<String, Object>> actions = new ArrayList<>();
            for (int i = 0; i < disconnected.size() - 1; i++) {
                actions.add(Map.of(
                        "type", "connect_stages",
                        "fromStageId", Objects.toString(disconnected.get(i).get("id"), ""),
                        "toStageId", Objects.toString(disconnected.get(i + 1).get("id"), ""),
                        "name", ""
                ));
            }
            suggestions.add(Map.of(
                    "id", "disconnected_processes",
                    "message", "Detecté procesos sin conexión. ¿Quieres que los conecte para mantener un flujo continuo?",
                    "reason", "Hay nodos aislados que no participan en un flujo continuo.",
                    "priority", "high",
                    "actions", actions
            ));
        }

        for (Map<String, Object> stage : stages) {
            String nodeType = Objects.toString(stage.get("nodeType"), "");
            String stageId = Objects.toString(stage.get("id"), "");
            if ("decision".equalsIgnoreCase(nodeType) && outdegree.getOrDefault(stageId, 0L) < 2L) {
                List<Map<String, Object>> actions = new ArrayList<>();
                actions.add(workyAction(
                        "type", "create_stage",
                        "placeholderId", "worky_yes_" + stageId,
                        "name", "Resultado Si",
                        "description", "Salida sugerida por Worky",
                        "nodeType", "end",
                        "order", stages.size() + 1,
                        "responsibleDepartmentName", null,
                        "responsibleJobRoleName", null,
                        "posX", 650,
                        "posY", 400
                ));
                actions.add(workyAction(
                        "type", "create_stage",
                        "placeholderId", "worky_no_" + stageId,
                        "name", "Resultado No",
                        "description", "Salida sugerida por Worky",
                        "nodeType", "end",
                        "order", stages.size() + 2,
                        "responsibleDepartmentName", null,
                        "responsibleJobRoleName", null,
                        "posX", 950,
                        "posY", 400
                ));
                actions.add(Map.of("type", "connect_stages", "fromStageId", stageId, "toStageId", "worky_yes_" + stageId, "name", "Si"));
                actions.add(Map.of("type", "connect_stages", "fromStageId", stageId, "toStageId", "worky_no_" + stageId, "name", "No"));
                suggestions.add(Map.of(
                        "id", "decision_missing_branches",
                        "message", "Recuerda que una decisión debe tener al menos dos salidas (Sí/No). ¿Quieres que las cree?",
                        "reason", "Las decisiones incompletas rompen la lógica condicional del workflow.",
                        "priority", "high",
                        "actions", actions
                ));
                break;
            }
        }

        for (Map<String, Object> stage : stages) {
            String nodeType = Objects.toString(stage.get("nodeType"), "");
            String stageId = Objects.toString(stage.get("id"), "");
            if ("fork".equalsIgnoreCase(nodeType)) {
                long branchCount = outdegree.getOrDefault(stageId, 0L);
                boolean hasJoin = stages.stream().anyMatch(s -> "join".equalsIgnoreCase(Objects.toString(s.get("nodeType"), "")));
                if (branchCount >= 2 && !hasJoin) {
                    List<Map<String, Object>> actions = new ArrayList<>();
                    actions.add(workyAction(
                            "type", "create_stage",
                            "placeholderId", "worky_join",
                            "name", "Union paralela",
                            "description", "Nodo join agregado por Worky",
                            "nodeType", "join",
                            "order", stages.size() + 1,
                            "responsibleDepartmentName", null,
                            "responsibleJobRoleName", null,
                            "posX", 650,
                            "posY", 560
                    ));
                    List<String> branchTargets = transitions.stream()
                            .filter(t -> stageId.equals(Objects.toString(t.get("fromStageId"), "")))
                            .map(t -> Objects.toString(t.get("toStageId"), ""))
                            .toList();
                    for (String branchTarget : branchTargets) {
                        actions.add(Map.of(
                                "type", "connect_stages",
                                "fromStageId", branchTarget,
                                "toStageId", "worky_join",
                                "name", ""
                        ));
                    }
                    suggestions.add(Map.of(
                            "id", "missing_join_after_fork",
                            "message", "Has creado una bifurcación pero no una unión. ¿Deseas que agregue la unión para cerrar el flujo paralelo?",
                            "reason", "Las ramas paralelas deben sincronizarse antes de continuar el flujo.",
                            "priority", "high",
                            "actions", actions
                    ));
                }
                break;
            }
        }

        List<Map<String, Object>> unclearNames = stages.stream()
                .filter(s -> "process".equalsIgnoreCase(Objects.toString(s.get("nodeType"), "")))
                .filter(s -> {
                    String name = Objects.toString(s.get("name"), "").trim().toLowerCase(Locale.ROOT);
                    return name.matches("^(etapa|proceso|nodo)\\s*\\d*$") || name.length() <= 4;
                })
                .toList();
        if (!unclearNames.isEmpty()) {
            List<Map<String, Object>> actions = new ArrayList<>();
            int index = 1;
            for (Map<String, Object> unclear : unclearNames) {
                actions.add(Map.of(
                        "type", "update_stage",
                        "stageId", Objects.toString(unclear.get("id"), ""),
                        "name", "Proceso " + index,
                        "description", "Nombre sugerido por Worky"
                ));
                index += 1;
            }
            suggestions.add(Map.of(
                    "id", "unclear_names",
                    "message", "Algunos procesos no tienen nombres claros. ¿Deseas que sugiera nombres más descriptivos?",
                    "reason", "Nombres genéricos dificultan entender el workflow.",
                    "priority", "medium",
                    "actions", actions
            ));
        }

        long processCount = stages.stream().filter(s -> "process".equalsIgnoreCase(Objects.toString(s.get("nodeType"), ""))).count();
        long decisionCount = stages.stream().filter(s -> "decision".equalsIgnoreCase(Objects.toString(s.get("nodeType"), ""))).count();
        if (processCount >= 4 && decisionCount == 0) {
            suggestions.add(Map.of(
                    "id", "too_linear",
                    "message", "Este flujo tiene muchos pasos lineales. ¿Quieres que sugiera una decisión para hacerlo más dinámico?",
                    "reason", "Un flujo demasiado lineal puede necesitar puntos de evaluación o bifurcación.",
                    "priority", "low",
                    "actions", List.of()
            ));
        }

        if (suggestions.isEmpty()) {
            suggestions.add(Map.of(
                    "id", "next_step_help",
                    "message", "¿Necesitas ayuda? Puedo sugerirte el siguiente paso en tu diagrama.",
                    "reason", "El diagrama no presenta errores estructurales graves en este momento.",
                    "priority", "low",
                    "actions", List.of()
            ));
        }

        return Map.of(
                "assistantName", "Worky",
                "summary", "Worky encontró oportunidades para mejorar tu workflow \"" + workflowName + "\".",
                "suggestions", suggestions
        );
    }

    private Map<String, Object> workyAction(Object... keyValues) {
        Map<String, Object> action = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            String key = Objects.toString(keyValues[i], null);
            if (key == null) continue;
            action.put(key, keyValues[i + 1]);
        }
        return action;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeBottleneckAnalysis(Map<String, Object> localAnalysis, Map<String, Object> parsed) {
        Map<String, Map<String, Object>> localByStage = new LinkedHashMap<>();
        for (Map<String, Object> bottleneck : (List<Map<String, Object>>) localAnalysis.getOrDefault("bottlenecks", List.of())) {
            localByStage.put(Objects.toString(bottleneck.get("stageId"), ""), bottleneck);
        }

        List<Map<String, Object>> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Map<String, Object> bottleneck : (List<Map<String, Object>>) parsed.getOrDefault("bottlenecks", List.of())) {
            String stageId = Objects.toString(bottleneck.get("stageId"), "");
            Map<String, Object> local = localByStage.get(stageId);
            Map<String, Object> normalized = new LinkedHashMap<>();
            if (local != null) normalized.putAll(local);
            normalized.putAll(bottleneck);
            if (!stageId.isBlank() && seen.add(stageId)) merged.add(normalized);
        }

        for (Map<String, Object> bottleneck : (List<Map<String, Object>>) localAnalysis.getOrDefault("bottlenecks", List.of())) {
            String stageId = Objects.toString(bottleneck.get("stageId"), "");
            if (!stageId.isBlank() && seen.add(stageId)) merged.add(bottleneck);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", Objects.toString(parsed.getOrDefault("summary", localAnalysis.get("summary")), ""));
        result.put("workflowAverageHours", localAnalysis.getOrDefault("workflowAverageHours", 0));
        result.put("workflowExpectedHours", localAnalysis.getOrDefault("workflowExpectedHours", 0));
        result.put("sampleProcedures", localAnalysis.getOrDefault("sampleProcedures", 0));
        result.put("bottlenecks", merged);
        result.put("parallelizationOpportunities", parsed.getOrDefault(
                "parallelizationOpportunities",
                localAnalysis.getOrDefault("parallelizationOpportunities", List.of())
        ));
        return result;
    }

    private Map<String, Object> stripEnrichedStages(Map<String, Object> analysis) {
        Map<String, Object> result = new LinkedHashMap<>(analysis);
        result.remove("enrichedStages");
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildLocalBottleneckAnalysis(String workflowId,
                                                             List<Map<String, Object>> stages,
                                                             List<Map<String, Object>> transitions,
                                                             Map<String, Double> averageHoursByStage) {
        Map<String, Map<String, Object>> stagesById = new LinkedHashMap<>();
        Map<String, Long> indegree = new HashMap<>();
        Map<String, Long> outdegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        Map<String, List<String>> reverseAdjacency = new HashMap<>();

        for (Map<String, Object> stage : stages) {
            String id = Objects.toString(stage.get("id"), "");
            if (id.isBlank()) continue;
            stagesById.put(id, stage);
            adjacency.put(id, new ArrayList<>());
            reverseAdjacency.put(id, new ArrayList<>());
        }

        for (Map<String, Object> transition : transitions) {
            String from = Objects.toString(transition.get("fromStageId"), "");
            String to = Objects.toString(transition.get("toStageId"), "");
            if (!from.isBlank()) outdegree.merge(from, 1L, Long::sum);
            if (!to.isBlank()) indegree.merge(to, 1L, Long::sum);
            if (!from.isBlank() && !to.isBlank()) {
                adjacency.computeIfAbsent(from, key -> new ArrayList<>()).add(to);
                reverseAdjacency.computeIfAbsent(to, key -> new ArrayList<>()).add(from);
            }
        }

        List<String> roots = stages.stream()
                .map(stage -> Objects.toString(stage.get("id"), ""))
                .filter(id -> !id.isBlank())
                .filter(id -> indegree.getOrDefault(id, 0L) == 0L)
                .toList();
        List<String> leaves = stages.stream()
                .map(stage -> Objects.toString(stage.get("id"), ""))
                .filter(id -> !id.isBlank())
                .filter(id -> outdegree.getOrDefault(id, 0L) == 0L)
                .toList();

        List<List<String>> allPaths = new ArrayList<>();
        for (String root : roots) {
            collectPaths(root, adjacency, new LinkedHashSet<>(), new ArrayList<>(), allPaths, leaves);
        }

        Map<String, Long> pathCoverage = new HashMap<>();
        for (List<String> path : allPaths) {
            for (String stageId : new LinkedHashSet<>(path)) {
                pathCoverage.merge(stageId, 1L, Long::sum);
            }
        }

        List<Map<String, Object>> enrichedStages = new ArrayList<>();
        List<Map<String, Object>> bottlenecks = new ArrayList<>();
        Set<String> added = new LinkedHashSet<>();

        for (Map<String, Object> stage : stages) {
            String stageId = Objects.toString(stage.get("id"), "");
            String nodeType = Objects.toString(stage.get("nodeType"), "");
            String departmentName = Objects.toString(stage.get("responsibleDepartmentName"), "");
            String roleName = Objects.toString(stage.get("responsibleJobRoleName"), "");
            double averageHours = averageHoursByStage.getOrDefault(
                    stageId,
                    ((Number) stage.getOrDefault("slaHours", 24)).doubleValue()
            );
            long in = indegree.getOrDefault(stageId, 0L);
            long out = outdegree.getOrDefault(stageId, 0L);
            long coverage = pathCoverage.getOrDefault(stageId, 0L);

            Map<String, Object> enriched = new LinkedHashMap<>(stage);
            enriched.put("averageHours", roundHours(averageHours));
            enriched.put("expectedHours", ((Number) stage.getOrDefault("slaHours", 24)).doubleValue());
            enriched.put("indegree", in);
            enriched.put("outdegree", out);
            enriched.put("pathCoverage", coverage);
            enriched.put("pathCoverageRatio", allPaths.isEmpty() ? 0D : (double) coverage / allPaths.size());
            enrichedStages.add(enriched);

            if ("process".equalsIgnoreCase(nodeType) || "decision".equalsIgnoreCase(nodeType) || "join".equalsIgnoreCase(nodeType)) {
                if (averageHours >= 24 && in >= 2) {
                    addBottleneck(bottlenecks, added, Map.of(
                            "stageId", stageId,
                            "stageName", Objects.toString(stage.get("name"), "Nodo"),
                            "type", "fan_in",
                            "severity", averageHours >= 36 ? "high" : "medium",
                            "averageHours", roundHours(averageHours),
                            "expectedHours", ((Number) stage.getOrDefault("slaHours", 24)).doubleValue(),
                            "indegree", in,
                            "outdegree", out,
                            "description", "El nodo acumula muchas entradas y un tiempo promedio alto, por lo que puede congestionar el flujo.",
                            "recommendation", buildFanInRecommendation(stage, departmentName, roleName)
                    ));
                }

                if (!allPaths.isEmpty() && coverage == allPaths.size() && averageHours >= 24) {
                    addBottleneck(bottlenecks, added, Map.of(
                            "stageId", stageId,
                            "stageName", Objects.toString(stage.get("name"), "Nodo"),
                            "type", "critical_path",
                            "severity", averageHours >= 36 ? "high" : "medium",
                            "averageHours", roundHours(averageHours),
                            "expectedHours", ((Number) stage.getOrDefault("slaHours", 24)).doubleValue(),
                            "indegree", in,
                            "outdegree", out,
                            "description", "Todos los caminos del workflow pasan por este nodo y su tiempo promedio es alto.",
                            "recommendation", buildCriticalPathRecommendation(stage, departmentName, roleName)
                    ));
                }

                if ("process".equalsIgnoreCase(nodeType) && averageHours >= 24 && coverage >= Math.max(1, allPaths.size() / 2)) {
                    addBottleneck(bottlenecks, added, Map.of(
                            "stageId", stageId,
                            "stageName", Objects.toString(stage.get("name"), "Nodo"),
                            "type", "role_overload",
                            "severity", averageHours >= 36 ? "high" : "medium",
                            "averageHours", roundHours(averageHours),
                            "expectedHours", ((Number) stage.getOrDefault("slaHours", 24)).doubleValue(),
                            "indegree", in,
                            "outdegree", out,
                            "description", "El mismo rol o responsable concentra gran parte del trabajo del workflow y tarda mas de lo esperado.",
                            "recommendation", buildRoleOverloadRecommendation(stage, departmentName, roleName)
                    ));
                }
            }
        }

        for (Map<String, Object> stage : stages) {
            String stageId = Objects.toString(stage.get("id"), "");
            String nodeType = Objects.toString(stage.get("nodeType"), "");
            if (!"fork".equalsIgnoreCase(nodeType)) continue;

            List<String> branchTargets = adjacency.getOrDefault(stageId, List.of());
            if (branchTargets.size() < 2) continue;

            Map<String, Integer> convergenceCount = new HashMap<>();
            for (String branchTarget : branchTargets) {
                for (String next : adjacency.getOrDefault(branchTarget, List.of())) {
                    convergenceCount.merge(next, 1, Integer::sum);
                }
            }

            for (Map.Entry<String, Integer> entry : convergenceCount.entrySet()) {
                if (entry.getValue() < 2) continue;
                Map<String, Object> convergedStage = stagesById.get(entry.getKey());
                if (convergedStage == null) continue;
                double averageHours = averageHoursByStage.getOrDefault(
                        entry.getKey(),
                        ((Number) convergedStage.getOrDefault("slaHours", 24)).doubleValue()
                );
                addBottleneck(bottlenecks, added, Map.of(
                        "stageId", entry.getKey(),
                        "stageName", Objects.toString(convergedStage.get("name"), "Nodo"),
                        "type", "parallelization",
                        "severity", averageHours >= 24 ? "high" : "medium",
                        "averageHours", roundHours(averageHours),
                        "expectedHours", ((Number) convergedStage.getOrDefault("slaHours", 24)).doubleValue(),
                        "indegree", indegree.getOrDefault(entry.getKey(), 0L),
                        "outdegree", outdegree.getOrDefault(entry.getKey(), 0L),
                        "description", "El flujo se bifurca, pero las ramas vuelven muy rapido al mismo nodo, por lo que casi no se aprovecha la paralelizacion.",
                        "recommendation", "Mueve validaciones posteriores a cada rama, agrega una union explicita y evita reconverger tan pronto en el mismo responsable."
                ));
            }
        }

        List<Map<String, Object>> opportunities = new ArrayList<>();
        for (Map<String, Object> stage : stages) {
            String stageId = Objects.toString(stage.get("id"), "");
            if (!"fork".equalsIgnoreCase(Objects.toString(stage.get("nodeType"), ""))) continue;
            List<String> branchTargets = adjacency.getOrDefault(stageId, List.of());
            if (branchTargets.size() >= 2) {
                opportunities.add(Map.of(
                        "stageIds", branchTargets,
                        "reason", "La bifurcacion desde \"" + Objects.toString(stage.get("name"), "Fork") + "\" puede redistribuir mejor carga si cada rama resuelve mas trabajo antes de reconverger."
                ));
            }
        }

        for (Map<String, Object> bottleneck : bottlenecks) {
            String type = Objects.toString(bottleneck.get("type"), "");
            if ("role_overload".equalsIgnoreCase(type) || "critical_path".equalsIgnoreCase(type) || "fan_in".equalsIgnoreCase(type)) {
                opportunities.add(Map.of(
                        "stageIds", List.of(Objects.toString(bottleneck.get("stageId"), "")),
                        "reason", Objects.toString(bottleneck.get("recommendation"), "")
                ));
            }
        }

        double workflowAverageHours = averageHoursByStage.values().stream().mapToDouble(Double::doubleValue).sum();
        double workflowExpectedHours = stages.stream()
                .mapToDouble(stage -> ((Number) stage.getOrDefault("slaHours", 24)).doubleValue())
                .sum();

        String summary = bottlenecks.isEmpty()
                ? "No se detectaron cuellos de botella fuertes con las metricas actuales del workflow."
                : "Se detectaron " + bottlenecks.size() + " posibles cuellos de botella con base en tiempos promedio, convergencia, sobrecarga por rol y estructura del grafo. Revisa primero los nodos de severidad alta y las recomendaciones de derivacion por condicion o reparto por roles.";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("workflowAverageHours", roundHours(workflowAverageHours));
        result.put("workflowExpectedHours", roundHours(workflowExpectedHours));
        result.put("sampleProcedures", workflowId.isBlank() ? 0 : procedureRepository.findByWorkflowId(workflowId).size());
        result.put("bottlenecks", bottlenecks);
        result.put("parallelizationOpportunities", opportunities);
        result.put("enrichedStages", enrichedStages);
        return result;
    }

    private void addBottleneck(List<Map<String, Object>> bottlenecks,
                               Set<String> added,
                               Map<String, Object> bottleneck) {
        String key = Objects.toString(bottleneck.get("stageId"), "") + "|" + Objects.toString(bottleneck.get("type"), "");
        if (added.add(key)) bottlenecks.add(bottleneck);
    }

    private String buildFanInRecommendation(Map<String, Object> stage, String departmentName, String roleName) {
        String stageName = Objects.toString(stage.get("name"), "este nodo");
        String owner = ownerLabel(departmentName, roleName);
        if (!owner.isBlank()) {
            return "Agrega una decision antes de \"" + stageName + "\" para separar casos simples y complejos. "
                    + "Los casos de menor monto, riesgo o complejidad pueden derivarse a un rol secundario dentro de "
                    + owner + ", mientras que los complejos quedan en el responsable principal. "
                    + "Si hay tareas independientes, considera una bifurcacion para repartir validaciones previas y luego una union.";
        }
        return "Agrega una decision antes de \"" + stageName + "\" para separar casos simples y complejos, o crea una prevalidacion para filtrar tramites antes de llegar a este cuello de botella.";
    }

    private String buildCriticalPathRecommendation(Map<String, Object> stage, String departmentName, String roleName) {
        String stageName = Objects.toString(stage.get("name"), "este nodo");
        String owner = ownerLabel(departmentName, roleName);
        if (!owner.isBlank()) {
            return "Todos los tramites pasan por \"" + stageName + "\". Conviene insertar una decision previa que derive por monto, riesgo, prioridad o complejidad. "
                    + "Por ejemplo, los casos simples pueden ir a un rol secundario o auxiliar en " + owner
                    + " y los casos complejos quedar en el rol principal. Si parte del trabajo puede hacerse en paralelo, abre una bifurcacion y reconcilia despues con una union.";
        }
        return "Todos los tramites pasan por \"" + stageName + "\". Inserta una decision previa para derivar casos simples y complejos por reglas de negocio, y usa paralelizacion solo si existen tareas independientes.";
    }

    private String buildRoleOverloadRecommendation(Map<String, Object> stage, String departmentName, String roleName) {
        String stageName = Objects.toString(stage.get("name"), "este nodo");
        if (!departmentName.isBlank() && !roleName.isBlank()) {
            return "El rol \"" + roleName + "\" en " + departmentName + " concentra demasiado trabajo en \"" + stageName + "\". "
                    + "Considera repartir la carga con una decision previa: casos de bajo monto, bajo riesgo o baja complejidad hacia un rol secundario/auxiliar, y casos complejos hacia el rol principal.";
        }
        if (!departmentName.isBlank()) {
            return "Este nodo sobrecarga al departamento " + departmentName + ". Considera separar el flujo con una decision por monto, complejidad o prioridad y asignar roles distintos para absorber la demanda.";
        }
        return "Este nodo concentra demasiada carga en un mismo responsable. Considera separar casos simples y complejos con una decision previa o agregar un segundo rol para descongestionar.";
    }

    private String ownerLabel(String departmentName, String roleName) {
        if (!departmentName.isBlank() && !roleName.isBlank()) {
            return "el departamento " + departmentName + " (" + roleName + ")";
        }
        if (!departmentName.isBlank()) {
            return "el departamento " + departmentName;
        }
        if (!roleName.isBlank()) {
            return "el rol " + roleName;
        }
        return "";
    }

    private void collectPaths(String current,
                              Map<String, List<String>> adjacency,
                              Set<String> visited,
                              List<String> path,
                              List<List<String>> allPaths,
                              List<String> leaves) {
        if (!visited.add(current)) return;
        path.add(current);

        List<String> nextNodes = adjacency.getOrDefault(current, List.of());
        if (nextNodes.isEmpty() || leaves.contains(current)) {
            allPaths.add(new ArrayList<>(path));
        } else {
            for (String next : nextNodes) {
                collectPaths(next, adjacency, new LinkedHashSet<>(visited), new ArrayList<>(path), allPaths, leaves);
            }
        }
    }

    private Map<String, Double> computeAverageHoursByStage(String workflowId, List<Map<String, Object>> stages) {
        if (workflowId == null || workflowId.isBlank()) {
            return Map.of();
        }

        Set<String> stageIds = stages.stream()
                .map(stage -> Objects.toString(stage.get("id"), ""))
                .filter(id -> !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        if (stageIds.isEmpty()) {
            return Map.of();
        }

        Map<String, List<Double>> durationsByStage = new LinkedHashMap<>();
        for (Procedure procedure : procedureRepository.findByWorkflowId(workflowId)) {
            for (ProcedureHistory history : procedureHistoryRepository.findByProcedureIdOrderByChangedAtAsc(procedure.getId())) {
                String stageId = Objects.toString(history.getToStageId(), "");
                if (stageId.isBlank() || !stageIds.contains(stageId)) continue;

                double durationHours = history.getDurationInStage() != null
                        ? history.getDurationInStage().doubleValue()
                        : 0D;
                if (durationHours <= 0D) continue;

                durationsByStage.computeIfAbsent(stageId, key -> new ArrayList<>()).add(durationHours);
            }
        }

        Map<String, Double> averages = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> entry : durationsByStage.entrySet()) {
            double average = entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0D);
            averages.put(entry.getKey(), roundHours(average));
        }
        return averages;
    }

    private double roundHours(double value) {
        return Math.round(value * 10D) / 10D;
    }

    private String callClaude(String systemPrompt, String model, int maxTokens, List<Map<String, Object>> messages) {
        if (claudeApiKey == null || claudeApiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "API key de Claude no configurada");
        }
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "system", systemPrompt,
                    "messages", messages
            );
            String json = objectMapper.writeValueAsString(requestBody);
            Request request = new Request.Builder()
                    .url(CLAUDE_URL)
                    .post(RequestBody.create(json, MediaType.get("application/json")))
                    .addHeader("x-api-key", claudeApiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    if (response.code() == 402 || body.contains("credit")) {
                        throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Sin créditos en la API de Claude");
                    }
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Error de la API de Claude: " + body);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = objectMapper.readValue(body, Map.class);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> content = (List<Map<String, Object>>) resp.get("content");
                return (String) content.get(0).get("text");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error llamando a Claude: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonResponse(String text) {
        try {
            String cleaned = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            return objectMapper.readValue(cleaned, Map.class);
        } catch (Exception e) {
            return Map.of("interpretation", text, "actions", List.of(), "affectedNodes", List.of(), "changes", "");
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
