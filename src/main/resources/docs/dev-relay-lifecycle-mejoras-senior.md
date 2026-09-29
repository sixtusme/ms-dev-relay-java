# Mejoras de arquitectura para un producto más senior y profesional

## 1. Objetivo

Este documento recoge las mejoras arquitectónicas propuestas sobre el lifecycle de tareas de `dev-relay (sixai)` para evolucionarlo desde una máquina de estados sólida hacia un producto más robusto, auditable, explicable y preparado para ejecución asíncrona, remediación y agentes autónomos.

La idea central es mantener una frontera clara:

> **El lifecycle decide qué puede ocurrir. Los agentes deciden cómo resolverlo.**

El orquestador/lifecycle debe seguir siendo código determinista. Los agentes aportan inteligencia y producen resultados, evidencias, preguntas o propuestas, pero no modifican directamente el estado del lifecycle.

---

# 2. Principios arquitectónicos

## 2.1. Separación Lifecycle / Agent Runtime

La frontera principal debe mantenerse:

```text
                ┌─────────────────────┐
                │   TASK LIFECYCLE    │
                │                     │
                │ state               │
                │ guards              │
                │ transitions         │
                │ budgets             │
                │ permissions         │
                └──────────┬──────────┘
                           │
                    delegates work
                           │
                           ▼
                ┌─────────────────────┐
                │    AGENT RUNTIME    │
                │                     │
                │ LLM                 │
                │ tools               │
                │ reasoning           │
                │ patches             │
                │ questions           │
                └─────────────────────┘
```

El agente no puede cambiar directamente el lifecycle.

El agente puede producir:

```text
Outcome
Evidence
Question
Proposal
```

El lifecycle decide qué hacer con ello.

---

# 3. Máquina de estados formal

Actualmente existen fases, estados, reglas y operaciones como `accept` y `reroute`. La siguiente evolución debería hacer de la transición un concepto explícito.

## 3.1. Modelo propuesto

```text
Task
 └── Lifecycle
      ├── Phase
      │    ├── State
      │    ├── Attempt
      │    ├── Evidence
      │    └── Decision
      └── Transition
           ├── from
           ├── event
           ├── guard
           ├── action
           └── resulting state
```

Una transición representa formalmente el cambio de estado:

```text
IMPLEMENTATION
    FAILED
       │
       ├── correction_requested
       ▼
IMPLEMENTATION
    IN_PROGRESS
```

## 3.2. Ventajas

Esto permite:

- saber exactamente por qué una tarea está en un estado;
- validar las transiciones de forma determinista;
- auditar quién o qué provocó una transición;
- evitar saltos de fase accidentales;
- reconstruir la historia de una tarea;
- hacer más sencillo el soporte y diagnóstico;
- preparar el sistema para concurrencia y reintentos.

---

# 4. Separar estado actual de historial

La decisión actual de utilizar la última fila de `task_phase` como fase actual es válida como implementación inicial, pero conviene separar conceptualmente:

```text
current state
        ≠
historical state
```

## 4.1. Modelo recomendado

```text
task
 └── current_phase_id

task_phase
 └── immutable historical phase instances

task_event
 └── immutable lifecycle events

task_phase_evidence
 └── immutable evidence
```

No es necesario implementar event sourcing puro.

La recomendación es utilizar un modelo de:

> **estado actual + historial/audit log inmutable**

Esto permite conservar una lectura rápida del estado actual sin perder la trazabilidad completa.

## 4.2. Beneficio

Debe ser posible responder:

> ¿Por qué esta tarea terminó en DONE?

y reconstruir algo como:

```text
APPROVAL
  approved by: human
  evidence: decision

VERIFICATION
  Jenkins build #182
  PASS

IMPLEMENTATION
  PR #482
  merged

CLIENT_TEST
  PROMOTE
```

---

# 5. Introducir Attempt como concepto de primera clase

Actualmente existe `remediation_iterations`, pero conviene distinguir:

```text
iteration
attempt
phase
```

## 5.1. Ejemplo

```text
Iteration 0
  IMPLEMENTATION
    attempt 1 → FAIL
    attempt 2 → PASS

Iteration 1
  IMPLEMENTATION
    attempt 1 → PASS
```

