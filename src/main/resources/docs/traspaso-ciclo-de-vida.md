# Traspaso de sesión — Ciclo de vida explícito de las tareas (sixai / dev-relay)

> Documento para que otra sesión de IA continúe el trabajo **sin repetir nada**. Léelo entero
> antes de tocar código. Fecha de corte: 2026-09-24.

---

## 1. Qué se busca (el objetivo grande)

El usuario quiere que **dev-relay (sixai) trabaje como `MetaOrchestrator`** (el orquestador de
agentes de metacontext) cuando resuelve una tarea de Jira: más "senior", menos tubería implícita.

De la comparación con `MetaOrchestrator` + el skill `meta-sdd` salió esta hoja de ruta de mejoras
(el orden lo acordó el usuario):

1. **Máquina de estados explícita + contrato de handoff** ← ✅ completa (pasos 1-5; paso 6
   `enforce=true` pendiente de decisión del usuario).
2. **Bucle de aclaraciones por Jira** ← ✅ v1 implementada (como "Fase C" del mapeo de
   mejoras-senior, `§8`): un especialista puede devolver preguntas; la fase queda `BLOCKED`, se
   preguntan en un comentario de Jira y, con la respuesta, se **reanuda el mismo especialista**
   (solo `CoderAgent` por ahora — ver `§8` Fase C para el alcance exacto).
3. **Clasificación por tracks** (simple / moderate / complex) y **fase de plan** con criterios de
   aceptación ← ✅ v1 implementada (ver más abajo).
4. **Remediación antes de la aprobación** ← ✅ v1 implementada (como "Fase D" del mapeo de
   mejoras-senior, `§8`): si la verificación falla, diagnóstico + coder reintentan sobre la MISMA
   PR, con presupuesto (reutiliza R3) y clasificando la causa raíz (código → coder; plan →
   planner; criterio → humano; infraestructura → reintento sin tocar código).
5. **Coder delegado a un runtime de agente de verdad** (bucle con tools, ediciones por parche,
   compilar antes de entregar) ← ✅ v1 implementada (ediciones por parche; ver más abajo).
   "Compilar antes de entregar" se decidió **no** construirlo como gate síncrono nuevo — ver
   justificación en la sección de la Fase 5.
6. **Verificación contra criterios de aceptación + retro** que escribe lecciones en los repos ←
   ✅ v1 implementada (ver más abajo).

### Fase 3 — track + plan de aceptación (esta sesión)
Sin documento de diseño previo (a diferencia de mejoras-senior para las fases 2 y 4), así que se
diseñó desde cero con el usuario antes de escribir código:

- **Nueva fase `TaskPhase.PLAN`**, entre `REPO_SELECTION` e `IMPLEMENTATION`. Coste de insertarla
  en el enum: ninguno en BD (`@Enumerated(EnumType.STRING)`, se guarda por nombre, no por
  posición) ni en la lib (`TaskPhaseDto.phase` ya era `type: string` libre). Si falla, termina la
  tarea (`failureIsTerminal()`), igual que `INTAKE`/`REPO_SELECTION` — no tiene camino de
  corrección propio y en la práctica no debería fallar nunca (ver abajo).
- **`PlannerAgent.classifyTrack(issueKey, title, description)`** (nuevo, llamada directa como
  `DiagnosticianAgent.classifyVerificationFailure`, sin pasar por el bucle de tools — no hace
  falta árbol de repo): clasifica `SIMPLE`/`MODERATE`/`COMPLEX` y extrae criterios de aceptación
  del título+descripción de Jira. Best-effort real: nunca lanza, `MODERATE` sin criterios es el
  valor por defecto sin IA o si el JSON no parsea — así la fase `PLAN` prácticamente no puede
  fallar por su cuenta.
- **`PullRequestService`**: tras `REPO_SELECTION PASS` y solo en el primer arranque (no en
  correcciones — `reroute()` abre `IMPLEMENTATION` directamente, sin pasar por `PLAN` de nuevo, y
  el track es del pedido original, no cambia por corregir algo), llama a `classifyTrack` y cierra
  `PLAN` con el track (evidencia `DECISION`) y los criterios (evidencia `PLAN`, si los hay).
- **Deliberadamente NO implementado (fuera del alcance de esta frase del roadmap):** el track no
  **cambia** todavía ningún comportamiento (no salta pasos para `SIMPLE` ni exige más para
  `COMPLEX`) y los criterios de aceptación no se **verifican** contra nada — son solo visibilidad
  por ahora. Ambas cosas encajarían en la Fase 6 (verificación contra criterios), que sigue sin
  diseñar.

### Fase 5 — coder con runtime real: ediciones por parche (esta sesión)
- **`CoderAgent`** gana una tercera acción de `changes` además de `CREATE`/`UPDATE`/`DELETE`:
  `PATCH` (`{"path", "action": "PATCH", "search", "replace"}`), pensada para tocar poco de un
  fichero que el coder ya leyó, en vez de que el LLM tenga que reescribirlo entero. Se resuelve en
  `toChangeSet`/`patchChange` (nuevo) ANTES de construir el commit: sigue siendo un `UPDATE` con el
  contenido completo ya parcheado — el tool `github.commit` y el guardarraíl no cambian.
- **`patchChange` reconstruye sobre el contenido COMPLETO** leído por `github.read_file`
  (`findMessage(context, "github.read_file:" + path)`), no sobre el `readContext` que se mete en
  el prompt — ese se trunca por el principio (`collectReadContext`/`truncate`) para no reventar el
  contexto del LLM en ficheros grandes; usar la versión truncada como "original" habría corrompido
  el fichero real al aplicar el parche sobre una copia incompleta.
- **Sin fuzzy matching**: `search` tiene que aparecer EXACTAMENTE una vez en el fichero leído. Si
  no aparece, o aparece más de una vez, el `PATCH` se descarta entero (best-effort, igual que un
  `CREATE`/`UPDATE` sin `content`) — evita que un parche ambiguo corrija el fichero equivocado o
  el sitio equivocado sin que nadie se dé cuenta hasta revisar el diff a mano.
- Skill `coder-generate-changes.md` actualizado con el formato `PATCH` (y, de paso, con el
  mecanismo `NEEDS_INPUT`/pregunta de la Fase C, que se había quedado sin documentar ahí).
