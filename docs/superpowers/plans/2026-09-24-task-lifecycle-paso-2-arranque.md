# Ciclo de vida de tareas — Paso 2: Arranque del flujo · Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que las tres primeras fases (`INTAKE`, `REPO_SELECTION`, `IMPLEMENTATION`) entreguen su resultado al `TaskLifecycle`, con el porqué que hoy se pierde (cómo se eligieron los repos, por qué el coder no dejó código, el plan y la revisión).

**Architecture:** Los servicios devuelven `PhaseOutcome` y no tocan el estado. `CoderAgent` explica su resultado en `data.outcome` sin cambiar su `status` ni su resumen. `SelectorAgent.select` devuelve también el método. `PullRequestService.openForIssue` devuelve la lista ordenada de resultados, e `IssueResponder` (el punto de entrada) se la entrega al lifecycle junto con el de `INTAKE`.

**Tech Stack:** Java 17+ · Spring Boot · Lombok.

**Spec:** `docs/superpowers/specs/2026-09-24-task-lifecycle-design.md` (§4.2, §4.3, §4.4 filas INTAKE / REPO_SELECTION / IMPLEMENTATION, §3.4; paso 2 de §10). Se apoya en el paso 1 (`control/lifecycle/`).

## Global Constraints

- Repo `ms-dev-relay-java`, rama `feature/agents-mcp-config`. **No** tocar `C:\Users\sixtusme\Dev\proyectos\sixai\`.
- **No se escribe código de testing en ningún momento.**
- **No se compila** (`mvn` prohibido): lo compila el usuario. La verificación es lectura cuidadosa + comprobación manual del usuario.
- Sin commits salvo que el usuario lo pida.
- `maestro.lifecycle.enforce=false`: nada de este paso puede cambiar el comportamiento visible (comentarios en Jira, PRs, placeholder, informe, verificación).
- El criterio de "hay código" de `PullRequestService` **no cambia**: `COMPLETED` + resumen no vacío.

## Decisiones de implementación (a validar en la revisión)

1. **`CoderAgent.Outcome` añade `TRUNCATED`, `LLM_ERROR` y `GUARD_REJECTED`** a los de la spec (`CODED`, `DISABLED`, `NO_CHANGES`, `DRY_RUN`, `COMMIT_FAILED`). Hoy un modelo sin tokens o caído se confunde con "sin cambios", y un commit denegado por `ChangeSetToolGuardrail` con un fallo de GitHub; separarlos es justo el porqué que la spec quiere recuperar.
2. **GitHub apagado → `openForIssue` devuelve lista vacía**: no se ha hecho nada, así que no hay resultado que entregar; la tarea queda en `REPO_SELECTION` en curso (hoy tampoco avanza).
3. **Jira sin issue / sin candidatos / sin selección → `REPO_SELECTION` `FAIL`**, lo que termina la tarea en `FAILED` (decisión 3 del paso 1). Hoy la tarea se queda viva para siempre sin que nadie lo sepa.
4. **Dry-run de GitHub**: cada repo `SKIPPED`, y la fase agregada `SKIPPED` si ningún repo tuvo código. Así la simulación avanza a `VERIFICATION` en vez de dejar `IMPLEMENTATION` en `FAILED`.
5. **Una PR de placeholder lleva evidencia `PR`** aunque el repo quede en `FAIL`: la verificación la compila igual, así que cuenta como repo esperado en la agregación de `VERIFICATION` (paso 3).
6. **`CorrectionService` ignora de momento lo que devuelve `openForIssue`**: sus resultados solo tienen sentido tras el `reroute`, que llega en el paso 4.

## Review Focus

- **Una excepción inesperada dentro de `openForIssue`** (Jira caído a mitad): el resultado va a `REPO_SELECTION` si aún no hubo selección, y a `IMPLEMENTATION` si ya la hubo; nunca se relanza.
- **Tarea reprocesada ya en curso** (`TaskRecorder.start` devuelve la viva): el `INTAKE PASS` llega tarde → el lifecycle lo guarda como evidencia y no mueve la tarea.
- **Evidencias largas** (respuesta del LLM, plan, revisión): se truncan a 2000 en `PhaseEvidence`; no rompen nada.
- **Coder que falla por límite de pasos del runtime** (`FAILED`, sin `data`): el motivo se toma del `status` + `summary`.
- **Revisión automática fallida**: `reviewWithAgent` devuelve `null` y no se añade evidencia `REVIEW`; el comentario de Jira sigue igual que hoy.

---

### Task 1: `CoderAgent` explica su resultado

**Files:**
- Modify: `src/main/java/es/colorbaby/microservices/dev/relay/ai/agent/impl/CoderAgent.java`

**Interfaces:**
- Produces: `CoderAgent.DATA_OUTCOME = "outcome"`; `enum CoderAgent.Outcome { CODED, DISABLED, NO_CHANGES, DRY_RUN, TRUNCATED, LLM_ERROR, GUARD_REJECTED, COMMIT_FAILED }`; `data.get(DATA_OUTCOME)` = `Outcome.name()` en todo resultado `COMPLETED`.

- [ ] **Step 1: Constantes y enum** — después de `public static final String ID = "coder";` añadir:

```java

  /** Clave de {@code data} con el motivo del resultado ({@link Outcome#name()}). */
  public static final String DATA_OUTCOME = "outcome";

  /**
   * Por qué terminó el coder como terminó. Sin esto, "apagado", "sin cambios", "dry-run", "sin
   * tokens" o "commit rechazado" llegaban todos como un resumen vacío y se perdía el motivo.
   */
  public enum Outcome {
    CODED,
    DISABLED,
    NO_CHANGES,
    DRY_RUN,
    TRUNCATED,
    LLM_ERROR,
    GUARD_REJECTED,
    COMMIT_FAILED
  }
