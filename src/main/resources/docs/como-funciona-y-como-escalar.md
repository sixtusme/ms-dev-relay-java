# Cómo funciona sixai, paso a paso, y cómo escalarlo

Este documento sigue una tarea real de Jira desde que aparece hasta que llega a producción, citando
el código real de cada paso. El objetivo es que un desarrollador (solo o con ayuda de una IA) pueda
entender el sistema completo sin tener que leer los 27→12 paquetes a ciegas, y sepa dónde y cómo
añadir cosas nuevas sin romper lo que ya funciona.

## Vista de pájaro

```text
Jira (comentario "/sixai ...")
      │
      ▼
intake/          — ¿es esto una tarea/orden que nos interesa?
      │  IssueEligibleEvent
      ▼
delivery/        — responder, abrir PRs, generar código, verificar que compila
      │               (usa ai/ para razonar: RouterAgent, SelectorAgent, CoderAgent)
      ▼
control/         — un humano aprueba, corrige o promociona a producción
      │
      ▼
deploy/          — build → imagen → despliegue, en PRE o en PROD
      │
      ▼
panel/           — todo lo anterior, consultable desde el front (/sixai/**)
```

Dos paquetes son transversales y no aparecen en el diagrama porque los usa todo el mundo:
`activity/` (audita cada paso) y `guardrail/` (protege lo que entra y sale del LLM).

---

## Paso 1 — Intake: decidir si una tarea nos interesa

Dos entradas posibles, mismo destino. El webhook (`api/WebhooksApiDelegateImpl`) y el polling
(`intake/JiraPollingScheduler`) terminan los dos en `IssueTriggerServiceImpl.process(...)`:

```java
// intake/IssueTriggerServiceImpl.java
@Async(AsyncConfig.JIRA_TASK_EXECUTOR)
@Override
public void process(JiraIssueDto issue, TriggerSource source) {
    if (!eligibilityFilter.isAssigneeAllowed(issue)) { ... return; }
    if (commandProperties.isEnabled() && isCommandStatus(issue)) {
        commandService.handle(issue);   // tarea YA en curso: esto es un comando, no un arranque
        return;
    }
    if (!eligibilityFilter.isStatusAllowed(issue)) { ... return; }
    if (eligibilityFilter.isAlreadyProcessed(issue)) { ... return; }

    Optional<EligibilityResult> eligibility = eligibilityFilter.evaluate(issue, comments);
    if (eligibility.isEmpty() || !processedIssuesTracker.markIfNew(issueKey)) { return; }

    eventPublisher.publishEvent(new IssueEligibleEvent(issueKey, ..., source, Instant.now()));
}
```

`JiraIssueEligibilityFilter` (misma carpeta) es lógica pura sin I/O: asignado permitido, estado
permitido, comentario con la palabra clave. `ProcessedIssuesTracker` es la idempotencia persistida
(nunca se re-arranca la misma orden dos veces, ni tras un reinicio).

**Qué hace único a este paso:** publica un evento de Spring (`IssueEligibleEvent`) y no sabe ni le
importa quién lo escucha. Eso es lo que permite que `delivery/` reaccione sin que `intake/` tenga
que conocerlo.

## Paso 2 — Delivery: responder, generar código, verificar

`delivery/responder/IssueResponder` escucha el evento:

```java
@EventListener
public void onIssueEligible(final IssueEligibleEvent event) {
    taskRecorder.start(issueKey, ...);           // abre la auditoría
    String replyText = buildReplyText(issueKey); // LLM o texto fijo, según maestro.llm.enabled
    publishReply(issueKey, replyText, event);
    moveToInProgress(issueKey);
    markProcessed(issueKey);
    pullRequestService.openForIssue(issueKey);   // aquí arranca lo interesante
}
```

`delivery/pullrequest/PullRequestService.openForIssue(...)` es el orquestador de "producir la PR":