- **Deliberadamente NO implementado — "compilar antes de entregar":** no se añadió un paso
  síncrono que compile en el runtime del coder antes del commit/PR. Motivo: el objetivo real de
  ese ítem (no dejar avanzar código que no compila) ya lo cubre lo que existe desde el paso 3 —
  `VERIFICATION` compila cada repo tras el commit y una `APPROVAL` en `FAILED`/`IN_PROGRESS` no
  deja pasar a `DEPLOY_PRE` — más la Fase D (reintento automático sobre la misma PR si falla). Un
  segundo compilador *dentro* del bucle del agente sería una segunda fuente de verdad con el mismo
  Jenkins, más lento por iteración (una llamada a CI extra en cada vuelta del coder) y sin ganar
  nada que la Fase D no arregle ya después de la PR. Si en el futuro se quiere feedback de compile
  DENTRO del propio bucle del coder (antes de comprometerse a un commit), sería una `AgentAction`
  nueva de tipo `TOOL` hacia el mismo `jenkins.get_console`/build, no un cambio de arquitectura.

### Fase 6 — criterios de aceptación + retro (esta sesión, cierra el roadmap grande)
Dos piezas independientes, ambas puramente informativas (nunca bloquean nada):

**Verificación contra criterios de aceptación:**
- `PullRequestService.openPullRequests` ahora guarda, cuando el coder deja código, su resumen tal
  cual como evidencia `EvidenceKind.REPORT` de `IMPLEMENTATION` (antes solo se usaba para decidir
  `coded`/enseñarlo en el link de la PR, no se persistía el texto).
- `PlannerAgent.checkAcceptance(issueKey, criteria, changesSummary)` (nuevo, mismo patrón directo
  que `classifyTrack`): dados los criterios de PLAN y los resúmenes de `IMPLEMENTATION`, dice si
  parecen cubiertos. Best-effort real: sin IA, sin criterios capturados, sin resúmenes, o si el
  JSON no parsea, no se avisa de nada — `met=true` es el valor por defecto en todos esos casos
  (nunca se avisa de un problema que no se puede confirmar).
- `VerificationOrchestrator.finishSuccess` dispara `checkAcceptanceCriteria` en cuanto el aggregate
  de `VERIFICATION` cierra en `PASSED` (mismo patrón que el disparo de la Fase D para `FAILED`,
  pero mirando el HISTORIAL en vez de `snapshot.current()`: al cerrar en `PASSED`, la tarea ya
  avanzó a `APPROVAL` dentro del mismo `accept()`). Si el planner cree que falta algo, comenta en
  la tarea y añade evidencia `REVIEW` a `VERIFICATION` como **evidencia tardía** (la fase ya está
  cerrada; `TaskLifecycle.apply` ya tenía este camino desde el diseño original — `isLate` — para
  guardar evidencia sin reabrir ni mover la tarea). **Nunca bloquea `APPROVAL`**: es exactamente lo
  que se dejó pendiente en la Fase 3 ("los criterios no se verifican contra nada").
- Nuevo flag `maestro.verification.check-acceptance-criteria` (`true` por defecto) para apagar solo
  esto sin apagar la verificación de compilación. Depende también de `maestro.planner.enabled`.

**Retro que escribe lecciones:**
- `RetroService` (nuevo, `control/retro/`, plain service — no es un `Agent`, no necesita bucle de
  tools, igual que `CorrectionService`/`PromotionService`): al llegar una tarea a `DONE`, si hizo
  falta al menos una corrección (`task_run.remediation_iterations > 0`), reúne toda la evidencia
  `FAIL` del recorrido, le pide al LLM UNA lección corta y práctica (rol nuevo `LlmRoles.RETRO`), y
  la anota en el `AGENTS.md` de cada repo que tocó la tarea (fichero configurable,
  `maestro.retro.target-file`), en una sección `## Lecciones de sixai`, con un commit directo a
  `develop` vía `GithubClient.putFile` (sin PR: es documentación, no código a revisar).
- **Deliberadamente lazy — sin señal, sin retro:** una tarea que compiló y se aprobó a la primera
  no genera ninguna lección; llenar `AGENTS.md` de notas de tareas que salieron bien solo sería
  ruido. La señal es una sola condición simple (`iteration > 0`), no una heurística elaborada.
- **Simplificación deliberada de dónde se inserta la lección:** siempre al FINAL del fichero, tras
  la cabecera de lecciones si ya existe (o creándola si no). Si `AGENTS.md` tuviera más secciones
  DESPUÉS de la de lecciones, la nueva entrada se cuela al final del fichero entero, no justo bajo
  la cabecera — para un `AGENTS.md` dedicado a esto (el caso normal) no importa; hacerlo bien de
  verdad requeriría parsear secciones Markdown, que no está ni pedido ni hace falta para la v1.
- Enganchado en `DeploymentCoordinator.complete()`, justo después del `accept()` que cierra
  `PROMOTION` en `PASSED` (y con ello la tarea en `DONE`): en ese punto la tarea ya no está
  `RUNNING`, así que la retro **no toca el lifecycle** — es un efecto secundario de after-DONE, no
  parte de la máquina de estados. Apagado por defecto (`maestro.retro.enabled=false`) y con
  `dry-run=true` por defecto, mismo patrón que el resto de interruptores nuevos.
- **Deliberadamente NO implementado:** no se distingue `AGENTS.md` de `CLAUDE.md` (un solo fichero
  configurable, no ambos ni autodetección de cuál usa cada repo) y la lección es la MISMA para
  todos los repos que tocó la tarea, no una por repo (un fallo de verificación ya se diagnostica
  por repo en la Fase D; la retro solo agrega esa evidencia en una narrativa, no vuelve a
  diagnosticar nada).

Con esto se cierran las 6 fases del roadmap grande de `§1` (paso 6 `enforce=true` sigue pendiente
de decisión del usuario) y las 33 mejoras de `dev-relay-lifecycle-mejoras-senior.md` (`§8`).

Principio que se mantiene en todo: **el orquestador de dev-relay es código determinista, no un
LLM**. La inteligencia vive solo en los especialistas (agentes).