```

- [ ] **Step 2: Apagado** — en `execute`, sustituir `return new AgentResult(AgentStatus.COMPLETED, "", List.of());` (el del `if (!properties.isEnabled() || !llmProperties.isEnabled())`) por `return finished(Outcome.DISABLED);`.

- [ ] **Step 3: Generación** — en `generateAndRequestCommit`, sustituir:

```java
    final ChangeSet changeSet = safeCall(
        () -> generateChanges(context, task, tree, readContext), EMPTY_CHANGE_SET, context.issueKey());

    final String repo = requireState(context, "repo");
    if (changeSet.isEmpty()) {
      log.info("El coder no propuso cambios aplicables para {} en {}", context.issueKey(), repo);
      return new AgentResult(AgentStatus.COMPLETED, "", List.of());
    }
    if (properties.isDryRun()) {
      log.info("[DRY-RUN] El coder cambiaría en {}: {}", repo, describePaths(changeSet));
      return new AgentResult(AgentStatus.COMPLETED, "", List.of());
    }
```

por:

```java
    final ChangeSet changeSet;
    try {
      changeSet = generateChanges(context, task, tree, readContext);
    } catch (LlmTruncatedException e) {
      // El modelo SÍ estaba escribiendo la solución y se le acabó el techo: no es "sin cambios".
      log.error("El coder se quedó sin tokens en {}: {}", context.issueKey(), e.getMessage());
      return finished(Outcome.TRUNCATED);
    } catch (RuntimeException e) {
      log.warn("El coder falló en {}: {}", context.issueKey(), e.getMessage());
      return finished(Outcome.LLM_ERROR);
    }

    final String repo = requireState(context, "repo");
    if (changeSet.isEmpty()) {
      log.info("El coder no propuso cambios aplicables para {} en {}", context.issueKey(), repo);
      return finished(Outcome.NO_CHANGES);
    }
    if (properties.isDryRun()) {
      log.info("[DRY-RUN] El coder cambiaría en {}: {}", repo, describePaths(changeSet));
      return finished(Outcome.DRY_RUN);
    }