```java
List<GithubIntegrationProperties.Repo> candidates = repoResolver.resolveCandidates(issue); // ¿qué sistema es?
List<String> repos = selectorAgent.select(issue, candidates);                              // ¿qué repos tocar?
for (String repo : repos) {
    githubClient.createBranch(repo, branch, ...);
    boolean coded = codeWithAgent(issueKey, repo, branch, summary, description);           // el coder
    if (!coded) { githubClient.putFile(..., placeholder, ...); }                           // fallback honesto
    GithubClient.PullRequest pr = githubClient.createPullRequest(...);
    verificationService.verify(issueKey, repo, branch, pr.number());                       // ¿compila?
}
```

`codeWithAgent` es el puente hacia `ai/`:

```java
private boolean codeWithAgent(String issueKey, String repo, String branch, String summary, String description) {
    AgentExecutionResult result = agentRuntime.execute(new AgentExecutionRequest(
        issueKey, summary, description, CoderAgent.ID,
        Map.of("repo", repo, "branch", branch)));
    return result.status() == AgentStatus.COMPLETED
        && result.summary() != null && !result.summary().isBlank();
}
```

`RepoResolver` (keywords → sistema) y `SelectorAgent` (LLM o keywords → repos concretos) son lógica
de negocio de `delivery/`; `CoderAgent` es donde vive `ai/` — ver la sección "El motor de agentes"
más abajo para lo que pasa dentro de esa llamada.

## Paso 3 — Control: un humano decide

Todo lo que pasa a partir de aquí depende de un comentario `/sixai` de una persona autorizada, nunca
de lo que "decida" un modelo por su cuenta.

```java
// control/command/CommandService.java
private void handleOne(final JiraIssueDto issue, final SixaiCommand command) {
    CommandIntent intent = routerAgent.route(command.issueKey(), command.issueStatus(), command.instruction());
    switch (intent) {
        case PROMOTE_TO_PROD -> handlePromote(issue, command);      // control/approval/PromotionService
        case REVISE -> correctionService.requestedByHuman(...);      // control/correction/CorrectionService
        case STATUS -> reply(command, statusReport(...));
        // REDEPLOY, CANCEL, UNKNOWN: se anota y se responde con honestidad
    }
}
```

La autorización de `PROMOTE_TO_PROD` **no depende del LLM ni del texto del comentario**: depende de
la identidad de quien lo escribió, comprobada contra Jira:

```java
private boolean isAuthorizedToPromote(final JiraIssueDto issue, final JiraUserDto author) {
    if (sameUser(author, fields.getReporter()) || sameUser(author, fields.getAssignee())) return true;
    return matchesAny(author, properties.getPromoteAuthorized());  // lista extra de config
}
```

Esta es la frontera de seguridad real del sistema — más importante que cualquier guardarraíl de
prompt (ver más abajo). Nunca se debe mover esta comprobación a "lo que dice el texto" o "lo que
decide el modelo".

`control/approval/ApprovalService` mergea las PRs a `develop` y arranca el despliegue a PRE.
`control/approval/PromotionService` hace lo mismo de `develop` a `main`/`master` para PROD, cuando
`/sixai PROD` ya viene autorizado. `control/correction/CorrectionService` reabre PRs nuevas con lo
que hay que arreglar, con un tope de ciclos (`maestro.correction.max-cycles`) que, al agotarse,
**para y escala a una persona** en vez de seguir insistiendo.

## Paso 4 — Deploy: build, imagen, despliegue

`deploy/DeploymentOrchestrator.sweep()` (`@Scheduled`) avanza cada despliegue un paso por barrido:
`BUILD_QUEUED → BUILD_RUNNING → DEPLOY_QUEUED → DEPLOY_RUNNING`. Cuando uno termina, avisa a
`deploy/DeploymentCoordinator.onRunFinished(run)`, que solo actúa cuando **todos** los repos del
lote han terminado:

```java
if (failed.isEmpty()) {
    complete(batch);   // PRE: reasigna al informador y mueve a TEST · PROD: comenta y cierra la tarea
} else {
    abort(batch, failed);
    if (batch.getPhase() == DeploymentPhase.PRE) {
        correctionService.triggeredByFailure(batch.getIssueKey(), detail);  // auto-corrección, solo en PRE
    }
}
```

Un fallo en PRODUCCIÓN **nunca** se auto-corrige — esa decisión es siempre de una persona.
`ai/agent/impl/DiagnosticianAgent` entra aquí para explicar POR QUÉ falló un build o un despliegue
(mira Harbor y el contenedor destino vía tools de solo lectura), no para arreglarlo.

## Paso 5 — Panel: consultarlo todo

`api/SixaiController` es la única puerta HTTP del panel (`/sixai/**`); delega en los servicios de
`panel/*` (`monitor` para "qué está pasando ahora", `session` para "qué PRs hay abiertas", `report`
para los informes en FTP/SFTP, `chat` e `insight` para preguntar en lenguaje natural). Ninguno de
estos servicios ejecuta acciones — son de solo lectura por diseño, igual que `DiagnosticianAgent`.

---

## El motor de agentes (`ai/`)

Cada vez que algo necesita razonar con un LLM, pasa por aquí. La pieza central es el loop de
`ai/orchestration/DefaultAgentRuntime`:

```java
private AgentExecutionResult run(final Agent agent, final AgentContext context, final int stepsLeft) {
    AgentResult result = agent.execute(context);   // el agente llama al LLM DENTRO de este método
    return switch (result.status()) {
        case COMPLETED, FAILED, NEEDS_INPUT -> new AgentExecutionResult(...);
        case WAITING_FOR_TOOL -> { applyToolActions(agent, context, result.actions()); yield run(agent, context, stepsLeft - 1); }
        case WAITING_FOR_AGENT -> run(resolveDelegate(result.actions()), context, stepsLeft - 1);
        case CONTINUE -> run(agent, context, stepsLeft - 1);
    };
}
```

Reglas fijas de esta pieza (no negociables al extenderla):

- **El runtime nunca llama al LLM.** Solo orquesta: ejecuta las tools que el agente pide y le
  devuelve el resultado. Cada `Agent` llama a `LlmClient` dentro de su propio `execute(...)`.
- **Toda tool pasa por `ToolGuardrail` antes de ejecutarse**, si alguno aplica:

  ```java
  for (final ToolGuardrail guardrail : guardrails) {
      if (!guardrail.appliesTo(tool)) continue;
      ToolGuardrail.Outcome outcome = guardrail.check(toolContext, arguments);
      if (outcome.denied()) { return outcome.denial(); }   // la tool NI se ejecuta
      arguments = outcome.arguments();                      // puede venir filtrada (menos ficheros, etc.)
  }
  ```

  Hoy solo hay un guardarraíl (`ai/tool/impl/guardrail/ChangeSetToolGuardrail`, que reutiliza
  `guardrail/ChangeSetGuard` para bloquear escrituras en rutas protegidas: `.github/workflows`,
  claves, etc.), aplicado a `github.commit`. Cualquier tool de escritura que se añada en el futuro
  puede protegerse igual, sin tocar el agente que la usa.
- **Las tools son el único punto de contacto con sistemas externos.** Ningún `Agent` importa
  `GithubClient`, `JiraClient`, `HarborClient`, etc. directamente — los envuelve una clase en
  `ai/tool/impl/<sistema>/` con un nivel de riesgo (`ToolRisk.READ` / `WRITE` / ...).
- **Las skills son markdown, no Java.** El prompt de sistema de cada agente vive en
  `resources/ai/skills/*.md` y se carga por id; el código Java siempre lleva un fallback hardcodeado
  por si el fichero no está, pero el fichero es la fuente de verdad operativa.

---

## Guardarraíles: qué protege qué (y qué NO)