> Existe además una propuesta más amplia y detallada, `dev-relay-lifecycle-mejoras-senior.md`
> (33 mejoras en Fases A-F), que profundiza técnicamente en estas mismas 6 fases y en cómo
> endurecer lo ya construido en los pasos 1-2. No sustituye este roadmap; ver el mapeo punto por
> punto en `§8`. Se adopta **por fases, sin bloquear los pasos en curso** (preferencia del
> usuario de ir fase a fase, ver `feedback_phased_execution` en memoria).

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
| `src/main/resources/docs/dev-relay-lifecycle-mejoras-senior.md` | Propuesta ampliada de mejoras (Fases A-F). Mapeo contra este roadmap en `§8`. |

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

### Paso 3 — Verificación ✅ implementado (pendiente de compilar/probar y de revisión independiente)
- `VerificationService.verify(...)` ahora devuelve `PhaseOutcome` de `VERIFICATION` por repo:
  `SKIPPED` (verificación apagada, sin job de build o dry-run), `STARTED` (encolado, evidencia
  `BUILD` con la `queueUrl`) o `FAIL` (no se pudo encolar).
- `VerificationOrchestrator` (sweeper `@Scheduled`, punto de entrada) entrega `PASS`/`FAIL` por
  repo a `TaskLifecycle.accept` en `finishSuccess`/`finishFailure` (esta última cubre también el
  timeout por sondeos agotados). La agregación por repos ya estaba en `LifecycleRules.resolve`.
- **Entrega incremental:** `PullRequestService.openForIssue` cambió de `List<PhaseOutcome>` a un
  `Consumer<PhaseOutcome> onOutcome` que se invoca EN CUANTO se produce cada resultado (no al
  final del bucle de repos): el sweeper corre en su propio hilo y un build rápido podía adelantarse
  al `IMPLEMENTATION` de un repo aún sin entregar. `IssueResponder` pasa
  `outcome -> taskLifecycle.accept(issueKey, outcome)`; `CorrectionService` pasa un consumidor
  vacío (`outcome -> { }`) porque todavía no le entrega nada al lifecycle (paso 4).
- **Orden dentro de `openPullRequests`:** los resultados de `verify` por repo se acumulan aparte
  (`verificationOutcomes`) y se entregan **después** del agregado de `IMPLEMENTATION`, no según se
  van generando en el bucle — si no, llegarían con la fase todavía en `IMPLEMENTATION` (`ORDER`).
- **Cierre de respaldo:** si el agregado de `IMPLEMENTATION` es `SKIPPED` y no hubo ningún
  `verify` (dry-run global de GitHub: ningún repo llegó a abrir PR), se emite un agregado
  `VERIFICATION SKIPPED` con motivo "sin PRs que verificar (dry-run)" — si no, `VERIFICATION` se
  quedaría `IN_PROGRESS` para siempre y la tarea nunca llegaría a `APPROVAL` (gap ya anotado por la
  revisión del paso 2, ver más abajo).
- Ojo, sigue igual que estaba previsto: si `IMPLEMENTATION` queda `FAILED` (todo placeholder), los
  veredictos de verificación SÍ llegan (hubo PRs reales, con placeholder) y agregan con normalidad;
  la tarea se queda en `IMPLEMENTATION FAILED` a la espera de una corrección, que es lo esperado
  (decisión ya tomada, `§7`).
- **No tocado:** `CorrectionService` (decisión 6 del paso 2, sigue para el paso 4); la decisión
  pendiente sobre `maestro.coder.dry-run=true` → `IMPLEMENTATION FAILED` (menor pendiente, `§7` /
  mapeo `§8` Fase A ítem 7) tampoco se tocó aquí.

### Estado de git
El usuario commitea por su cuenta. Al cierre del paso 2, los pasos 1 y 2 estaban sin commitear
(comprobar con `git status`). El paso 3 (este cambio) tampoco está commiteado.

---

## 6. Lo que queda por hacer

### Paso 4 — Aprobación, despliegue, promoción, comandos y corrección ✅ implementado (pendiente de compilar/probar y de revisión independiente)
- `ApprovalService.doApprove`: tras el freno existente de `verificationService.blocks()` (que
  sigue siendo el que de verdad frena hoy), `check(issueKey, APPROVAL)`; denegado ⇒ comentario en
  Jira con el motivo (solo llega a pasar en modo estricto). `accept(APPROVAL PASS)` con
  `actor = aprobador`; si había verificación fallida sin bloquear, evidencia `DECISION` "aprobado
  con verificación fallida por X" (R1, señalado en la revisión del paso 1). `mergeAll` añade
  evidencia `PR` de `DEPLOY_PRE STARTED` con los repos mergeados (la fase ya está abierta por el
  `accept` de `APPROVAL`, así que esto solo añade evidencia a la misma fila).
- `DeploymentService.startBatch`: es el único choque de PRE y PROD (aprobación y promoción pasan
  las dos por aquí), así que ahí van `check(DEPLOY_PRE | PROMOTION)` (justo al lado de la
  comprobación de lote `RUNNING`, que **se mantiene** igual) y el `accept` del desenlace inmediato:
  `SKIPPED` en dry-run, `FAIL` si ningún repo arranca, `STARTED` con cuántos arrancaron y cuáles
  quedaron fuera si no.
- `DeploymentCoordinator`: `complete()` entrega `DEPLOY_PRE PASS` (PRE) o `PROMOTION PASS` (PROD,
  que al ser la última fase hace que el lifecycle cierre la tarea en `DONE` solo); `abort()` entrega
  `FAIL` con el detalle por repo como evidencia `REASON`. **Quitados** los dos
  `taskRecorder.phase(...)` (PRE y PROD) y el `taskRecorder.finish(...)` de PROD: pisaban
  `current_phase` con el nombre del estado de Jira ("TEST") o "PROD" a la vez que el lifecycle
  escribía la fase real ahí, y el cierre en `DONE` ahora lo hace el lifecycle una sola vez.
- `CommandService`: `PROMOTE` autorizado ⇒ `CLIENT_TEST PASS` (actor = autor) antes de llamar a
  `PromotionService`; el `check(PROMOTION)` real lo hace `DeploymentService.startBatch` (no hacía
  falta duplicarlo aquí). `REVISE` ⇒ `CLIENT_TEST FAIL` con la instrucción como evidencia `REASON`,
  y luego la petición de corrección de siempre.
