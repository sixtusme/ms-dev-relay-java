# Traspaso de sesión — Ciclo de vida explícito de las tareas (sixai / dev-relay)

> Documento para que otra sesión de IA continúe el trabajo **sin repetir nada**. Léelo entero
> antes de tocar código. Fecha de corte: 2026-09-24.

---

## 1. Qué se busca (el objetivo grande)

El usuario quiere que **dev-relay (sixai) trabaje como `MetaOrchestrator`** (el orquestador de
agentes de metacontext) cuando resuelve una tarea de Jira: más "senior", menos tubería implícita.

De la comparación con `MetaOrchestrator` + el skill `meta-sdd` salió esta hoja de ruta de mejoras
(el orden lo acordó el usuario):

1. **Máquina de estados explícita + contrato de handoff** ← *en curso (esta fase)*.
2. **Bucle de aclaraciones por Jira**: un especialista puede devolver preguntas; la fase queda
   `BLOCKED`, se preguntan en un comentario de Jira y, con la respuesta, se **reanuda el mismo
   especialista**. (`AgentStatus.NEEDS_INPUT` ya existe y nadie lo emite todavía.)
3. **Clasificación por tracks** (simple / moderate / complex) y **fase de plan** con criterios de
   aceptación (el `PlannerAgent` ya existe).
4. **Remediación antes de la aprobación**: si la verificación falla, diagnóstico + coder reintentan
   sobre la MISMA PR, con presupuesto y clasificando la causa raíz (código → coder; plan → planner;
   criterio → humano).
5. **Coder delegado a un runtime de agente de verdad** (bucle con tools, ediciones por parche,
   compilar antes de entregar) en vez del coder de una pasada.
6. **Verificación contra criterios de aceptación + retro** que escribe lecciones en los
   `AGENTS.md`/`CLAUDE.md` de los repos.

Principio que se mantiene en todo: **el orquestador de dev-relay es código determinista, no un
LLM**. La inteligencia vive solo en los especialistas (agentes).

---

## 2. Reglas del usuario (obligatorias)

- **Repo y rama:** `C:\Users\sixtusme\Dev\metaenlace\proyectos\metacontext\runtimes\root\repos\ms-dev-relay-java`,
  rama **`feature/agents-mcp-config`**. Existe un clon viejo en
  `C:\Users\sixtusme\Dev\proyectos\sixai\ms-dev-relay-java` (rama `main`, código anterior a los
  agentes): **no leerlo ni tocarlo**.
- **No se compila nunca** (nada de `mvn`). Compila y arranca el usuario.
- **No se escribe código de testing nunca** (ni clases, ni dependencias de test, ni asserts).
- **No hacer commits** salvo que el usuario lo pida (él commitea).
- **Los servicios los arranca el usuario** desde el IDE: no pararlos ni reiniciarlos.
- Idioma: español. Estilo del repo: 2 espacios, parámetros `final`, Lombok, javadoc en español
  explicando el **porqué**.
- Paquetes por dominio (ver `src/main/resources/docs/arquitectura-paquetes.md`); arquitectura de IA
  en `src/main/resources/ai/architecture.md`.
- Flujo de trabajo que se ha seguido: diseño aprobado por partes → spec → **plan por paso** →
  ejecución "Native" (la IA implementa) → **revisión final por un subagente revisor de solo
  lectura** → arreglar Critical/Important, listar Minor al usuario.

---

## 3. Documentos de referencia (leer en este orden)

| Documento | Para qué |
|---|---|
| `docs/superpowers/specs/2026-09-24-task-lifecycle-design.md` | **La spec aprobada** de la fase 1 de la hoja de ruta: fases, reglas R1–R5, handoff, persistencia, panel, pasos 1–6. Es la autoridad. |
| `docs/superpowers/plans/2026-09-24-task-lifecycle-paso-1-modelo.md` | Plan ejecutado del paso 1 (con sus decisiones de implementación). |
| `docs/superpowers/plans/2026-09-24-task-lifecycle-paso-2-arranque.md` | Plan ejecutado del paso 2 (con sus decisiones). |
| `src/main/resources/ai/architecture.md` | Arquitectura Maestro (agentes, tools, skills, knowledge, memory). |
| `src/main/resources/docs/arquitectura-paquetes.md` | Por qué los paquetes están organizados por dominio. |