| Guardarraíl | Dónde vive | Qué protege | Cómo se aplica |
|---|---|---|---|
| `SecretRedactor` | `guardrail/` | que no salgan tokens/contraseñas hacia el LLM ni en su respuesta | global, dentro de `GuardedLlmClient` (decorador exterior de `LlmClient`) |
| `PromptShield` | `guardrail/` | marcar texto externo (Jira, logs) como DATO y no como orden | por-agente, quien construye el prompt decide envolver el texto (hoy: `CoderAgent`) |
| `ChangeSetGuard` / `ChangeSetToolGuardrail` | `guardrail/` + `ai/tool/impl/guardrail/` | que no se escriban rutas protegidas ni ficheros enormes | genérico, vía el pipeline de tools del `AgentRuntime` — protege a CUALQUIER agente que use `github.commit`, no solo al coder |
| **Identidad Jira** (`isAuthorizedToPromote`, checks en `ApprovalService`) | `control/` | quién puede aprobar/promocionar/desplegar | **esta es la que de verdad importa** — nunca depende del LLM ni de lo que diga el texto |

Si alguna vez tienes que elegir entre "un guardarraíl de prompt más fino" y "una comprobación de
identidad más estricta", la segunda es la que sostiene el edificio.

---

## Cómo escalar esto (tú + una IA)

### Añadir una tool nueva (acceso de solo lectura o escritura a un sistema externo)

1. Implementa `Tool` en `ai/tool/impl/<sistema>/`, con `risk()` correcto (`READ` si no muta nada).
2. Anótala `@Component` — Spring la registra sola en `InMemoryToolRegistry` (inyecta `List<Tool>`).
3. Si escribe algo sensible, escribe también un `ToolGuardrail` que `appliesTo(tool)` esa tool
   concreta — no metas la validación dentro del propio agente que la usa.

### Añadir un agente nuevo

1. Implementa `Agent` en `ai/agent/impl/`. Si necesita tools, resuélvelas por nombre desde
   `context.availableTools()`/`ToolRegistry`, nunca importando el cliente del sistema externo.
2. Si el flujo es de un solo turno (clasificar, elegir), sigue el patrón de `RouterAgent`/
   `SelectorAgent`: un método público tipado + `execute(AgentContext)` genérico de apoyo.
3. Si el flujo necesita tools de escritura, sigue el patrón de `CoderAgent`: máquina de estados que
   avanza en cada llamada, dejando que el `AgentRuntime` ejecute las tools (así los guardarraíles se
   aplican de verdad, en vez de que el agente se las salte llamándolas directamente).
4. Escribe su prompt de sistema en `resources/ai/skills/<algo>.md`, no como constante Java.

### Añadir un dominio de negocio nuevo (paquete de primer nivel)

Antes de crear uno, relee `docs/arquitectura-paquetes.md`. Si lo que añades es una variante de un
dominio existente (otra forma de corregir, otra consulta del panel), va DENTRO de ese paquete. Si es
una capacidad realmente nueva (por ejemplo, un `ReviewerAgent` con su propio ciclo de vida), sí
merece paquete propio — pero sigue el mismo patrón: nombre de negocio, no técnico.

### Trabajando con un agente de código (Claude Code u otro) en este repo

- Dale el paquete concreto donde va el cambio, no "todo el proyecto": el contexto se gasta rápido y
  la estructura por dominio existe justo para poder acotar así.
- Para cambios que tocan varios ficheros a la vez (mover algo de paquete, renombrar), pide que
  verifique con `grep` las referencias ANTES y DESPUÉS del cambio — es la única forma de tener
  confianza sin poder compilar en el entorno del agente.
- Nunca le pidas que relaje `ChangeSetGuard`, `SecretRedactor` o las comprobaciones de identidad de
  `control/` "para que funcione" — si algo choca con un guardarraíl, el guardarraíl tiene razón y el
  cambio hay que replantearlo, no saltárselo.
- Los cambios grandes (reestructurar paquetes, tocar la lib compartida) mejor por fases pequeñas,
  cada una compilable y revertible por separado — así se ha hecho toda esta migración.
