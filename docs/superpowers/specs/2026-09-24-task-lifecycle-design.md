# Ciclo de vida explícito de las tareas (fase 1)

- **Fecha:** 2026-09-24
- **Repo / rama:** `ms-dev-relay-java` · `feature/agents-mcp-config` (la lib `ms-dev-relay-lib-java` solo cambia para `TaskDetailDto.phases`, ver §7)
- **Estado:** diseño aprobado por partes en conversación; pendiente de revisión de este documento

## 1. Contexto y porqué

sixai ya recorre una tarea de principio a fin (Jira → PRs → verificación → aprobación → PRE →
TEST → PROD, con ciclos de corrección), y dentro de cada PR ya razona con agentes
(`PlannerAgent` → `CoderAgent` → `ReviewerAgent` vía `AgentRuntime`). Pero **el estado de una
tarea no existe como tal**: se deduce de varios sitios.

| Dónde vive hoy | Qué se deduce |
|---|---|
| `TaskMonitorService.stageOf` | La etapa del panel, a partir del **último** `TaskEvent` |
| `task_run.current_phase` | Texto libre; solo se rellena con `TEST` y `PROD` |
| `VerificationService.blocks()` | Si la aprobación se frena (solo con `block-approval=true`) |
| `deployment_batch` en `RUNNING` | Si ya hay un despliegue vivo |
| `CorrectionService.countCycles()` | Ciclos consumidos, **contando eventos** |
| Prefijo de rama en GitHub | Qué PRs están pendientes (`ApprovalService`, `PromotionService`) |

Además, **el porqué se pierde** en varios puntos:

- `CoderAgent` devuelve `COMPLETED` con resumen vacío tanto si está apagado, como si no propuso
  cambios, como en dry-run, como si el commit falló. `PullRequestService` solo ve "no hay código"
  y pone el placeholder.
- `SelectorAgent.select` devuelve la lista de repos, pero no **cómo** la eligió (LLM, palabras
  clave, único candidato, todos por falta de señal).
- El plan del `PlannerAgent` y el veredicto del `ReviewerAgent` solo viven en el prompt del coder
  y en un comentario de Jira: no quedan asociados a la tarea.
- `PullRequestService` hace `return` silencioso cuando no hay repos.

`src/main/resources/ai/architecture.md` ya pide esto: un *Maestro Orchestrator* por encima del
runtime y una **Memory** con "execution state, current step, decisions, errors, approvals,
resumable workflow state". Esta fase construye esa pieza a nivel de **tarea**, tomando como
referencia `MetaOrchestrator` + `meta-sdd`: **una fuente de verdad del estado, un único escritor,
fases con gates que llevan evidencia y reglas deterministas de transición**. El orquestador es
**código, no un LLM**.

### 1.1 Frontera con `AgentRuntime`

| | `TaskLifecycle` (nuevo) | `AgentRuntime` (existente) |
|---|---|---|
| Nivel | La **tarea** entera (días) | Una **ejecución** de agente (segundos/minutos) |
| Unidad | Fase (`IMPLEMENTATION`, `APPROVAL`…) | Paso (LLM → tool → LLM, máx. 8) |
| Estado | Persistido en BD | En memoria (`DefaultAgentContext`) |
| Decide | Si la tarea puede avanzar | Qué tool/agente sigue dentro de la ejecución |

El lifecycle **no** toca el runtime. Los resultados del runtime (`AgentExecutionResult`) se
traducen a evidencia de fase en quien los invoca (`PullRequestService`).

## 2. Objetivos y no-objetivos

**Objetivos**

1. Una tarea tiene una **fase explícita** y cada fase un **estado y un gate con evidencia**.
2. Todas las transiciones pasan por **un único componente** (`TaskLifecycle`) que las valida.
3. Todos los servicios devuelven un **handoff tipado** (`PhaseOutcome`) en vez de `void`/`boolean`;
   los agentes explican su resultado en `AgentExecutionResult.data`.
4. Panel, comando `STATUS`, aprobación y corrección **leen** ese estado en vez de deducirlo.
5. Despliegue en dos tiempos: **modo registro** (observa y avisa) → **modo estricto** (bloquea).