- `PromotionService.promote`: `FAIL` si no hay PRs mergeadas que promocionar, o si ningún merge a
  producción salió bien — **sin esto, `PROMOTION` se habría quedado `IN_PROGRESS` para siempre**
  (nada más la cierra); si hay merges, evidencia `PR` de `PROMOTION STARTED` antes de arrancar el
  despliegue (mismo patrón que `ApprovalService`).
- `CorrectionService`: `countCycles`/`events` (ya no se usan; se han quitado el campo y el
  método) sustituidos por `taskLifecycle.reroute(issueKey, instruction, actor)`, llamado **antes**
  de `openForIssue` — abre la iteración siguiente de `IMPLEMENTATION` de una vez, para que los
  resultados de `openForIssue` lleguen con la fase ya correcta. Denegado (hoy, en modo registro,
  solo puede ser R3 — el presupuesto agotado; R4 no frena de verdad hasta el modo estricto) ⇒
  mismo comportamiento de "gave up" que antes (la tarea ya quedó en `ESCALATED`, con evidencia
  `CORRECTION_BUDGET_EXCEEDED` desde `TaskLifecycle.escalate`). El `dry-run` se comprueba **antes**
  de llamar a `reroute`: si no, consumiría presupuesto real por una simulación. `openForIssue`
  ahora entrega sus resultados con `outcome -> taskLifecycle.accept(issueKey, outcome)` (antes,
  consumidor vacío).

### Paso 5 — Lectura ✅ implementado (pendiente de **reconstruir la lib** + compilar el servicio; ver más abajo)
- `TaskLifecycle.snapshot(issueKey)` → `Optional<LifecycleSnapshot>` (nuevo, en `control/lifecycle/`):
  fase actual, estado, iteración (`task_run.remediation_iterations`) e historial completo
  (`List<PhaseHistoryEntry>`, cada uno con su `List<EvidenceEntry>`). A diferencia de
  `check`/`accept`/`reroute`, lee **cualquier** tarea (viva o ya terminada), no solo la `RUNNING`.
  Tipos nuevos: `LifecycleSnapshot`, `PhaseHistoryEntry`, `EvidenceEntry` (con `EvidenceEntry.of`
  para construirse desde `PhaseEvidence`).
- `TaskMonitorService.stageOf` ahora recibe el snapshot ya calculado (se pide una vez en `detail()`
  y se reutiliza) y traduce fase+estado a `Stage` con la tabla de la spec §7 (**las `stageKey` no
  cambian**); `fromEvent` **eliminado**. Un despliegue vivo sigue teniendo prioridad (sin cambios
  ahí). Extendí la tabla más allá de la spec, mismo patrón que `DEPLOY_PRE FAILED`: `PROMOTION
  FAILED` también da "Con fallos"/`FAILED` (la spec no lo listaba explícitamente, pero es el mismo
  caso).
- `/sixai STATUS` (`CommandService.statusReport`) ahora antepone fase, estado, iteración `n/max`
  (`CorrectionProperties.getMaxCycles()`), qué falta (con un cálculo real de repos pendientes de
  veredicto en `VERIFICATION`, comparando la evidencia `PR` de `IMPLEMENTATION` contra los
  veredictos ya recibidos) y qué comandos tienen sentido ahora — la lista de PRs abiertas se
  mantiene igual, debajo.
- `TaskRecorder.phase()` y, además, `TaskRecorder.finish()` **eliminados**: el paso 4 ya les había
  quitado su único llamante a los dos (no solo a `phase()` como decía el plan); `TaskLifecycle`
  cierra la tarea llamando a `TaskRun.close(...)` directamente.
- `TaskDetailDto.phases` — **bloqueo resuelto**: el esquema real vive en el repo de la lib,
  `C:\Users\sixtu\Dev\proyectos\ms-dev-relay-lib-java` (rama `main`), que **ya tenía sin commitear**
  el spec completo de `/sixai` (`common/src/main/resources/static/openapi.yaml` +
  `openapi/schemas/sixai/*.yaml` + `openapi/paths/sixai/*.yaml` — trabajo previo de otra sesión, no
  tocar lo demás). Añadidos ahí `TaskPhaseDto.yaml` y `TaskPhaseEvidenceDto.yaml`, y `phases` a
  `TaskDetailDto.yaml`. En el servicio: `panel/monitor/TaskDetailDto` (el DTO interno, **no** el de
  OpenAPI) gana `phases` + records `TaskPhaseDto`/`TaskPhaseEvidenceDto`; `TaskMonitorService.detail`
  los rellena desde el snapshot; `SixaiApiDelegateImpl.getTaskDetail` los mapea a
  `openapi.model.TaskPhaseDto`/`TaskPhaseEvidenceDto`.
  **Orden de compilación obligatorio** (el servicio NO compila hasta que se siga):
  1. `mvn install` (o `generate-sources` + `install`) en `ms-dev-relay-lib-java` — regenera
     `openapi.model.TaskPhaseDto`/`TaskPhaseEvidenceDto` con los esquemas nuevos y los deja en
     `.m2`. Ojo: ese repo tiene además 4 ficheros de clientes (GitHub/Jenkins) modificados sin
     commitear, ajenos a este cambio — no son míos, no los toqué, pero compilan juntos.
  2. Compilar `ms-dev-relay-java` (este servicio) normalmente.

### Paso 6 — `enforce=true`
Solo cambio de configuración, cuando el usuario lo decida y sin `LIFECYCLE_VIOLATION` recientes.
**Checklist de `§7` actualizada esta sesión:** de los 5 menores pendientes, el 4 y el 5 quedaron
resueltos con código (transacción propia para `LIFECYCLE_VIOLATION`, lock para la carrera de
hilos) y el 1 quedó zanjado sin código (por `task_run`, que es lo que ya hace el runtime). Quedan
dos, y ninguno es código: el 2 es un no-op hoy (`REDEPLOY` no está automatizado, así que R2 no
tiene nada que denegar todavía) y el 3 es repasar datos en BD (cerrar a mano las tareas viejas que
darían aviso R1) justo antes de activarlo.

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
1. ✅ **Zanjado (esta sesión, sin código):** se queda por `task_run`, que es lo que la ejecución ya
   hace hoy (`reroute()` incrementa `task_run.remediation_iterations`, no un contador por issue).
   Lo que contaba distinto era solo el relleno **puntual** de `V6` al escribir el histórico de
   tareas ya en curso en ese momento — un script que ya corrió una vez, no un comportamiento
   recurrente que haya que resolver en código. No hace falta tocar nada más para `enforce=true`.