Una iteración representa una vuelta de remediación.

Un attempt representa un intento concreto de ejecutar una fase.

## 5.2. Ventajas

Esto permite distinguir correctamente:

- reintento técnico;
- nueva remediación;
- nueva ejecución del agente;
- nueva ejecución de Jenkins;
- nueva propuesta del planner;
- nueva intervención humana.

Además simplifica la medición de:

```text
phase_attempts
retry_count
remediation_count
attempt_duration
```

---

# 6. Evidence estructurada y auditable

La estructura actual:

```text
Evidence(kind, detail, url)
```

es una buena base, pero debería evolucionar hacia una evidencia más rica.

## 6.1. Modelo propuesto

```text
Evidence
 ├── id
 ├── kind
 ├── source
 ├── detail
 ├── url
 ├── createdAt
 ├── actor
 ├── phase
 ├── iteration
 ├── attempt
 └── metadata
```

## 6.2. Source

Por ejemplo:

```text
JIRA
GITHUB
JENKINS
AGENT
HUMAN
SYSTEM
```

Esto permite distinguir el origen de cada afirmación del lifecycle.

## 6.3. Resultado esperado

La evidencia debe permitir contestar:

- qué ocurrió;
- quién o qué lo produjo;
- cuándo ocurrió;
- en qué fase;
- en qué iteración;
- en qué intento;
- qué recurso externo lo demuestra.

---

# 7. Reason Codes

No conviene depender únicamente de texto libre en `detail`.

## 7.1. Modelo

```text
FailureReason
  BUILD_FAILED
  TEST_FAILED
  NO_CHANGES
  REPO_NOT_FOUND
  AGENT_ERROR
  HUMAN_REJECTED
  ACCEPTANCE_CRITERIA_FAILED
  CORRECTION_BUDGET_EXCEEDED
  EXTERNAL_SERVICE_UNAVAILABLE
```

La evidencia puede contener:

```text
reasonCode = BUILD_FAILED
detail = "Jenkins build #182 failed..."
```

## 7.2. Ventajas

Permite:

- estadísticas fiables;
- búsquedas;
- dashboards;
- automatizaciones;
- diagnóstico;
- agrupación de fallos;
- análisis de causa raíz.

Por ejemplo:

```text
¿Cuántas tareas fallaron este mes por BUILD_FAILED?
```

no debería requerir interpretar texto libre.

---

# 8. BLOCKED como estado de primera clase

El modelo ya contempla:

```text
PhaseStatus.BLOCKED
Recommendation.BLOCKED
```

La siguiente fase debe convertirlo en un concepto completamente operativo.

## 8.1. Modelo propuesto

```text
BLOCKED
 ├── reason
 ├── blockingActor
 ├── question
 ├── requestedAt
 ├── answeredAt
 └── resumeToken
```

## 8.2. Reanudación

El objetivo debe ser:

```text
BLOCKED → resume SAME execution context
```

y no:

```text
BLOCKED → restart whole workflow
```

Esto es especialmente importante para el futuro flujo de aclaraciones de Jira.

Ejemplo:

```text
SPECIALIST
    ↓
QUESTION
    ↓
BLOCKED
    ↓
JIRA COMMENT
    ↓
ANSWER
    ↓
RESUME SAME SPECIALIST
```

---

# 9. Idempotencia

Dado que el sistema utiliza:

- Jira;
- GitHub;
- Jenkins;
- procesos asíncronos;
- schedulers/sweepers;
- callbacks;
- agentes;

es esperable recibir eventos duplicados.

Por tanto:

> **Toda transición del lifecycle debe ser idempotente.**

## 9.1. Identificadores recomendados

```text
transitionId
sourceEventId
phaseAttemptId
```

## 9.2. Garantía

La siguiente operación:

```text
accept(outcome X)
accept(outcome X)
```

debe producir exactamente el mismo resultado que:

```text
accept(outcome X)
```

una única vez.

## 9.3. Beneficios

Esto reduce los riesgos de:

- doble cierre;
- doble aprobación;
- doble promoción;
- dos remediaciones simultáneas;
- evidencias duplicadas;
- inconsistencias provocadas por callbacks repetidos.

---

# 10. Concurrencia