---

## 4. Diseño en una página (resumen de la spec)

**Fases** (`control/lifecycle/TaskPhase`):
`INTAKE → REPO_SELECTION → IMPLEMENTATION → VERIFICATION → APPROVAL → DEPLOY_PRE → CLIENT_TEST →
PROMOTION → DONE`; terminales `DONE · ESCALATED · FAILED · CANCELLED`. Una corrección hace
*reroute* a `IMPLEMENTATION` con iteración +1 (presupuesto `maestro.correction.max-cycles`).

**Estado de fase:** `PENDING | IN_PROGRESS | BLOCKED | PASSED | FAILED | SKIPPED`. El gate es el
estado final + `decided_by` + evidencias.

**Reglas** (`LifecycleRules`):
- R1 aprobar solo con `VERIFICATION` cerrada (si falló, solo con `block-approval=false`) y
  `IMPLEMENTATION` en `PASSED`.
- R2 `DEPLOY_PRE`/`PROMOTION` solo tras su gate previo en `PASSED` (o `PROMOTION` fallida para
  reintento).
- R3 presupuesto de correcciones → `ESCALATED`. **Vinculante en los dos modos.**
- R4 solo se corrige desde `IMPLEMENTATION`/`VERIFICATION`/`DEPLOY_PRE` en `FAILED` o desde
  `CLIENT_TEST`.
- R5 nada sale de un terminal.
- `ORDER`: resultado fuera de secuencia.

**Handoff:** `PhaseOutcome(phase, recommendation STARTED|PASS|FAIL|SKIPPED|BLOCKED, repo|null,
evidence[], actor)`; `Evidence(kind PR|COMMENT|BUILD|REPORT|PLAN|REVIEW|DECISION|REASON, detail,
url)`. **Los servicios devuelven resultados y NO tocan el estado**; quien se los entrega a
`TaskLifecycle.accept(...)` es el **punto de entrada** (listener, sweeper, endpoint, comando).

**`TaskLifecycle`** (único escritor): `check(issueKey, target)` antes de actuar,
`accept(issueKey, outcome)`, `reroute(issueKey, reason, actor)`. Transacción propia
(`REQUIRES_NEW`) y *best-effort*: cualquier fallo → "permitido", nunca tumba el trabajo real.

**Modos:** `maestro.lifecycle.enforce=false` (por defecto, **modo registro**): una regla incumplida
se anota como evento `LIFECYCLE_VIOLATION` y se aplica igual. `true` (modo estricto): se rechaza.
Se pasa a `true` cuando los avisos lleven tiempo sin aparecer (decisión del usuario, paso 6).

**Multirrepo:** `IMPLEMENTATION` se cierra con un resultado agregado; `VERIFICATION` se cierra por
agregación cuando todos los repos con evidencia `PR` de la iteración tienen veredicto;
`DEPLOY_*` los agrega `DeploymentCoordinator`.

**Persistencia (`V6__lifecycle.sql`):** `task_run.remediation_iterations`, tablas `task_phase` (la
fase actual = **última fila** de la tarea; sin `UNIQUE`) y `task_phase_evidence` (append-only).
Relleno aproximado de las tareas en curso a partir de su último evento.

**Frontera:** `TaskLifecycle` = nivel tarea (fases, BD). `AgentRuntime` = nivel ejecución de un
agente (pasos LLM→tool, en memoria). El lifecycle no toca el runtime.

---

## 5. Estado actual

### Paso 1 — Modelo ✅ implementado y revisado
- Nuevo paquete `control/lifecycle/`: `TaskPhase`, `PhaseStatus`, `Recommendation`, `EvidenceKind`,
  `Evidence`, `PhaseOutcome`, `Verdict`, `TaskPhaseRun`, `PhaseEvidence`, `TaskPhaseRunRepository`,
  `PhaseEvidenceRepository`, `PhaseSnapshot`, `LifecycleRules`, `TaskLifecycle`.