**No-objetivos (fases posteriores)**

- Que el orquestador **invoque** al siguiente especialista (dispatcher). Llega con el bucle de
  aclaraciones (fase 2), que es donde hace falta pausar y reanudar. Ahí se usará
  `AgentStatus.NEEDS_INPUT`, que hoy ningún agente emite.
- Persistir y reanudar ejecuciones del `AgentRuntime`.
- Que el `ReviewerAgent` sea un gate: sigue siendo "una opinión más, nunca un bloqueo".
- `CANCEL` real (cerrar la tarea como `CANCELLED`): el modelo lo permite, pero no se implementa.
- Cambios en el front: el panel recibe datos aditivos, no se rediseña.
- **Código de testing: no se escribe en ningún momento** (decisión del usuario).

## 3. Modelo de fases

```
INTAKE → REPO_SELECTION → IMPLEMENTATION → VERIFICATION → APPROVAL → DEPLOY_PRE
                               ▲                                        │
                               │ reroute (corrección, con presupuesto)  ▼
                               └──────────────── CLIENT_TEST ◄──────────┘
                                                      │
                                                      ▼
                                                 PROMOTION → DONE

Terminales: DONE · ESCALATED · FAILED · CANCELLED (este último, reservado)
```

| Fase | Dueño (especialista) | Qué la cierra |
|---|---|---|
| `INTAKE` | `delivery/responder/IssueResponder` | Comentario publicado y tarea en curso en Jira |
| `REPO_SELECTION` | `delivery/pullrequest/RepoResolver` + `SelectorAgent` | Lista de repos elegida |
| `IMPLEMENTATION` | `delivery/pullrequest/PullRequestService` (Planner → Coder → Reviewer vía `AgentRuntime`) | PR abierta por repo, con o sin código |
| `VERIFICATION` | `delivery/verification/VerificationService` + `VerificationOrchestrator` | Todos los repos con veredicto |
| `APPROVAL` | Humano vía `control/approval/ApprovalService` | Aprobación desde el front |
| `DEPLOY_PRE` | `ApprovalService` (merge) + `deploy/DeploymentService`/`DeploymentCoordinator` | Lote PRE cerrado |
| `CLIENT_TEST` | Cliente vía `control/command/CommandService` | `PROMOTE` o `REVISE` |
| `PROMOTION` | `control/approval/PromotionService` + `DeploymentService`/`DeploymentCoordinator` | Lote PROD cerrado |

### 3.1 Estado de una fase

`PENDING | IN_PROGRESS | BLOCKED | PASSED | FAILED | SKIPPED`

- Una sola columna de estado por fase: el gate **es** el estado final + `decided_by` + la evidencia.
- `BLOCKED` queda reservado para la fase 2 (preguntas pendientes); en esta fase no se produce.

### 3.2 Transiciones permitidas

1. **Avance normal:** una fase en `PASSED` o `SKIPPED` pasa a la siguiente del orden de arriba.
2. **Fase fallida:** una fase en `FAILED` **no avanza**. Solo sale por:
   - *reroute* a `IMPLEMENTATION` (corrección), si la fase lo admite (regla R4);
   - reintento explícito de `PROMOTION` (nuevo `PROMOTE` tras un `PROMOTION` fallido);
   - `FAILED` terminal, cuando el error es irrecuperable.
3. **Terminales:** `DONE`, `ESCALATED`, `FAILED`, `CANCELLED` no admiten transiciones.

### 3.3 Reglas del gate