La arquitectura debe considerar explícitamente que pueden existir dos resultados simultáneos para la misma tarea.

Ejemplo:

```text
verify executor
       │
       ├── SKIPPED
       │
       └── PASS desde sweeper
```

Ambos pueden intentar modificar la misma fase.

## 10.1. Recomendaciones

Introducir:

- versionado optimista de la tarea/fase;
- `attemptId`;
- `transitionId`;
- validación de estado antes de aplicar una transición;
- idempotencia;
- reglas explícitas para resultados tardíos.

## 10.2. Resultado tardío

Un resultado correspondiente a una fase o iteración anterior no debe alterar el estado actual.

Debe convertirse en:

```text
late result
    ↓
evidence only
```

Esto debe ser una regla formal del lifecycle.

---

# 11. Decisión ALLOW / DENY / UNKNOWN

Actualmente el comportamiento `best-effort` permite que un fallo interno del lifecycle no bloquee el trabajo real.

Como estrategia de migración es razonable.

Pero no debería convertirse en una propiedad permanente del modelo.

## 11.1. Modelo recomendado

```text
LifecycleDecision
  ALLOW
  DENY
  UNKNOWN
```

Durante la migración:

```text
UNKNOWN → ALLOW + warning
```

En un futuro modo más estricto:

```text
UNKNOWN → BLOCK
```

## 11.2. Ventaja

Se evita confundir:

```text
"el lifecycle ha comprobado que está permitido"
```

con:

```text
"el lifecycle no ha podido comprobarlo"
```

Son situaciones semánticamente diferentes.

---

# 12. Explainability API

El lifecycle debería poder explicar el estado de una tarea sin obligar al usuario a reconstruirlo a partir de logs.

## 12.1. Endpoint de estado

Conceptualmente:

```http
GET /tasks/{issueKey}/lifecycle
```

Respuesta:

```json
{
  "currentPhase": "VERIFICATION",
  "status": "BLOCKED",
  "iteration": 1,
  "progress": {
    "completed": 4,
    "total": 9
  },
  "blockedBy": {
    "reason": "BUILD_FAILED",
    "actor": "jenkins"
  },
  "nextAllowedActions": [
    "REMEDIATE",
    "ESCALATE"
  ]
}
```

## 12.2. Timeline

También:

```http
GET /tasks/{issueKey}/lifecycle/timeline
```

Debe devolver la historia de:

- transiciones;
- fases;
- intentos;
- evidencias;
- decisiones;
- actores;
- errores.

---

# 13. `/sixai STATUS`

El comando de estado debería convertirse en una vista humana del lifecycle.

Debe responder claramente:

```text
Fase actual
Estado
Iteración
Intento
Gate actual
Qué está bloqueando
Qué falta
Qué acciones están permitidas
Qué ocurrió anteriormente
```

Ejemplo:

```text
Task: SIX-123

Phase: VERIFICATION
Status: BLOCKED

Iteration: 1 / 3
Attempt: 2

Gate:
  Waiting for Jenkins verification

Blocked by:
  BUILD_FAILED

Last action:
  Coder remediation

Next allowed actions:
  REMEDIATE
  ESCALATE
```

---

# 14. Observabilidad como parte del producto

La información del lifecycle no debería quedarse únicamente en logs.

## 14.1. Métricas

Por ejemplo:

```text
tasks_started
tasks_completed
tasks_failed
tasks_escalated

phase_duration
phase_retry_count
correction_count

blocked_tasks
blocked_duration

lifecycle_violations
lifecycle_decisions

agent_failures
agent_question_rate
```

## 14.2. Lead time por fase

Ejemplo:

```text
INTAKE           3s
IMPLEMENTATION   4m 21s
VERIFICATION     1m 08s
APPROVAL         12s
DEPLOY_PRE       48s
CLIENT_TEST      2m 15s
PROMOTION        31s
```

Esto convierte el lifecycle en una fuente de información operacional.

---

# 15. Presupuestos explícitos

Los presupuestos no deberían estar únicamente ligados al contador de correcciones.

Conviene poder expresar:

```text
Task budget
Phase budget
Iteration budget
Agent attempt budget
```

Ejemplo:

```text
max remediation iterations = 3
max implementation attempts = 2
max verification retries = 3
max agent tool loops = 20
```

Esto evita que un fallo de una capa consuma indefinidamente recursos de otra.

---

# 16. Causa raíz y remediación

La remediación futura debería utilizar la información estructurada anterior.

Flujo conceptual:

```text
VERIFICATION FAILED
        ↓
Diagnosis
        ↓
Root cause
        ↓
Routing
        ├── CODE → CODER
        ├── PLAN → PLANNER
        ├── CRITERIA → HUMAN
        └── INFRA → RETRY / ESCALATE
```

El resultado del diagnóstico debe ser estructurado:

```text
RootCause
 ├── category
 ├── confidence
 ├── evidence[]
 └── recommendedAction
```

La decisión final sigue perteneciendo al lifecycle.

---

# 17. Contrato de salida de los agentes

Para que la frontera entre agentes y lifecycle sea sólida, los agentes deberían tener un contrato común.

Conceptualmente:

```text
AgentResult
 ├── status
 ├── outcome
 ├── evidence[]
 ├── questions[]
 ├── proposals[]
 ├── reasonCode
 └── metadata
```

El agente nunca debería devolver directamente:

```text
setPhase(DONE)
```

En su lugar:

```text
AgentResult
    ↓
TaskLifecycle.accept(...)
    ↓
LifecycleRules
    ↓
Transition
```

Esto mantiene el control centralizado.

---

# 18. Human-in-the-loop

La intervención humana debería ser otro actor formal del lifecycle.

Actores:

```text
SYSTEM
AGENT
JENKINS
GITHUB
JIRA
HUMAN
```

Una decisión humana debe producir:

```text
Decision
 ├── actor
 ├── action
 ├── timestamp
 ├── reason
 └── evidence
```

Por ejemplo:

```text
APPROVAL
  decision: APPROVED
  actor: human
  reason: approved despite failed verification
```

Esto es especialmente importante para mantener auditabilidad.

---

# 19. Política de eventos

Conviene definir una taxonomía estable de eventos.

Ejemplo:

```text
TASK_CREATED
PHASE_STARTED
PHASE_BLOCKED
PHASE_PASSED
PHASE_FAILED

AGENT_STARTED
AGENT_COMPLETED
AGENT_NEEDS_INPUT

VERIFICATION_STARTED
VERIFICATION_COMPLETED

APPROVAL_GRANTED
APPROVAL_REJECTED

REMEDIATION_STARTED
REMEDIATION_COMPLETED

LIFECYCLE_VIOLATION
LIFECYCLE_DECISION
TASK_ESCALATED
TASK_COMPLETED
TASK_FAILED
```

Los nombres deberían representar hechos ocurridos, no acciones ambiguas.

---

# 20. Estado frente a eventos

Regla recomendada:

> Los eventos explican lo que ocurrió. El estado explica dónde estamos.

Ejemplo:

```text
Events:
  PHASE_STARTED
  BUILD_STARTED
  BUILD_FAILED
  REMEDIATION_STARTED

Current state:
  VERIFICATION / IN_PROGRESS
```

Esto evita utilizar el event log como sustituto accidental del modelo de estado.

---

# 21. Seguridad y permisos

El lifecycle también debería convertirse en el punto de control de acciones sensibles.

Ejemplo:

```text
MERGE
DEPLOY
PROMOTE
REMEDIATE
CANCEL
ESCALATE
```

Cada acción debería pasar por:

```text
check(action)
    ↓
permission
    ↓
lifecycle guard
    ↓
budget
    ↓
transition
```

El agente puede proponer una acción, pero no saltarse estas comprobaciones.

---

# 22. Diseño recomendado de alto nivel

La arquitectura final podría evolucionar hacia:

```text
                         ┌──────────────────────────┐
                         │          TASK            │
                         └────────────┬─────────────┘
                                      │
                                      ▼
                         ┌──────────────────────────┐
                         │     TASK LIFECYCLE       │
                         │                          │
                         │ State                    │
                         │ Guards                   │
                         │ Transitions              │
                         │ Budgets                  │
                         │ Permissions               │
                         │ Idempotency               │
                         └────────────┬─────────────┘
                                      │
                     ┌────────────────┼────────────────┐
                     │                │                │
                     ▼                ▼                ▼
                ┌─────────┐     ┌──────────┐     ┌──────────┐
                │ Agents  │     │ Jenkins  │     │ GitHub   │
                └────┬────┘     └────┬─────┘     └────┬─────┘
                     │               │                │
                     └───────────────┼────────────────┘
                                     ▼
                              ┌──────────────┐
                              │   OUTCOMES   │
                              │   EVIDENCE   │
                              │   EVENTS     │
                              └──────┬───────┘
                                     │
                                     ▼
                         ┌──────────────────────────┐
                         │       LIFECYCLE          │
                         │       ACCEPT             │
                         └──────────────────────────┘
```

---

# 23. Roadmap recomendada

## Fase A — endurecer el modelo

Antes de continuar con grandes funcionalidades:

1. Formalizar `Transition`.
2. Introducir `Attempt`.
3. Definir `LifecycleEvent`.
4. Definir idempotencia.
5. Resolver concurrencia.
6. Separar estado actual de historial.
7. Introducir `reasonCode`.

## Fase B — auditabilidad

8. Enriquecer `Evidence`.
9. Formalizar actores.
10. Añadir timeline.
11. Crear explainability API.
12. Mejorar `/sixai STATUS`.

## Fase C — ejecución humana/asíncrona

13. Convertir `BLOCKED` en primera clase.
14. Implementar preguntas de agentes.
15. Implementar resume del mismo especialista.
16. Formalizar decisiones humanas.

## Fase D — remediación

17. Diagnóstico estructurado.
18. Clasificación de causa raíz.
19. Presupuestos por capa.
20. Routing de remediación.
21. Attempts e iterations.

## Fase E — observabilidad

22. Métricas por fase.
23. Duraciones.
24. Retries.
25. Violaciones.
26. Motivos de fallo.
27. Tasa de bloqueos.
28. Eficiencia de agentes.

## Fase F — enforcement

29. Mantener `enforce=false` durante la migración.
30. Eliminar las violaciones conocidas.
31. Resolver las condiciones de concurrencia.
32. Pasar progresivamente a `enforce=true`.
33. Mantener capacidad de diagnóstico y rollback.

---

# 24. Criterios de madurez

El lifecycle debería considerarse maduro cuando pueda responder de forma determinista:

### Estado

> ¿Dónde está la tarea?

```text
VERIFICATION / BLOCKED
```

### Motivo

> ¿Por qué?

```text
BUILD_FAILED
```

### Evidencia

> ¿Qué lo demuestra?

```text
Jenkins build #182
```

### Historia

> ¿Qué ocurrió antes?

```text
IMPLEMENTATION PASS
→ VERIFICATION FAIL
→ REMEDIATION
→ VERIFICATION BLOCKED
```

### Responsabilidad

> ¿Quién tomó cada decisión?

```text
SYSTEM
AGENT
HUMAN
```

### Acción

> ¿Qué puede ocurrir ahora?

```text
REMEDIATE
ESCALATE
```

### Presupuesto

> ¿Cuántos intentos quedan?

```text
Iteration 1 / 3
Attempt 2 / 2
```

### Reanudación

> ¿Podemos continuar sin reiniciar todo?

```text
YES — resume existing execution context
```

---

# 25. Principio final

La evolución del sistema no debería consistir en añadir más complejidad al orquestador.

El objetivo es conseguir un núcleo pequeño, determinista y extremadamente fiable:

```text
                 WHAT CAN HAPPEN?
                       │
                       ▼
                TASK LIFECYCLE
                       │
             ┌─────────┴─────────┐
             ▼                   ▼
          ALLOW                DENY
             │
             ▼
          AGENT WORK
             │
             ▼
       OUTCOME + EVIDENCE
             │
             ▼
       LIFECYCLE ACCEPT
             │
             ▼
          TRANSITION
```

La inteligencia debe permanecer en los especialistas.

El control, las reglas, los presupuestos, las transiciones, la auditabilidad y la seguridad deben permanecer en el lifecycle.

La frase que resume la arquitectura es:

> **Los agentes resuelven problemas; el lifecycle gobierna el proceso.**