- `config/LifecycleProperties` + `maestro.lifecycle.enforce` en `application.yml`.
- `V6__lifecycle.sql`. `TaskRun.remediationIterations` + `TaskRun.close()` (lo usa
  `TaskRecorder.finish`). `TaskEventType.LIFECYCLE_VIOLATION`; `TaskMonitorService` lo ignora al
  calcular la etapa.
- Revisión independiente: sin Critical. Se arreglaron 2 Important: transacción `REQUIRES_NEW` en
  `TaskLifecycle.safely`, y `LifecycleRules.isLate` (un resultado de una **iteración anterior** es
  evidencia tardía, no un salto adelante).
- Nadie lo usaba aún al terminar el paso 1 (comportamiento idéntico).

### Paso 2 — Arranque del flujo ✅ implementado (revisión independiente lanzada al cierre; ver §8)
- `CoderAgent`: `DATA_OUTCOME="outcome"` + `enum Outcome { CODED, DISABLED, NO_CHANGES, DRY_RUN,
  TRUNCATED, LLM_ERROR, GUARD_REJECTED, COMMIT_FAILED }` en `data`; mismo `status` y resumen que
  antes (el criterio "hay código" de `PullRequestService` no cambia).
- `SelectorAgent.select` devuelve `Selection(repos, Method)`, `Method ∈ NONE, SINGLE_CANDIDATE,
  LLM, KEYWORDS, ALL_FALLBACK`.
- `PullRequestService.openForIssue` devuelve `List<PhaseOutcome>` en orden: `REPO_SELECTION`, un
  `IMPLEMENTATION` por repo (evidencias `PR`, `PLAN`, `REVIEW` o `REASON` con el outcome del coder)
  y el agregado (`PASS` si algún repo tiene código, `SKIPPED` si todo fue dry-run, `FAIL` si no).
  `reviewWithAgent` devuelve el veredicto.
- `IssueResponder` (punto de entrada) acepta `INTAKE PASS` (o `FAIL` en el `catch`) y luego la lista.
- `CorrectionService` **todavía ignora** la lista que devuelve `openForIssue` (llega en el paso 4).

### Estado de git
El usuario commitea por su cuenta. Al cierre, el paso 1 estaba **parcialmente commiteado** (solo
`V6` y el plan) y el resto de ficheros de pasos 1 y 2 estaban sin commitear. Comprobar con
`git status` al empezar.

---

## 6. Lo que queda por hacer

### Paso 3 — Verificación
- `VerificationService.verify(...)` devuelve `PhaseOutcome` por repo: `STARTED` (encolado),
  `SKIPPED` (sin job de build, verificación apagada o dry-run), `FAIL` (no se pudo encolar).
- `VerificationOrchestrator` (sweeper `@Scheduled`) es punto de entrada: al terminar cada build
  entrega `PASS`/`FAIL` por repo (con diagnóstico) a `TaskLifecycle.accept`. La agregación ya está
  hecha en `LifecycleRules.resolve`.
- **Orden:** `PullRequestService` llama a `verify` dentro del bucle de repos, **antes** del
  agregado de `IMPLEMENTATION`. Los resultados de `verify` deben añadirse a la lista de `outcomes`
  **después** del agregado (acumularlos aparte y añadirlos al final), o el lifecycle los verá
  fuera de orden (`ORDER`).
- Ojo: si `IMPLEMENTATION` queda `FAILED` (todo placeholder), los veredictos de verificación
  llegarán con la fase actual en `IMPLEMENTATION` → violación `ORDER` en modo registro. Es lo
  esperado por la spec (se registra y se deja pasar).

### Paso 4 — Aprobación, despliegue, promoción, comandos y corrección
- `ApprovalService.approve`: `check(APPROVAL)` antes de mergear; en modo estricto, denegado ⇒
  comentar en Jira el motivo (como hoy con `block-approval`). Aceptar `APPROVAL PASS` con
  `actor = aprobador`. **Si se aprueba con la verificación fallida, añadir evidencia `DECISION`
  "aprobado con verificación fallida por X"** (R1 de la spec; lo señaló la revisión del paso 1).
  Los merges → evidencia de `DEPLOY_PRE` (`STARTED`).