```

y borrar la constante `EMPTY_CHANGE_SET` (queda sin uso; `safeCall` sigue usándose en `planReads`).

- [ ] **Step 4: Commit** — en `finalizeFromCommit`, sustituir:

```java
    if (!success) {
      log.warn("El commit del coder no se aplicó en {}: {}", context.issueKey(), message.content());
      return new AgentResult(AgentStatus.COMPLETED, "", List.of());
    }
```

por:

```java
    if (!success) {
      log.warn("El commit del coder no se aplicó en {}: {}", context.issueKey(), message.content());
      // El runtime describe el resultado de la tool como "<STATUS>: <contenido>"; DENIED es el
      // guardarraíl, cualquier otro fallo es de GitHub.
      final boolean denied = message.content() != null
          && message.content().startsWith(ToolStatus.DENIED.name() + ": ");
      return finished(denied ? Outcome.GUARD_REJECTED : Outcome.COMMIT_FAILED);
    }
```

y la última línea del método:

```java
    return new AgentResult(AgentStatus.COMPLETED, text, List.of(), Map.of("changedFiles", changedFiles));
```

por:

```java
    return new AgentResult(AgentStatus.COMPLETED, text, List.of(),
        Map.of("changedFiles", changedFiles, DATA_OUTCOME, Outcome.CODED.name()));
```

- [ ] **Step 5: Helper** — antes de `safeCall`, añadir:

```java
  /**
   * Termina sin código, diciendo por qué. El resumen vacío se mantiene a propósito: es el criterio
   * con el que el llamante decide poner el placeholder.
   */
  private static AgentResult finished(final Outcome outcome) {
    return new AgentResult(AgentStatus.COMPLETED, "", List.of(),
        Map.of(DATA_OUTCOME, outcome.name()));
  }
```

- [ ] **Step 6: Lectura de verificación** — `grep -n "EMPTY_CHANGE_SET\|new AgentResult(AgentStatus.COMPLETED, \"\"" CoderAgent.java` no debe devolver nada; `ToolStatus`, `LlmTruncatedException` y `Map` ya están importados.

---

### Task 2: `SelectorAgent` dice cómo eligió

**Files:**
- Modify: `src/main/java/es/colorbaby/microservices/dev/relay/ai/agent/impl/SelectorAgent.java`

**Interfaces:**
- Produces: `enum SelectorAgent.Method { NONE, SINGLE_CANDIDATE, LLM, KEYWORDS, ALL_FALLBACK }`; `record SelectorAgent.Selection(List<String> repos, Method method)`; `Selection select(JiraIssueDto, List<Repo>)` (antes `List<String>`). Único llamante: `PullRequestService` (Task 3).

- [ ] **Step 1: Tipos** — antes de `private final LlmProperties llmProperties;` añadir:

```java
  /** Cómo se eligieron los repos: explica por qué se abrió PR (o no) en cada uno. */
  public enum Method {
    NONE,
    SINGLE_CANDIDATE,
    LLM,
    KEYWORDS,
    ALL_FALLBACK
  }

  /** Repos donde abrir PR y cómo se eligieron. */
  public record Selection(List<String> repos, Method method) {
  }

```

- [ ] **Step 2: `select`** — sustituir el método completo por:

```java
  /** Repos donde abrir PR y cómo se eligieron. Vacío solo si no había candidatos. */
  public Selection select(final JiraIssueDto issue, final List<Repo> candidates) {
    if (candidates.isEmpty()) {
      return new Selection(List.of(), Method.NONE);
    }
    if (candidates.size() == 1) {
      return new Selection(List.of(candidates.get(0).getName()), Method.SINGLE_CANDIDATE);
    }

    if (llmProperties.isEnabled()) {
      final List<String> byLlm = selectWithLlm(issue, candidates);
      if (!byLlm.isEmpty()) {
        return new Selection(byLlm, Method.LLM);
      }
      log.warn("El LLM no acotó repos para {}; uso el fallback por keywords", issue.getKey());
    }

    final List<String> byKeywords = selectWithKeywords(issue, candidates);
    if (!byKeywords.isEmpty()) {
      return new Selection(byKeywords, Method.KEYWORDS);
    }

    log.warn("Sin señales para acotar repos de {}; abro PR en todos los candidatos", issue.getKey());
    return new Selection(candidates.stream().map(Repo::getName).toList(), Method.ALL_FALLBACK);
  }