| # | Regla | Dónde se consulta (`check`) |
|---|---|---|
| R1 | Solo se entra en `APPROVAL` con `VERIFICATION` **cerrada**. Si cerró en `FAILED`, solo con `maestro.verification.block-approval=false`, y queda evidencia `DECISION` "aprobado con verificación fallida por X". Además exige `IMPLEMENTATION` en `PASSED` (no se aprueban PRs de solo placeholder). | `ApprovalService.approve` |
| R2 | `DEPLOY_PRE` y `PROMOTION` exigen el gate anterior en `PASSED` (`APPROVAL` y `CLIENT_TEST` respectivamente, o `PROMOTION` en `FAILED` para reintento) y **ningún despliegue vivo**. | `DeploymentService.startBatch` |
| R3 | Un *reroute* a `IMPLEMENTATION` consume **1 iteración** de `task_run.remediation_iterations`. Si ya vale `maestro.correction.max-cycles`, la tarea pasa a `ESCALATED` en vez de reabrir. | `CorrectionService` |
| R4 | Solo se hace *reroute* desde `IMPLEMENTATION` en `FAILED`, `VERIFICATION` en `FAILED`, `DEPLOY_PRE` en `FAILED` o `CLIENT_TEST`. Un fallo en `PROMOTION` nunca se corrige solo. | `CorrectionService` |
| R5 | Nada transiciona desde un terminal. | Todas |

Las comprobaciones de seguridad que ya existen **se mantienen** además de las reglas
(`inFlight` de `ApprovalService`, lote `RUNNING` en `DeploymentService`): son baratas y protegen
también en modo registro.

### 3.4 Multirrepo

- **`IMPLEMENTATION`**: `PullRequestService` emite un resultado por repo y, al acabar el bucle,
  uno agregado (`repo = null`) que **cierra** la fase: `PASSED` si al menos un repo tiene código
  real; `FAILED` si ninguno (todo placeholder o ninguna PR abierta).
- **`VERIFICATION`**: se cierra **por agregación**. Repos esperados = los que tienen evidencia
  `PR` en `IMPLEMENTATION` de la iteración actual. Cuando cada uno tiene veredicto
  (`PASS`/`FAIL`/`SKIPPED`): `FAILED` si alguno falló; `SKIPPED` si todos se saltaron;
  `PASSED` en otro caso. Esa lógica vive en `TaskLifecycle`, no en el orquestador de Jenkins.
- **`DEPLOY_PRE` / `PROMOTION`**: `DeploymentCoordinator` ya agrega el lote; emite un único
  resultado agregado al cerrar el lote.

### 3.5 Iteraciones

Un *reroute* no sobrescribe: abre filas nuevas de `task_phase` con `iteration + 1` desde
`IMPLEMENTATION`. El historial de cada intento (plan, código, revisión, qué falló y por qué) se
conserva.

### 3.6 Relación con `task_run.status`

| Fase | `task_run.status` |
|---|---|
| No terminal | `RUNNING` |
| `DONE` | `DONE` (+ `finished_at`, `duration_ms`, como hoy) |
| `FAILED` | `FAILED` (+ `finished_at`, `duration_ms`) |
| `ESCALATED` | **`RUNNING`**: se mantiene visible en el panel como "Necesita una persona", igual que hoy tras `GAVE_UP` |

## 4. Contrato de handoff

### 4.1 Resultado de fase

```
PhaseOutcome
  phase           TaskPhase
  recommendation  STARTED | PASS | FAIL | SKIPPED | BLOCKED
  repo            String, opcional (null = resultado agregado de la fase)
  evidence        List<Evidence>
  actor           "sixai" o la persona

Evidence
  kind    PR | COMMENT | BUILD | REPORT | PLAN | REVIEW | DECISION | REASON
  detail  texto (se trunca a 2000)
  url     opcional
```

- Los artefactos (PR, informe, build) son evidencias con URL; no hay un tipo aparte.
- `BLOCKED` está en el contrato para la fase 2; en esta fase nadie lo emite.

### 4.2 Resultado de los agentes (sin contrato nuevo)

Los agentes ya devuelven `AgentExecutionResult(status, summary, data)`. No se crea un tipo
paralelo: se usa `data` para que **el porqué no se pierda**.