- `DeploymentService.startBatch`: `check(DEPLOY_PRE | PROMOTION)`; devolver `STARTED` con los
  repos arrancados y los que quedaron fuera (`StartResult` ya existe). **Mantener** su comprobación
  de "lote RUNNING".
- `DeploymentCoordinator`: entregar el agregado `PASS`/`FAIL` al cerrar el lote. **Quitar**
  `taskRecorder.phase(...)` y `taskRecorder.finish(...)`: el cierre de la tarea en `DONE` lo hace
  ya el lifecycle al pasar `PROMOTION` (evita cerrar dos veces y pisar `current_phase` con
  `TEST`/`PROD`).
- `CommandService`: `PROMOTE` autorizado ⇒ `CLIENT_TEST PASS` (actor = autor) y luego
  `check(PROMOTION)`; `REVISE` ⇒ `CLIENT_TEST FAIL` y petición de corrección.
- `PromotionService`: `STARTED` con los merges; `FAIL` si no hay nada que promocionar.
- `CorrectionService`: sustituir `countCycles` por `taskLifecycle.reroute(...)`. Si devuelve
  denegado con regla `R3` ⇒ comportamiento actual de "gave up" (la tarea ya quedó en `ESCALATED`).
  **Hacer `reroute` ANTES de `openForIssue`** y entregar la lista que devuelve al lifecycle.

### Paso 5 — Lectura
- `TaskLifecycle.snapshot(issueKey)` (no existe aún).
- `TaskMonitorService.stageOf` pasa a leer fase + estado (tabla de mapeo a `stageKey` en la spec
  §7; **las `stageKey` no cambian**) y se elimina `fromEvent`. Un despliegue vivo sigue teniendo
  prioridad.
- `TaskDetailDto.phases` (aditivo). **Bloqueo conocido:** `TaskDetailDto` es un modelo generado
  por OpenAPI (`openapi.model.*`), cuyos esquemas según el `.gitignore` viven en
  `ms-dev-relay-lib-java`, pero **no se encontraron** ni en la lib del workspace (rama `main`), ni en
  el jar de `.m2`, ni hay `openapi.yaml` en el servicio (lo quitó el commit `115ae6d`). Preguntar al
  usuario dónde están antes de este paso. Es la única pieza que toca la lib (orden: esquema → lib →
  servicio).
- `/sixai STATUS`: fase, gate, iteración `n/max` y qué falta para avanzar.
- Eliminar `TaskRecorder.phase()` (ya no tiene llamantes).

### Paso 6 — `enforce=true`
Solo cambio de configuración, cuando el usuario lo decida y sin `LIFECYCLE_VIOLATION` recientes.
Antes, resolver los menores pendientes (§7).

### Después: fases 2–6 de la hoja de ruta (§1)
Cada una necesita su propio diseño → spec → plan. La fase 2 (aclaraciones) usará
`PhaseStatus.BLOCKED` / `Recommendation.BLOCKED`, ya reservados en el modelo.

---

## 7. Decisiones tomadas y menores pendientes

**Decisiones ya aplicadas** (detalle en los planes):
- Sin `UNIQUE` en `task_phase`; la fase actual es la última fila.
- R3 vinculante en ambos modos.
- `INTAKE`/`REPO_SELECTION` fallidas ⇒ tarea `FAILED`.
- Resultado tardío (fase anterior o iteración anterior) ⇒ solo evidencia.
- `ESCALATED` mantiene `task_run.status = RUNNING` (se sigue viendo en el panel).
- `block-approval` sigue decidiendo R1.
- Coder: `Outcome` ampliado con `TRUNCATED`, `LLM_ERROR`, `GUARD_REJECTED`.
- GitHub apagado ⇒ `openForIssue` devuelve lista vacía; dry-run ⇒ `SKIPPED`.