```

---

### Task 3: `PullRequestService` devuelve los resultados

**Files:**
- Modify: `src/main/java/es/colorbaby/microservices/dev/relay/delivery/pullrequest/PullRequestService.java`

**Interfaces:**
- Consumes: Task 1 (`CoderAgent.DATA_OUTCOME`), Task 2 (`SelectorAgent.Selection`), paso 1 (`PhaseOutcome`, `Evidence`, `EvidenceKind`, `Recommendation`, `TaskPhase`).
- Produces: `List<PhaseOutcome> openForIssue(String issueKey)` y `List<PhaseOutcome> openForIssue(String issueKey, String correction)` — orden: `REPO_SELECTION`, un `IMPLEMENTATION` por repo, `IMPLEMENTATION` agregado.

- [ ] **Step 1: Imports** — añadir:

```java
import es.colorbaby.microservices.dev.relay.control.lifecycle.Evidence;
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceKind;
import es.colorbaby.microservices.dev.relay.control.lifecycle.PhaseOutcome;
import es.colorbaby.microservices.dev.relay.control.lifecycle.Recommendation;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskPhase;
```

- [ ] **Step 2: `openForIssue`** — sustituir los dos métodos `openForIssue` por:

```java
  /** Abre las PRs de una issue ya puesta en curso. Best-effort: nunca relanza. */
  public List<PhaseOutcome> openForIssue(final String issueKey) {
    return openForIssue(issueKey, null);
  }

  /**
   * Abre las PRs de una issue. Con {@code correction} se trata de un ciclo de corrección: se
   * abren PRs NUEVAS (las anteriores ya se mergearon a develop) con lo que hay que arreglar.
   *
   * @param correction qué hay que corregir, o null si es el primer arranque
   * @return resultados de REPO_SELECTION e IMPLEMENTATION, en el orden en que hay que
   *     entregárselos al ciclo de vida. Vacío si la integración con GitHub está apagada: no se ha
   *     hecho nada, así que no hay nada que contar.
   */
  public List<PhaseOutcome> openForIssue(final String issueKey, final String correction) {
    if (!properties.isEnabled()) {
      return List.of();
    }
    final List<PhaseOutcome> outcomes = new ArrayList<>();
    try {
      JiraIssueDto issue = jiraClient.getIssue(issueKey);
      if (issue == null) {
        outcomes.add(selectionFailed("no se pudo leer la tarea de Jira"));
        return outcomes;
      }
      List<GithubIntegrationProperties.Repo> candidates = repoResolver.resolveCandidates(issue);
      if (candidates.isEmpty()) {
        log.info("Sin repos mapeados para {} (revisa maestro.github.projects)", issueKey);
        outcomes.add(selectionFailed(
            "sin repos mapeados para su sistema (revisa maestro.github.projects)"));
        return outcomes;
      }
      final SelectorAgent.Selection selection = selectorAgent.select(issue, candidates);
      final List<String> repos = selection.repos();
      if (repos.isEmpty()) {
        log.info("Ningún repo seleccionado para {} entre los candidatos", issueKey);
        outcomes.add(selectionFailed("ningún repo seleccionado entre los candidatos"));
        return outcomes;
      }
      outcomes.add(PhaseOutcome.of(TaskPhase.REPO_SELECTION, Recommendation.PASS,
          Evidence.of(EvidenceKind.DECISION, "Repos elegidos (" + selection.method() + "): "
              + String.join(", ", repos))));

      JiraIssueDtoFields fields = issue.getFields();
      String summary = fields == null || fields.getSummary() == null ? issueKey : fields.getSummary();
      String description = fields == null
          ? "" : JiraTextExtractor.extractPlainText(fields.getDescription());

      // Aquí ya se ha leído la issue de Jira: se aprovecha para completar el registro sin gastar
      // otra llamada a la API.
      taskRecorder.describe(issueKey, summary, epicOf(fields), systemOf(candidates));

      if (correction == null) {
        // Solo en el primer arranque: se crea la carpeta y se copian los adjuntos de quien creó la
        // tarea. En una corrección la carpeta ya existe y volver a copiarlos sería ruido.
        reportService.prepareFolder(issue);
      }

      // En una corrección, al coder se le da la descripción original MÁS lo que hay que arreglar:
      // sin el original perdería el contexto de qué se pedía.
      final String brief = correction == null ? description
          : description + "\n\n## Corrección solicitada\n" + correction;

      final List<String> coded =
          openPullRequests(issueKey, summary, brief, repos, correction, outcomes);

      // El informe se publica cuando el coder ya ha resuelto: antes no habría nada que contar.
      if (!coded.isEmpty()) {
        reportService.publishReport(issue,
            deliveryReport(issueKey, summary, brief, coded));
      }
    } catch (RuntimeException e) {
      log.error("Error abriendo PRs para {}: {}", issueKey, e.getMessage());
      // Si aún no se habían elegido repos, lo que falló fue la selección; si no, la implementación.
      final String reason = "error abriendo PRs: " + e.getMessage();
      outcomes.add(outcomes.isEmpty() ? selectionFailed(reason)
          : PhaseOutcome.of(TaskPhase.IMPLEMENTATION, Recommendation.FAIL,
              Evidence.of(EvidenceKind.REASON, reason)));
    }
    return outcomes;
  }