| Agente | Cambio |
|---|---|
| `CoderAgent` | Cuando no commitea, sigue devolviendo `COMPLETED` con resumen vacío (el `PullRequestService` no cambia su criterio de "hay código"), pero añade `data.outcome` ∈ `DISABLED`, `NO_CHANGES`, `DRY_RUN`, `COMMIT_FAILED`. Con código: `data.outcome = CODED` + `changedFiles` (como hoy). Un error del modelo (`safeCall`) sigue su camino actual y, si llega a `FAILED`, el `summary` es la causa. |
| `SelectorAgent` | `select` devuelve `RepoSelection(repos, method)`, `method` ∈ `SINGLE_CANDIDATE`, `LLM`, `KEYWORDS`, `ALL_FALLBACK`, `NONE`. |
| `PlannerAgent` | Sin cambios: su `summary` (el plan) se guarda como evidencia `PLAN` por repo. |
| `ReviewerAgent` | Sin cambios: su veredicto se guarda como evidencia `REVIEW` por repo, además del comentario en Jira. No afecta al estado de la fase. |

`PullRequestService` traduce estos resultados a `PhaseOutcome` de `IMPLEMENTATION`.

### 4.3 Quién consume el handoff

Cada servicio **devuelve** su resultado y **no toca el estado**. Quien lo entrega a
`TaskLifecycle.accept(...)` es el punto de entrada que ya existe (listener, sweeper, endpoint de
`SixaiApiDelegateImpl`, comando). El encadenamiento actual (`IssueResponder` →
`PullRequestService` → agentes → `VerificationService`) se mantiene: lo que cambia es que los
resultados suben en vez de perderse.

### 4.4 Qué devuelve cada servicio

| Servicio | Fase | Resultado |
|---|---|---|
| `IssueResponder` | `INTAKE` | `PASS` (evidencia: comentario publicado, transición en Jira) · `FAIL` (causa raíz, hoy en `reportError`) |
| `RepoResolver` + `SelectorAgent` | `REPO_SELECTION` | `PASS` con los repos elegidos y el `method` · `FAIL` sin candidatos o sin selección |
| `PullRequestService` | `IMPLEMENTATION` | Por repo: `PASS` (PR con código; evidencias `PR`, `PLAN`, `REVIEW`) / `FAIL` (PR de placeholder, con `data.outcome` del coder como `REASON`) / `FAIL` (no se pudo abrir). Agregado: ver 3.4 |
| `VerificationService` | `VERIFICATION` | Por repo: `STARTED` (encolado) · `SKIPPED` (sin job de build, verificación apagada o dry-run) · `FAIL` (no se pudo encolar) |
| `VerificationOrchestrator` | `VERIFICATION` | Por repo: `PASS` · `FAIL` (motivo + diagnóstico si lo hay) |
| `ApprovalService` | `APPROVAL` → `DEPLOY_PRE` | `APPROVAL` `PASS` con `actor` = aprobador; merges como evidencia de `DEPLOY_PRE` (`STARTED`) |
| `DeploymentService.startBatch` | `DEPLOY_PRE` / `PROMOTION` | `STARTED` con repos arrancados y los que quedaron fuera (`StartResult` ya existe) |
| `DeploymentCoordinator` | `DEPLOY_PRE` / `PROMOTION` | Agregado: `PASS` · `FAIL` con el detalle por repo |
| `CommandService` | `CLIENT_TEST` | `PROMOTE` autorizado → `CLIENT_TEST` `PASS` (actor = autor) · `REVISE` → `CLIENT_TEST` `FAIL` + petición de *reroute* |
| `PromotionService` | `PROMOTION` | `STARTED` con los merges; `FAIL` si no hay nada que promocionar |
| `CorrectionService` | — | No emite resultado: pide `reroute(IMPLEMENTATION, motivo, actor)`; recibe permitido o `ESCALATED` |

Decisión aceptada: una tarea cuyas PRs son **todas placeholder** deja `IMPLEMENTATION` en
`FAILED`. En modo registro solo se anota; en modo estricto impide aprobarla (R1) y la única salida
es una corrección (`/sixai` con `REVISE`).

## 5. `TaskLifecycle`

Paquete `control/lifecycle/` (responde a "¿quién decide qué pasa a partir de aquí?", según
`docs/arquitectura-paquetes.md`). Es el único componente que escribe el estado. Operaciones:

| Operación | Qué hace |
|---|---|
| `check(issueKey, targetPhase)` | Evalúa R1–R5 **antes** de una acción con efectos. Devuelve `Verdict` (permitido / denegado + motivo). |
| `accept(issueKey, outcome)` | Valida la transición, guarda la evidencia, actualiza estado de fase y `current_phase`, avanza si procede, registra el hito en `task_event`. Devuelve `Verdict`. |
| `reroute(issueKey, IMPLEMENTATION, reason, actor)` | Aplica R3/R4: abre la iteración siguiente o pasa a `ESCALATED`. |
| `snapshot(issueKey)` | Fase actual, estado, iteración y evidencias por fase. Lo usan panel y `STATUS`. |

### 5.1 Modos

`maestro.lifecycle.enforce` (por defecto `false`).

| | Modo registro (`false`) | Modo estricto (`true`) |
|---|---|---|
| `check` con regla incumplida | Devuelve **permitido**; registra `LIFECYCLE_VIOLATION` (WARN en log + evento en la línea de tiempo) | Devuelve **denegado**; el llamante no actúa y lo cuenta en Jira (como hoy `ApprovalService` con `block-approval`) |
| `accept` con transición inválida | **Se aplica igual** (el estado sigue a la realidad) + `LIFECYCLE_VIOLATION` | Se rechaza + `LIFECYCLE_VIOLATION` |
| Error interno del lifecycle (BD caída…) | Se loguea y el flujo sigue (best-effort, como `TaskRecorder`) | Igual: un fallo del registro no tumba el trabajo |

### 5.2 Eventos

- Nuevo `TaskEventType.LIFECYCLE_VIOLATION` (detalle: regla, fase actual, fase pedida).
- Los cambios de fase **no** generan eventos nuevos: ya quedan en `task_phase`. Los hitos
  actuales (`PR_OPENED`, `VERIFY_OK`…) se siguen registrando igual.
- `TaskRecorder.phase()` se elimina; `start`, `describe`, `record`, `finish` se mantienen.

## 6. Persistencia — `V6__lifecycle.sql`

(`V5` ya está ocupada por `V5__knowledge.sql`.)

**`task_run`**

- `current_phase` (ya existe, `VARCHAR(20)`): pasa a guardar `TaskPhase`. Los nombres más largos,
  `REPO_SELECTION`/`IMPLEMENTATION`, tienen 14 caracteres.
- Nueva `remediation_iterations INT NOT NULL DEFAULT 0`.

**`task_phase`** — una fila por (tarea, fase, iteración)

| Columna | Tipo | Nota |
|---|---|---|
| `id` | `BIGINT AUTO_INCREMENT PK` | |
| `task_run_id` | `BIGINT NOT NULL` | FK → `task_run.id` |
| `phase` | `VARCHAR(20) NOT NULL` | |
| `iteration` | `INT NOT NULL DEFAULT 0` | |
| `status` | `VARCHAR(20) NOT NULL` | |
| `decided_by` | `VARCHAR(255) NULL` | Quién cerró el gate |
| `started_at` | `TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP` | |
| `finished_at` | `TIMESTAMP NULL` | |

Índices: `UNIQUE (task_run_id, phase, iteration)`, `(status)`.

**`task_phase_evidence`** — append-only, un registro por evidencia de cada `PhaseOutcome`

| Columna | Tipo | Nota |
|---|---|---|
| `id` | `BIGINT AUTO_INCREMENT PK` | |
| `task_phase_id` | `BIGINT NOT NULL` | FK → `task_phase.id` |
| `repo` | `VARCHAR(150) NULL` | `null` = agregado |
| `recommendation` | `VARCHAR(20) NOT NULL` | |
| `kind` | `VARCHAR(20) NOT NULL` | |
| `detail` | `VARCHAR(2000) NULL` | `VARCHAR` y no `TEXT`, por `ddl-auto=validate` (mismo criterio que `V1`). Un plan o revisión más largos se truncan. |
| `url` | `VARCHAR(500) NULL` | |
| `actor` | `VARCHAR(255) NULL` | |
| `recorded_at` | `TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP` | |

Índice: `(task_phase_id)`.

Un `PhaseOutcome` sin evidencias se guarda igualmente como una fila con `kind = REASON` y
`detail` nulo, para que conste la recomendación.