**Menores pendientes (decisión del usuario):**
1. Presupuesto de correcciones: el relleno de `V6` lo cuenta por issue (como `countCycles`), pero
   en ejecución es por `task_run`: una tarea relanzada de la misma issue empieza con presupuesto
   nuevo. ¿Cuál se quiere?
2. R2 bloquea un redespliegue desde `DEPLOY_PRE FAILED` (el comando `REDEPLOY` quedaría denegado en
   estricto). La spec no lo contempla.
3. Tareas rellenadas en `APPROVAL`/`VERIFICATION FAILED` no tienen `IMPLEMENTATION PASSED` ⇒ darán
   avisos R1; cerrarlas antes de `enforce=true`.
4. Si falla la escritura del evento `LIFECYCLE_VIOLATION`, se revierte toda la escritura del
   lifecycle (y en estricto un "denegado" acabaría en "permitido").
5. Sin control de concurrencia sobre las filas de fase (carrera improbable entre un `SKIPPED` de
   `verify` en el executor async y un veredicto del sweeper).

---

## 8. Cómo arrancar la nueva sesión

1. Leer este documento, la spec y los dos planes.
2. `git -C <repo> status` y `git log --oneline -10` en la rama `feature/agents-mcp-config`.
3. Preguntar al usuario si el paso 2 compila/arranca bien y si revisó las tablas
   (`task_phase`, `task_phase_evidence`) con una tarea real o en dry-run.
4. Resultado de la revisión independiente del paso 2: si al cierre no quedó anotado aquí debajo,
   relanzar una revisión de solo lectura de los 4 ficheros del paso 2 (`CoderAgent`,
   `SelectorAgent`, `PullRequestService`, `IssueResponder`) antes de empezar el paso 3.
5. Escribir el plan del **paso 3** (`docs/superpowers/plans/…-paso-3-verificacion.md`), pedir
   revisión al usuario y ejecutarlo.

### Revisión del paso 2 (hecha)
Veredicto: aprobado; compila por lectura y el comportamiento visible no cambia. Se arregló el único
Important: el agregado de `IMPLEMENTATION` se añade **antes** del comentario de Jira, y el `catch`
de `openForIssue` ya no añade un `FAIL` si la implementación ya tenía veredicto (antes, Jira caído
al comentar dejaba la fase en `FAILED` con código real).

**Notas que heredan los pasos siguientes (de esa revisión):**
- **Paso 3 — entrega incremental:** `IssueResponder` entrega la lista entera AL FINAL, pero
  `verify` lanza Jenkins por repo mientras el coder sigue con los demás. Cuando la verificación
  entregue resultados, un build rápido podría llegar con la tarea aún en `IMPLEMENTATION` →
  `ORDER`. Solución prevista: pasar un `Consumer<PhaseOutcome>` a `openForIssue` para entregar cada
  resultado en el momento (y los `STARTED/SKIPPED` de `verify` después del agregado).
- **Paso 3 — dry-run de GitHub:** `IMPLEMENTATION` queda `SKIPPED` sin evidencias `PR`, así que
  `VERIFICATION` no tiene repos esperados y se quedaría `IN_PROGRESS` para siempre. Cerrarla como
  `SKIPPED` cuando la implementación no tuvo PRs.
- **Decidir con el usuario:** con `maestro.coder.dry-run=true` cada repo lleva placeholder → la
  implementación queda `FAILED` en una simulación. Podría mapearse a `SKIPPED` cuando todos los
  repos sin código son `DRY_RUN`/`DISABLED`.
- **Mientras llega el paso 4:** `TaskRecorder.phase` (desde `DeploymentCoordinator`) sigue
  escribiendo `TEST`/`PROD` en `task_run.current_phase`, la misma columna que ahora escribe el
  lifecycle. El panel no la lee (usa eventos), pero en BD pueden verse valores mezclados.
- Cosméticos: `GUARD_REJECTED` también cubre "tool no autorizada" del runtime; un error transitorio
  de Jira en la selección termina la tarea en `FAILED` (decisión 3 del paso 2).