```

- [ ] **Step 3: `openPullRequests`** — sustituir el método completo por:

```java
  /**
   * Abre las PRs y devuelve las líneas de resumen de aquellas en las que el coder SÍ escribió
   * código: son las que dan contenido al informe (si no hay ninguna, no hay nada que informar).
   * Deja en {@code outcomes} un resultado de IMPLEMENTATION por repo y, al final, el agregado.
   */
  private List<String> openPullRequests(
      final String issueKey, final String summary, final String description,
      final List<String> repos, final String correction, final List<PhaseOutcome> outcomes) {

    String title = (correction == null ? "sixai · " : "sixai (corrección) · ")
        + issueKey + " · " + summary;
    String body = prBody(issueKey, summary, description);
    String placeholder = placeholderFile(issueKey, summary, description);

    List<String> codedRepos = new ArrayList<>();
    List<String> links = new ArrayList<>();
    for (String repo : repos) {
      String branch = properties.getBranchPrefix() + issueKey + "-" + Instant.now().getEpochSecond();

      if (properties.isDryRun()) {
        log.info("[DRY-RUN] Abriría draft-PR en {} (rama {})", repo, branch);
        outcomes.add(PhaseOutcome.forRepo(TaskPhase.IMPLEMENTATION, Recommendation.SKIPPED, repo,
            Evidence.of(EvidenceKind.REASON, "dry-run: no se abre PR")));
        continue;
      }
      try {
        final String base = properties.getBaseBranch();
        githubClient.createBranch(repo, branch, githubClient.getBranchSha(repo, base));
        // El planner decide el enfoque ANTES de que el coder escriba nada (solo lectura); el coder
        // lo sigue, pero decide por su cuenta si el planner está apagado o no dejó plan.
        final String plan = planWithAgent(issueKey, repo, branch, summary, description);
        // El coder intenta implementar la tarea; si no puede (apagado/dry-run/sin cambios/fallo),
        // se deja el placeholder para que la PR tenga al menos un commit que la sostenga.
        final AgentExecutionResult coderResult =
            codeWithAgent(issueKey, repo, branch, summary, description, plan);
        final boolean coded = coderResult.status() == AgentStatus.COMPLETED
            && coderResult.summary() != null && !coderResult.summary().isBlank();
        if (!coded) {
          githubClient.putFile(repo, branch, ".sixai/" + issueKey + ".md",
              placeholder, "chore(sixai): arranque de " + issueKey);
        }
        GithubClient.PullRequest pr = githubClient.createPullRequest(
            repo, branch, base, title, body, true);
        log.info("Draft-PR abierta en {} hacia {}: {}", repo, base, pr.url());
        links.add("- " + repo + " #" + pr.number() + ": " + pr.url());
        taskRecorder.record(issueKey, TaskEventType.PR_OPENED, "sixai",
            repo + " #" + pr.number() + " (" + branch + " → " + base + "): " + pr.url());

        // La PR cuenta como evidencia aunque sea de placeholder: la verificación la compila igual.
        final List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence(EvidenceKind.PR,
            "#" + pr.number() + " (" + branch + " → " + base + ")", pr.url()));
        if (!plan.isBlank()) {
          evidence.add(Evidence.of(EvidenceKind.PLAN, plan));
        }
        if (coded) {
          taskRecorder.record(issueKey, TaskEventType.CODE_GENERATED, "sixai", repo);
          codedRepos.add("- **" + repo + "** — PR [#" + pr.number() + "](" + pr.url() + "), rama `"
              + branch + "` → `" + base + "`");
          // El reviewer solo lee lo que el coder ya commiteó; su veredicto es una opinión más
          // para quien apruebe, nunca un bloqueo.
          final String review =
              reviewWithAgent(issueKey, repo, branch, summary, description, coderResult, pr.number());
          if (review != null) {
            evidence.add(Evidence.of(EvidenceKind.REVIEW, review));
          }
        } else {
          evidence.add(Evidence.of(EvidenceKind.REASON,
              "sin código del coder: " + coderOutcome(coderResult)));
        }
        outcomes.add(new PhaseOutcome(TaskPhase.IMPLEMENTATION,
            coded ? Recommendation.PASS : Recommendation.FAIL, repo, evidence, PhaseOutcome.SIXAI));

        // Se manda compilar la rama YA, para que cuando alguien mire la PR sepa si se sostiene.
        // El veredicto tarda unos minutos y llega solo, a la tarea y al panel.
        verificationService.verify(issueKey, repo, branch, pr.number());
      } catch (RuntimeException e) {
        log.error("No se pudo abrir la PR en {} para {}: {}", repo, issueKey, e.getMessage());
        outcomes.add(PhaseOutcome.forRepo(TaskPhase.IMPLEMENTATION, Recommendation.FAIL, repo,
            Evidence.of(EvidenceKind.REASON, "no se pudo abrir la PR: " + e.getMessage())));
      }
    }

    if (!links.isEmpty()) {
      jiraClient.addComment(issueKey,
          "sixai ha arrancado el trabajo abriendo estas PRs:\n" + String.join("\n", links));
    }
    outcomes.add(implementationVerdict(repos.size(), codedRepos.size(), properties.isDryRun()));
    return codedRepos;
  }

  /**
   * Veredicto de IMPLEMENTATION: PASS si algún repo tiene código de verdad; SKIPPED si todo fue
   * simulación; FAIL si no (todo placeholder o ninguna PR abierta).
   */
  private static PhaseOutcome implementationVerdict(final int repos, final int coded,
      final boolean dryRun) {
    final Recommendation recommendation;
    if (coded > 0) {
      recommendation = Recommendation.PASS;
    } else {
      recommendation = dryRun ? Recommendation.SKIPPED : Recommendation.FAIL;
    }
    return PhaseOutcome.of(TaskPhase.IMPLEMENTATION, recommendation,
        Evidence.of(EvidenceKind.REASON, coded + " de " + repos + " repo(s) con código del coder"
            + (dryRun ? " (dry-run)" : "")));
  }

  /** Por qué el coder no dejó código, tal y como lo cuenta él (ver {@link CoderAgent.Outcome}). */
  private static String coderOutcome(final AgentExecutionResult result) {
    if (result.status() != AgentStatus.COMPLETED) {
      final String summary = result.summary();
      return result.status() + (summary == null || summary.isBlank() ? "" : " — " + summary);
    }
    final Object outcome = result.data().get(CoderAgent.DATA_OUTCOME);
    return outcome == null ? "motivo desconocido" : String.valueOf(outcome);
  }

  private static PhaseOutcome selectionFailed(final String reason) {
    return PhaseOutcome.of(TaskPhase.REPO_SELECTION, Recommendation.FAIL,
        Evidence.of(EvidenceKind.REASON, reason));
  }