### 6.1 Relleno de las tareas en curso

Solo para `task_run.status = 'RUNNING'`. Se toma el **último evento relevante** (ignorando
`COMMAND_RECEIVED` y `CORRECTION_REQUESTED`, que no cambian la etapa):

| Último evento | `current_phase` | Estado de fase |
|---|---|---|
| `DETECTED` | `INTAKE` | `IN_PROGRESS` |
| `IN_PROGRESS` | `REPO_SELECTION` | `IN_PROGRESS` |
| `CODE_GENERATED`, `PR_OPENED`, `VERIFY_STARTED`, `CORRECTION_STARTED` | `VERIFICATION` si hubo `VERIFY_STARTED` después del último `PR_OPENED`, si no `IMPLEMENTATION` | `IN_PROGRESS` |
| `VERIFY_OK` | `APPROVAL` | `IN_PROGRESS` |
| `VERIFY_FAILED` | `VERIFICATION` | `FAILED` |
| `APPROVED`, `MERGED`, `BUILD_STARTED`, `BUILD_OK`, `DEPLOYED_PRE` | `DEPLOY_PRE` | `IN_PROGRESS` |
| `BUILD_FAILED`, `FAILED` | `DEPLOY_PRE` | `FAILED` |
| `MOVED_TO_TEST` | `CLIENT_TEST` | `IN_PROGRESS` |
| `PROMOTED` | `PROMOTION` | `IN_PROGRESS` |
| `GAVE_UP` | `ESCALATED` | — |

- `remediation_iterations` = número de `CORRECTION_STARTED` de la tarea (paridad con `countCycles`).
- La fila de `task_phase` rellenada lleva `iteration = remediation_iterations`.
- Es una aproximación (por ejemplo, `VERIFY_OK` de un repo no garantiza que los demás hayan
  terminado). Es aceptable: solo afecta a tareas que ya estaban en vuelo, y en modo registro una
  incoherencia solo produce avisos.

## 7. Panel y API

- `panel/monitor/TaskMonitorService.stageOf` lee `current_phase` + estado. **Las `stageKey` no
  cambian**, así el front sigue funcionando sin tocarlo. Un despliegue vivo sigue teniendo
  prioridad (detalle de build/deploy por repo, como hoy).

| Fase / estado | Etiqueta | `stageKey` |
|---|---|---|
| `INTAKE` | Detectada | `DETECTED` |
| `REPO_SELECTION` | Analizando la tarea | `IN_PROGRESS` |
| `IMPLEMENTATION` (iteración 0) | Escribiendo código | `CODE_GENERATED` |
| `IMPLEMENTATION` (iteración > 0) | Corrigiendo | `CORRECTION` |
| `IMPLEMENTATION` `FAILED` | Sin código generado | `FAILED` |
| `VERIFICATION` en curso | Comprobando que compila | `VERIFYING` |
| `VERIFICATION` `FAILED` | No compila | `VERIFY_FAILED` |
| `APPROVAL` | Esperando aprobación | `AWAITING_APPROVAL` |
| `DEPLOY_PRE` en curso | Mergeada a develop | `MERGED` |
| `DEPLOY_PRE` `FAILED` | Con fallos | `FAILED` |
| `CLIENT_TEST` | En pruebas del cliente | `TEST` |
| `PROMOTION` | Promocionando a producción | `PROMOTING` |
| `DONE` | Desplegada en producción | `DEPLOYED_PROD` |
| `ESCALATED` | Necesita una persona | `GAVE_UP` |
| `FAILED` | Con fallos | `FAILED` |

- `TaskMonitorService.fromEvent` desaparece (el switch exhaustivo sobre `TaskEventType` además
  obligaría a tocarlo por `LIFECYCLE_VIOLATION`).