2. **Sigue pendiente, pero hoy es un no-op:** `CommandService` responde a `REDEPLOY` con
   "anotado, pero todavía no está automatizado" — no llama a `check(DEPLOY_PRE)` ni a nada del
   lifecycle, así que R2 no puede denegarlo hoy porque el comando no llega a preguntarle nada.
   Si algún día se automatiza de verdad, ahí sí habrá que decidir si R2 debe permitir reentrar en
   `DEPLOY_PRE FAILED` para reintentar (la spec no lo contempla explícitamente).
3. **Sigue pendiente — es una tarea de datos, no de código:** antes de activar `enforce=true`, revisar
   en BD si hay tareas en `APPROVAL`/`VERIFICATION FAILED` sin `IMPLEMENTATION PASSED` (del relleno
   de `V6`) y cerrarlas a mano; si no, R1 las bloqueará con avisos en cuanto se active el modo
   estricto. No es algo que se pueda resolver con un cambio de código — depende de qué tareas reales
   haya en la base en ese momento.
4. ✅ **Resuelto (esta sesión):** `TaskLifecycle.decide()` ya no escribe el evento
   `LIFECYCLE_VIOLATION` dentro de la MISMA transacción que decide/aplica el resultado — nuevo
   método `recordViolation` (misma receta que `safely`: `TransactionTemplate` con
   `PROPAGATION_REQUIRES_NEW`, error capturado fuera). Antes, si ese `INSERT` fallaba, arrastraba en
   el rollback la escritura real del ciclo de vida — el colmo sería que un fallo al AVISAR de una
   violación acabara revirtiendo lo que en modo registro se había decidido "permitir". Ahora el aviso
   vive en su propia transacción independiente: si falla, se loguea y punto, igual que ya prometía
   la clase `TaskRecorder` ("nunca puede tumbar el trabajo real") pero no se cumplía del todo aquí.
5. ✅ **Resuelto (esta sesión):** `TaskRunRepository.lockLiveTasks` (nuevo, `@Lock(PESSIMISTIC_WRITE)`
   + `@Query`, `SELECT ... FOR UPDATE`) sustituye a `findFirstByIssueKeyAndStatusOrderByStartedAtDesc`
   dentro de `TaskLifecycle.liveTask` (el único sitio que lo usaba). Serializa a los dos hilos que
   de verdad pueden solaparse en la misma tarea — el executor `@Async` que abre PRs y los sweepers
   `@Scheduled` de verificación/despliegue son pools distintos — así que ya no hay carrera entre un
   `SKIPPED` de `verify` y un veredicto del sweeper: el segundo en llegar espera a que el primero
   termine su transacción y decide sobre el estado ya actualizado, no sobre una foto obsoleta. Sin
   coste para las lecturas: `TaskLifecycle.snapshot` (panel/`STATUS`) sigue leyendo sin `FOR UPDATE`,
   así que nunca espera por este lock. **No tocado:** `TaskRecorder.current` (usa el método sin
   lock) — solo toca columnas descriptivas (`title`/`epic`/`systemName`) y `task_event`, no
   `task_phase`, así que no participa en la misma carrera y añadirle el lock no aportaba nada.

---

## 8. Mapeo con la propuesta de mejoras senior (`dev-relay-lifecycle-mejoras-senior.md`)

> Ese documento no sustituye este roadmap: es una propuesta técnica más detallada para las mismas
> fases de `§1` y para endurecer lo ya construido en los pasos 1-2. Se adopta **de forma
> incremental, por fases, sin bloquear los pasos en curso**.

**Cuidado con la numeración — conviven tres esquemas distintos:**
- **"Fase 1–6"** (`§1`): la hoja de ruta grande acordada con el usuario (máquina de estados,
  aclaraciones, tracks, remediación, coder-runtime, retro).
- **"Paso 1–6"** (`§5–§6`): los pasos de implementación de la **Fase 1** únicamente (modelo,
  arranque, verificación, aprobación/despliegue, lectura, enforce).
- **"Fase A–F"** (mejoras-senior): agrupación propia de esa propuesta (endurecer modelo,
  auditabilidad, ejecución humana/asíncrona, remediación, observabilidad, enforcement).

Leyenda: ✅ decisión ya tomada / cubierta — 🔜 encaja en un paso o fase ya planificada — 🆕 nueva,
sin hueco asignado todavía.