```

- [ ] **Step 4: `reviewWithAgent` devuelve el veredicto** — sustituir el método completo por:

```java
  /**
   * Pide al {@link ReviewerAgent}, vía {@link AgentRuntime}, un veredicto sobre lo que el coder
   * acaba de commitear, y lo comenta en la tarea junto al número de la PR. Best-effort: un fallo
   * aquí no afecta a la PR, que ya está abierta.
   *
   * @return el veredicto, o null si no hubo (sin cambios que revisar, reviewer apagado o fallo)
   */
  @SuppressWarnings("unchecked")
  private String reviewWithAgent(final String issueKey, final String repo, final String branch,
      final String summary, final String description, final AgentExecutionResult coderResult,
      final int prNumber) {
    final Object changedFiles = coderResult.data().get("changedFiles");
    if (!(changedFiles instanceof List<?> paths) || paths.isEmpty()) {
      return null;
    }
    try {
      final String codeSummary = coderResult.summary() == null ? "" : coderResult.summary();
      final AgentExecutionResult result = agentRuntime.execute(new AgentExecutionRequest(
          issueKey, summary, description, ReviewerAgent.ID,
          Map.of("repo", repo, "branch", branch, "changedFiles", (List<String>) paths,
              "codeSummary", codeSummary)));
      if (result.status() == AgentStatus.COMPLETED && result.summary() != null
          && !result.summary().isBlank()) {
        jiraClient.addComment(issueKey, "🔍 Revisión automática de " + repo + " (PR #" + prNumber
            + "):\n\n" + result.summary());
        return result.summary();
      }
    } catch (RuntimeException e) {
      log.warn("El reviewer falló en {} para {}: {}", repo, issueKey, e.getMessage());
    }
    return null;
  }