- `TaskDetailDto` gana `phases`: lista de `{phase, iteration, status, decidedBy, startedAt,
  finishedAt, evidence[]}`. Cambio aditivo. `TaskDetailDto` es un modelo **generado por OpenAPI**
  (`openapi.model.TaskDetailDto`, usado en `SixaiApiDelegateImpl`) y, según el `.gitignore` del
  servicio, sus esquemas viven en `ms-dev-relay-lib-java`. Así que este campo es la **única pieza
  de la fase que toca la lib** (esquema → lib → servicio, en ese orden). **Pendiente:** el esquema
  no aparece ni en la lib del workspace (`main`) ni en el jar instalado en `.m2`, ni en el servicio
  hay `openapi.yaml`; hay que localizar dónde está antes del paso 5.
- Comando `STATUS`: responde fase actual, estado del gate, iteración `n/max` y lo que falta para
  avanzar (por ejemplo, "faltan 1 de 2 veredictos de compilación"), además de las PRs.

## 8. Configuración

| Clave | Por defecto | Nota |
|---|---|---|
| `maestro.lifecycle.enforce` | `false` | Nueva. `MAESTRO_LIFECYCLE_ENFORCE` |
| `maestro.correction.max-cycles` | `3` | Existente; pasa a ser el presupuesto de R3 |
| `maestro.verification.block-approval` | `false` | Existente; en R1 decide si una verificación fallida permite aprobar |

## 9. Validación (sin código de testing)

1. **Compilación** tras cada paso, con el servicio parado. Se le pregunta al usuario antes, según
   el `CLAUDE.md` del workspace.
2. **Modo registro como red de seguridad:** con `enforce=false`, cualquier desajuste entre reglas
   y realidad aparece como `LIFECYCLE_VIOLATION` sin cambiar el comportamiento.
3. **Comprobación manual del usuario** al final de cada paso: una tarea real o en `dry-run`,
   revisando `task_phase`/`task_phase_evidence` en BD y el panel.
4. **Paso a `enforce=true`** solo cuando no aparezcan violaciones durante un periodo que decide el
   usuario.

## 10. Orden de implementación

Todo en la rama `feature/agents-mcp-config`. Cada paso compila por separado.

1. **Modelo:** `V6`, `TaskPhase`, `PhaseStatus`, entidades y repositorios, `PhaseOutcome`,
   `Evidence`, `Verdict`, `TaskLifecycle` (modo registro), `LIFECYCLE_VIOLATION`, propiedad
   `enforce`.
2. **Arranque del flujo:** `IssueResponder` (`INTAKE`), `RepoResolver` + `SelectorAgent`
   (`REPO_SELECTION`, `RepoSelection`), `CoderAgent` (`data.outcome`) y `PullRequestService`
   (`IMPLEMENTATION`, con evidencias `PLAN`/`REVIEW`).
3. **Verificación:** `VerificationService`, `VerificationOrchestrator` y la agregación en el
   lifecycle.
4. **Aprobación, despliegue, promoción, comandos y corrección:** `check` en `ApprovalService` y
   `DeploymentService`, resultados de `DeploymentCoordinator` y `PromotionService`, `CLIENT_TEST`
   desde `CommandService`, `reroute` en `CorrectionService` (sustituye `countCycles`).
5. **Lectura:** `TaskMonitorService`, `TaskDetailDto.phases`, `STATUS`; se elimina
   `TaskRecorder.phase()`.
6. **`enforce=true`:** cambio de configuración, decisión del usuario.

## 11. Riesgos

| Riesgo | Mitigación |
|---|---|
| El relleno de `V6` asigna mal la fase de una tarea en vuelo | Solo afecta a tareas en curso; en modo registro solo genera avisos. |
| Muchas violaciones al principio (el flujo actual incumple reglas a propósito, p. ej. aprobar PRs de placeholder) | Es la señal buscada: se revisan antes de pasar a estricto. |
| El lifecycle falla y arrastra el trabajo real | Best-effort en ambos modos, igual que `TaskRecorder`. |
| En modo estricto una tarea queda atascada en una fase `FAILED` | La salida es una corrección (`REVISE`) o, en `PROMOTION`, un nuevo `PROMOTE`; con el presupuesto agotado pasa a `ESCALATED` y se ve en el panel. |
| Cambiar `CoderAgent`/`SelectorAgent` altera el flujo de delivery | Los cambios son aditivos (`data.outcome`, `method`): el criterio de "hay código" de `PullRequestService` no cambia. |