### Fase A — endurecer el modelo
| # | Mejora | Estado | Dónde encaja |
|---|---|---|---|
| 1 | `Transition` formal (from/event/guard/action/estado resultante) | 🆕 | Sin hueco; `LifecycleRules`/`TaskLifecycle.check` ya validan transiciones pero no como objeto explícito. Valorar tras cerrar el paso 4, no antes. |
| 2 | `Attempt` como concepto propio (iteración ≠ intento ≠ fase) | 🆕 sin hueco | El menor pendiente 1 de `§7` (presupuesto por issue vs por `task_run`) ya se zanjó **sin necesitar este concepto**: por `task_run` es lo que ya hace el código, no hacía falta un `Attempt` distinto de la iteración para decidirlo. Sigue sin ningún consumidor real que lo necesite (ítem repetido como Fase D #21) — no se construye especulativamente. |
| 3 | `LifecycleEvent` (taxonomía estable de eventos) | ✅ con alcance reducido (esta sesión) | No se construyó una taxonomía genérica de transición (`PHASE_STARTED`/`PHASE_CLOSED`...) porque ya es redundante: eso es exactamente lo que `task_phase`/`task_phase_evidence` registran con más detalle (fase, estado, evidencia), y el panel ya lo lee vía `LifecycleSnapshot` (paso 5). Lo que SÍ faltaba de verdad, revisando `PullRequestService`, era un hueco concreto: cuando el coder pregunta (Fase C) o retoma tras la respuesta, no quedaba NINGÚN rastro en `task_event` — solo en la evidencia de la fase, que es una lista distinta de la que pinta el timeline del panel (`TaskDetailDto.timeline` vs `.phases`). Nuevos `TaskEventType.AGENT_NEEDS_INPUT`/`AGENT_RESUMED`, registrados en los 3 sitios de `PullRequestService` donde el coder se bloquea o retoma (arranque, segunda pregunta al reanudar, reanudación con éxito). |
| 4 | Idempotencia (`transitionId`/`sourceEventId`/`phaseAttemptId`) | ✅ implementado, con alcance reducido | La propuesta original pedía claves de idempotencia explícitas en cada evento; en la práctica, revisando dónde podía llegar de verdad un `accept()` duplicado, el hueco real no era "el mismo evento entregado dos veces" (los sweepers son poll-based y cada fila solo se procesa una vez: `runs.findByStatus(RUNNING)` ya excluye lo ya resuelto) sino una **carrera entre hilos** — el executor async del arranque y los sweepers corren en pools distintos y pueden solaparse en la misma tarea. Se resolvió serializando con un lock (`TaskRunRepository.lockLiveTasks`, `§7.5`) en vez de añadir claves de idempotencia por evento a toda la base de código: mismo objetivo (que activar `enforce=true` no empiece a rechazar algo que en realidad ya estaba resuelto por el otro hilo), con un cambio de dos ficheros en vez de tocar cada sitio que construye un `PhaseOutcome`. |
| 5 | Concurrencia (versionado optimista, resultados tardíos) | ✅ | Regla "resultado tardío → solo evidencia" ya decidida (`§7`). El control de concurrencia real (menor pendiente 5 de `§7`) se resolvió esta sesión con el mismo lock del ítem 4 — no hizo falta versionado optimista aparte. |
| 6 | Separar estado actual / historial | ✅ | Ya decidido: `task_phase` sin `UNIQUE`, fase actual = última fila. El propio documento lo valida como implementación inicial razonable; no requiere cambio salvo problema real de volumen. |
| 7 | `reasonCode` (en vez de solo texto libre en `detail`) | ✅ implementado | Nuevo enum `FailureReason` (`BUILD_FAILED`, `TEST_FAILED`, `NO_CHANGES`, `REPO_NOT_FOUND`, `AGENT_ERROR`, `HUMAN_REJECTED`, `ACCEPTANCE_CRITERIA_FAILED`, `CORRECTION_BUDGET_EXCEEDED`, `EXTERNAL_SERVICE_UNAVAILABLE`). `Evidence` gana un 4º campo opcional (constructores/factories viejos se mantienen). `PhaseEvidence` gana columna `reason_code` (`V7__lifecycle_reason_code.sql`). Puesto ya en `VerificationOrchestrator` (`BUILD_FAILED`), `VerificationService` (`EXTERNAL_SERVICE_UNAVAILABLE`), `PullRequestService` (`REPO_NOT_FOUND`, `EXTERNAL_SERVICE_UNAVAILABLE`, `NO_CHANGES`/`AGENT_ERROR` según el outcome del coder) y en `TaskLifecycle.escalate` (`CORRECTION_BUDGET_EXCEEDED`, que ahora además queda como evidencia — antes `ESCALATED` no dejaba ninguna). **No tocado todavía:** `HUMAN_REJECTED` no tiene llamante (no existe rechazo humano formal); `TEST_FAILED` tampoco (no hay distinción build vs test todavía). `ACCEPTANCE_CRITERIA_FAILED` sí tiene llamante desde la Fase 6: `VerificationOrchestrator.checkAcceptanceCriteria` lo pone en la evidencia `REVIEW` cuando el planner cree que falta un criterio. Pendiente de compilar/probar. |

### Fase B — auditabilidad
| # | Mejora | Estado | Dónde encaja |
|---|---|---|---|
| 8 | `Evidence` enriquecida (source, actor, phase, iteration, attempt, metadata, createdAt) | ✅ parcial | `source` implementado: nuevo enum `EvidenceSource` (`SYSTEM`/`AGENT`/`JENKINS`/`GITHUB`/`JIRA`/`HUMAN`), campo 5º opcional en `Evidence` (mismo patrón que `reasonCode`: constructores/factories viejos intactos), columna `source` en `PhaseEvidence` (`V8__lifecycle_evidence_source.sql`). Puesto en la práctica totalidad de los sitios que generan evidencia (verificación → `JENKINS`, PR/merges → `GITHUB`, plan/revisión/coder → `AGENT`, aprobación/REVISE/corrección humana → `HUMAN`, Jira caída → `JIRA`, decisiones internas/dry-run → `SYSTEM`); sin clasificar solo donde de verdad es ambiguo (p. ej. la respuesta de `IssueResponder`, que puede venir del LLM o de una plantilla fija). `actor` ya existía (`PhaseOutcome.actor`, texto libre) — sigue así a propósito, `source` es la categoría, `actor` es el nombre concreto. `phase`/`iteration` no se duplican en la fila: ya llegan gratis porque cada evidencia cuelga de una `TaskPhaseRun` con esos datos, y el snapshot de lectura (`LifecycleSnapshot`) ya las agrupa así. `createdAt` = `recordedAt`, ya existía. **No implementado:** `attempt` (es el ítem 2 de Fase A, sigue sin consumidor) y `metadata` (mapa libre sin caso de uso concreto todavía — no se añade especulativamente). |
| 9 | Actores formalizados (SYSTEM/AGENT/JENKINS/GITHUB/JIRA/HUMAN) | ✅ | Es el mismo `EvidenceSource` del ítem 8 (la propuesta usa dos nombres, "source" en `§6.2` y "actores" en `§18`, para la misma lista de 6 valores) — no hacía falta un segundo enum. |
| 10 | Timeline (`GET /lifecycle/timeline`) | ✅ | Cubierto por el paso 5: `TaskDetailDto.phases` (pendiente de que compiles lib+servicio) expone el historial completo con evidencia; no se abrió un endpoint aparte porque ya viaja dentro del detalle de la tarea. |
| 11 | Explainability API (`GET /lifecycle`) | ✅ | `TaskLifecycle.snapshot(issueKey)`, implementado en el paso 5. |
| 12 | Mejorar `/sixai STATUS` | ✅ | Implementado en el paso 5: fase, estado, iteración `n/max`, qué falta (con cálculo real de repos pendientes en `VERIFICATION`) y próximas acciones. |

### Fase C — ejecución humana/asíncrona ✅ v1 implementada (pendiente de compilar/probar; ver alcance abajo)
| # | Mejora | Estado | Dónde encaja |
|---|---|---|---|
| 13 | `BLOCKED` primera clase (reason/blockingActor/question/requestedAt/answeredAt/resumeToken) | ✅ | **No hizo falta tocar `LifecycleRules`/`TaskLifecycle`**: `Recommendation.BLOCKED → PhaseStatus.BLOCKED` ya existía, y como `BLOCKED` no está "cerrado" ni "deja avanzar" (`PhaseStatus`), un outcome `BLOCKED` agregado simplemente deja la fila abierta hasta que un outcome posterior (aggregate, misma fase, no cerrada) la cierre — exactamente lo que ya preveía el modelo del paso 1. El "quién/qué/cuándo" vive como evidencia normal (`Evidence`, `EvidenceSource.AGENT`/`HUMAN`), no como columnas nuevas. |
| 14 | Preguntas de agentes | ✅ (solo `CoderAgent`) | `CoderAgent` puede responder `{"question": "..."}` en vez de cambios; `generateAndRequestCommit` lo detecta y devuelve `AgentStatus.NEEDS_INPUT`. `PlannerAgent`/`ReviewerAgent`/`SelectorAgent` no lo implementan (decisión del usuario: solo merece la pena conservar el trabajo a medias del coder). |
| 15 | Resume del mismo especialista | ✅ | Nuevo `AgentSuspension` (`ai/orchestration/`, `V9__agent_suspension.sql`): guarda `messages`+`state` del `AgentContext` (JSON) cuando el runtime ve `NEEDS_INPUT`. `AgentRuntime.resume(issueKey, repo, agentId, answer)` (nuevo método) reconstruye el contexto tal cual, añade la respuesta como mensaje y sigue el mismo bucle — nunca reinicia al coder. Reabre lo que la memoria de proyecto había marcado como fuera de alcance; decidido con el usuario antes de implementar (ver preguntas de esta sesión). |
| 16 | Decisiones humanas formalizadas (actor/action/timestamp/reason/evidence) | ✅ | Ya cubierto por `EvidenceSource.HUMAN` (Fase B) + `actor`/`recordedAt` que ya llevaba `Evidence`/`PhaseEvidence`; no hizo falta un tipo `Decision` aparte. |
| — | *(añadido más tarde, Fase A #3)* `TaskEventType.AGENT_NEEDS_INPUT`/`AGENT_RESUMED` | ✅ | El bloqueo/reanudación quedaba SOLO en la evidencia de la fase, no en el timeline de eventos (`task_event`) que pinta el panel por separado (`TaskDetailDto.timeline`). |

**Alcance de esta v1 (decisiones tomadas con el usuario en esta sesión):**
- Reconocer la respuesta: **cualquier comentario nuevo** de una persona en una tarea `BLOCKED` se trata como la respuesta, sin exigir `/sixai` delante (`CommandService.handlePendingAnswer`, llamado desde `IssueTriggerServiceImpl` antes de `handle()`).
- Solo `CoderAgent` puede preguntar. El resto de agentes no lo necesitan (pasadas cortas y de solo lectura: más barato relanzarlas enteras que construir resume para ellas).
- Si hay varios repos y uno se bloquea, **toda la fase `IMPLEMENTATION` queda `BLOCKED`** (no se cierra con los demás repos mientras uno sigue a medias); es una simplificación deliberada — el caso común es un repo por tarea.
- `PullRequestService.resumeAfterAnswer` reconstruye el título/cuerpo de la PR con el resumen/descripción **actuales** de Jira (no los que tenía la ejecución al pararse) y **no vuelve a publicar el informe de entrega** a la carpeta de la tarea. El código que se commitea no se ve afectado por ninguna de las dos cosas.
- Ficheros nuevos: `ai/orchestration/AgentSuspension.java`, `AgentSuspensionRepository.java`, `V9__agent_suspension.sql`. Modificados: `AgentRuntime`/`DefaultAgentRuntime` (método `resume`, `NEEDS_INPUT` ahora guarda el contexto), `CoderAgent` (pregunta + inyecta la respuesta en el prompt al reanudar + devuelve la rama en `data` para poder terminar la PR sin volver a adivinarla), `PullRequestService` (detecta `NEEDS_INPUT` en el bucle, `resumeAfterAnswer` nuevo), `CommandService` (`handlePendingAnswer` nuevo), `IssueTriggerServiceImpl` (lo engancha).
- **De paso, se corrigió un bug preexistente:** `AgentRuntime.java` usaba `AgentExecutionRequest`/`AgentExecutionResult` sin importarlos (viven en el subpaquete `.record`); no debería haber compilado nunca. Puede que fuera invisible porque nadie regeneraba ese fichero exacto; ahora tiene los imports correctos.

### Fase D — remediación ✅ v1 implementada (pendiente de compilar/probar; ver alcance abajo)
| # | Mejora | Estado | Dónde encaja |
|---|---|---|---|
| 17 | Diagnóstico estructurado | ✅ | `DiagnosticianAgent.classifyVerificationFailure(jobPath, buildNumber, issueKey)` (nuevo) lee la consola de Jenkins (mismo tool `jenkins.get_console` que ya usaba) y devuelve `RootCause(category, explanation)`. Si el LLM está apagado o no responde JSON válido, cae a `CODE` (el único camino que existía antes de esto). |
| 18 | Clasificación de causa raíz | ✅ | Nuevo `DiagnosticianAgent.RootCauseCategory { CODE, PLAN, CRITERIA, INFRA }`, nested en el agente (mismo patrón que `CoderAgent.Outcome`). |
| 19 | Presupuestos por capa | ✅ parcial (decisión deliberada) | **No se añadió un presupuesto nuevo por capa**: las rutas `CODE`/`PLAN`/`INFRA` reutilizan el mismo R3 (`taskLifecycle.reroute`, `maestro.correction.max-cycles`) que ya frenaba las correcciones manuales — así una causa que se resiste no puede consumir CI sin límite solo por venir de un disparo automático. `CRITERIA` no gasta presupuesto (no reintenta, solo informa). |
| 20 | Routing de remediación | ✅ | `CorrectionService.triggeredByVerificationFailure` (nuevo): `CODE` → `retryWithCoder` (coder solo) · `PLAN` → `retryWithCoder` (planner + coder) · `CRITERIA` → `escalateToHuman` (comentario, sin reroute) · `INFRA` → `retryInfra` (cierra `IMPLEMENTATION` sin tocar código y vuelve a pedir el build). |
| 21 | Attempts/iterations | 🆕 (sin cambio) | Sigue siendo el ítem 2 de Fase A: no tiene consumidor propio todavía. Cada intento de esta fase D ya usa la iteración de `reroute` existente (`task_run.remediation_iterations`), no hace falta un concepto nuevo para lo implementado aquí. |

**Decisión de esta sesión — MISMA PR, no una nueva:** a diferencia de `requestedByHuman`/`triggeredByFailure` (que siguen abriendo PRs nuevas porque la PR anterior ya se mergeó a develop), un fallo de `VERIFICATION` reintenta sobre la PR que ya está abierta — `PullRequestService.retryOnSameBranch` (nuevo) invoca al coder (y, si la causa es `PLAN`, primero al planner) sobre la misma rama; GitHub actualiza la PR sola en cuanto llega el commit, así que no hace falta volver a crear nada. Reutiliza el mismo patrón NEEDS_INPUT/BLOCKED de la Fase C si el coder necesita aclaración en el reintento.

**Disparo:** automático. `VerificationOrchestrator.finishFailure` llama a `taskLifecycle.snapshot(issueKey)` tras aceptar el `FAIL` del repo; si esa fue la fila que cerró `VERIFICATION` en `FAILED` (agregado), dispara `CorrectionService.triggeredByVerificationFailure` — si aún faltan otros repos por veredicto, no dispara nada todavía. Sigue detrás de `maestro.correction.auto-fix-on-failure` (la misma propiedad que ya disparaba la corrección en fallos de despliegue) y de `maestro.correction.dry-run`.

**Simplificaciones deliberadas:** solo un repo se diagnostica/reintenta por evento de cierre (si varios repos fallan a la vez, se actúa sobre el que cerró el agregado); `INFRA` no lleva un tope de reintentos aparte de R3 (si el runner sigue fallando, el presupuesto general acaba escalando a una persona); el diagnóstico "texto libre" que ya existía en `VerificationOrchestrator.diagnose` (para el comentario de fallo) se mantiene **sin tocar** y es una llamada al LLM aparte de la nueva clasificación — quedan dos llamadas por fallo en vez de una; se podría unificar más adelante (Fase E / limpieza).

### Fase E — observabilidad ✅ v1 implementada (pendiente de compilar/probar; ver decisión de alcance abajo)
| # | Mejora | Estado | Dónde encaja |
|---|---|---|---|
| 22–28 | Métricas por fase, duraciones, retries, violaciones, motivos de fallo, tasa de bloqueos, eficiencia de agentes | ✅ (sin librería de métricas nueva) | Nuevo `panel/metrics/LifecycleMetricsService.compute()`: calcula todo desde lo ya persistido (`task_run`/`task_phase`/`task_phase_evidence`/`task_event`), sin Micrometer/Prometheus (no era dependencia del proyecto y añadirla es una decisión de infraestructura que no estaba pedida). Cubre: tareas arrancadas/completadas/fallidas/escaladas, correcciones (manuales + automáticas, Fase D), avisos `LIFECYCLE_VIOLATION`, veces que un agente preguntó (evidencia `BLOCKED`+`AGENT`, sobrevive aunque `agent_suspension` se borre al reanudar — Fase C), fallos de agente (`reasonCode=AGENT_ERROR`), decisiones (`EvidenceKind.DECISION`) y duración media por fase. |

**Decisión de alcance (sin hueco natural, así que tocó decidir el "dónde"):** expuesto como comando nuevo **`/sixai METRICS`** (mismo patrón que `STATUS`), no como endpoint. Es un ajuste deliberado: las métricas son globales (todas las tareas), no de la tarea desde la que se preguntan, así que un comando de chat es un encaje imperfecto — pero no hacía falta tocar la lib (a diferencia de `TaskDetailDto.phases` en el paso 5) ni añadir una dependencia nueva, y es inmediatamente usable. Si más adelante se quiere un endpoint/dashboard de verdad, `LifecycleMetricsService` ya está listo para exponerse así sin cambios.
Tocado para el comando nuevo: `CommandIntent.METRICS`, `RouterAgent` (prompt + fallback por palabras clave), skill `command-routing.md`, `CommandService.metricsReport()`.
**Tasa de bloqueos, añadida esta sesión:** `LifecycleMetrics.tasksBlocked` (nuevo campo) cuenta
TAREAS con al menos una aclaración pedida por un agente — distinto de `agentQuestions`, que cuenta
preguntas (una tarea puede preguntar varias veces y solo cuenta una vez aquí). Hace falta ir
evidencia → fase → tarea porque `PhaseEvidence` no lleva el `taskRunId` directamente. Expuesto en
`/sixai METRICS` como "Tareas que necesitaron alguna aclaración: X de Y (Z%)".
**Sigue sin implementar:** "eficiencia de agentes" como tasa compuesta — se queda en los contadores
sueltos (preguntas, fallos) porque no hay una fórmula concreta pedida; inventar una habría sido
especular sin un caso de uso real detrás.

### Fase F — enforcement
| # | Mejora | Estado | Dónde encaja |
|---|---|---|---|
| 29–33 | Mantener `enforce=false` en la migración, eliminar violaciones conocidas, resolver concurrencia, pasar a `enforce=true`, mantener diagnóstico/rollback | ✅ | Es literalmente el paso 6 ya descrito (`§199-201`); esta propuesta da la checklist explícita de qué debe estar resuelto antes del salto (coincide con los menores pendientes de `§7`). |

---

## 9. Cómo arrancar la nueva sesión

1. Leer este documento, la spec y los dos planes.
2. `git -C <repo> status` y `git log --oneline -10` en la rama `feature/agents-mcp-config`.
3. Preguntar al usuario si el paso 2 compila/arranca bien y si revisó las tablas
   (`task_phase`, `task_phase_evidence`) con una tarea real o en dry-run.
4. Resultado de la revisión independiente del paso 2: si al cierre no quedó anotado aquí debajo,
   relanzar una revisión de solo lectura de los 4 ficheros del paso 2 (`CoderAgent`,
   `SelectorAgent`, `PullRequestService`, `IssueResponder`) antes de empezar el paso 3.
5. Escribir el plan del **paso 3** (`docs/superpowers/plans/…-paso-3-verificacion.md`), incorporando
   el `reasonCode` (Fase A, ítem 7 de mejoras-senior) en el diagnóstico de verificación, pedir
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