```

- [ ] **Step 5: Lectura de verificación** — el único otro llamante, `CorrectionService.java:82` (`pullRequestService.openForIssue(issueKey, instruction);`), sigue compilando: ignora el valor devuelto (decisión 6).

---

### Task 4: `IssueResponder` entrega los resultados

**Files:**
- Modify: `src/main/java/es/colorbaby/microservices/dev/relay/delivery/responder/IssueResponder.java`

**Interfaces:**
- Consumes: Task 3 (`openForIssue` devuelve `List<PhaseOutcome>`); paso 1 (`TaskLifecycle.accept`).

- [ ] **Step 1: Imports** — añadir:

```java
import es.colorbaby.microservices.dev.relay.control.lifecycle.Evidence;
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceKind;
import es.colorbaby.microservices.dev.relay.control.lifecycle.PhaseOutcome;
import es.colorbaby.microservices.dev.relay.control.lifecycle.Recommendation;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskLifecycle;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskPhase;
```

- [ ] **Step 2: Dependencia** — después de `private final TaskRecorder taskRecorder;` añadir `private final TaskLifecycle taskLifecycle;`.

- [ ] **Step 3: `onIssueEligible`** — sustituir:

```java
      taskRecorder.record(issueKey, TaskEventType.IN_PROGRESS, "sixai", null);
      pullRequestService.openForIssue(issueKey);
    } catch (RuntimeException e) {
      // No se relanza: un fallo respondiendo no debe tumbar el ciclo de detección.
      log.error("No se pudo responder la issue {}", issueKey, e);
      reportError(issueKey, e);
    }
```

por:

```java
      taskRecorder.record(issueKey, TaskEventType.IN_PROGRESS, "sixai", null);
      taskLifecycle.accept(issueKey, intakeDone(replyText));
      // Se entregan en el orden en que vienen: la selección de repos, cada PR y, al final, el
      // veredicto de la implementación.
      pullRequestService.openForIssue(issueKey)
          .forEach(outcome -> taskLifecycle.accept(issueKey, outcome));
    } catch (RuntimeException e) {
      // No se relanza: un fallo respondiendo no debe tumbar el ciclo de detección.
      log.error("No se pudo responder la issue {}", issueKey, e);
      reportError(issueKey, e);
      taskLifecycle.accept(issueKey, PhaseOutcome.of(TaskPhase.INTAKE, Recommendation.FAIL,
          Evidence.of(EvidenceKind.REASON, rootMessage(e))));
    }
```

- [ ] **Step 4: Helper** — antes de `private static String truncate(`, añadir:

```java
  /** INTAKE cerrada: la tarea está respondida y en curso. */
  private static PhaseOutcome intakeDone(final String replyText) {
    final Evidence evidence = replyText == null || replyText.isBlank()
        ? Evidence.of(EvidenceKind.REASON, "puesta en curso sin comentario")
        : Evidence.of(EvidenceKind.COMMENT, replyText);
    return PhaseOutcome.of(TaskPhase.INTAKE, Recommendation.PASS, evidence);
  }
```

- [ ] **Step 5: Verificación manual del usuario (tras compilar él)**

1. Disparar una tarea (real o con `dry-run` de GitHub) y, al terminar el arranque:
   - `SELECT id, phase, iteration, status, decided_by FROM task_phase WHERE task_run_id = <id> ORDER BY id;` → `INTAKE PASSED`, `REPO_SELECTION PASSED`, `IMPLEMENTATION PASSED|FAILED|SKIPPED` y, si pasó, `VERIFICATION IN_PROGRESS`.
   - `SELECT e.repo, e.recommendation, e.kind, LEFT(e.detail, 80), e.url FROM task_phase_evidence e JOIN task_phase p ON p.id = e.task_phase_id WHERE p.task_run_id = <id> ORDER BY e.id;` → comentario, método de selección, PR/PLAN/REVIEW por repo y el agregado.
2. En Jira y GitHub todo igual que antes (comentarios, PRs, placeholder, informe).
3. `task_event` sin `LIFECYCLE_VIOLATION` en ese arranque (en una tarea con todo placeholder, `IMPLEMENTATION` queda `FAILED` y es lo esperado; el aviso llegará cuando la verificación del paso 3 intente avanzar).

---

## Self-review

- **Cobertura spec paso 2:** `INTAKE` (Task 4) · `REPO_SELECTION` con método (Tasks 2–3) · `CoderAgent` `data.outcome` (Task 1) · `IMPLEMENTATION` por repo y agregado con `PR`/`PLAN`/`REVIEW` (Task 3) · el punto de entrada entrega los resultados (Task 4).
- **Comportamiento visible sin cambios:** mismos comentarios, PRs, placeholder, informe, revisión y verificación; el criterio `coded` es idéntico.
- **Coherencia de nombres:** `CoderAgent.DATA_OUTCOME`/`Outcome`, `SelectorAgent.Selection`/`Method`, `openForIssue → List<PhaseOutcome>`, `selectionFailed`, `implementationVerdict`, `coderOutcome`, `intakeDone`.
- **Para el paso 4:** `CorrectionService` deberá hacer `reroute` antes de `openForIssue` y entregar su lista al lifecycle.
