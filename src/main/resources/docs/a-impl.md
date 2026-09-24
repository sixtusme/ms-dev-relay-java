Parto de lo que he leído: la definición de MetaOrchestrator, el skill meta-sdd (máquina de fases, tracks, bucle de remediación) y los servicios principales de ms-dev-relay-java. No he revisado todo (por ejemplo, el detalle de DeploymentCoordinator o de InsightService).

Cómo funciona hoy dev-relay

Jira (webhook/polling) → filtro de elegibilidad → IssueEligibleEvent
→ IssueResponder (comenta y pone la tarea en curso)
→ RepoSelector (el LLM elige los repos) → PullRequestService (draft-PR por repo)
→ CoderService (2 pasadas LLM: qué ficheros leer y ficheros completos en JSON)
→ VerificationService (¿compila la rama?)
→ aprobación humana → merge a develop → despliegue en PRE → TEST
→ CorrectionService (PR nueva si falla o si el cliente se queja, con tope de ciclos)

La base es buena y hay cosas que ya son senior:
- Guardarraíles: ChangeSetGuard y PromptShield.
- Commits atómicos.
- Tope de correcciones con escalado a una persona.
- Línea de tiempo append-only (TaskEvent).
- Modelo por rol (LlmRoles).
- Despliegues que se retoman tras un reinicio.

Le falta lo que hace fuerte a MetaOrchestrator: separar quién decide de quién ejecuta, y tener un estado explícito con compuertas (gates).

Las diferencias de fondo

1. El flujo es implícito; en MetaOrchestrator es un estado explícito

Hoy "en qué punto está una tarea" se deduce de cuatro sitios a la vez:
- el estado de Jira,
- los nombres de rama en GitHub (ApprovalService reconstruye las PRs pendientes leyéndolos),
- los listeners de eventos,
- los barridos programados (@Scheduled).

MetaOrchestrator tiene una sola fuente de verdad (sdd-state.yml) con current_phase, phases, gates con evidencia y decisions[], y un único escritor.

Idea: convertir TaskRun en esa máquina de estados. Cada tarea tendría su fase actual, el estado de cada fase (pending | in-progress | blocked | passed | failed | skipped) y cada gate con su evidencia. Solo un componente, el orquestador, cambia de fase, y lo hace validando reglas deterministas del tipo:
- no se aprueba sin verificación en verde;
- no se avanza con una pregunta pendiente;
- una fase saltada tiene que llevar su motivo.

Es el equivalente a gate-check y a las mutaciones del CLI de estado que se validan y hacen rollback. Así desaparece la reconstrucción desde GitHub y cualquier pantalla puede responder "¿por qué está parada esta tarea?".

2. Separar el orquestador de los especialistas, con un contrato de entrega (handoff)

Hoy cada servicio decide y ejecuta, y devuelve poco: CoderService.tryImplement devuelve un boolean. "Apagado", "no supo hacerlo", "se quedó sin tokens" y "tarea ambigua" acaban todos en un false y en el commit placeholder.

En MetaOrchestrator cada especialista devuelve un handoff tipado:
- el artefacto producido,
- una recomendación para el gate (pass | fail | paused),
- la evidencia,
- los bloqueos,
- los riesgos,
- o bien un sobre de preguntas.

Idea: que responder, planner, coder, verificador y diagnóstico devuelvan todos la misma forma de resultado, y que el orquestador decida qué pasa después. Un detalle importante: el orquestador de dev-relay no debería ser un LLM.
MetaOrchestrator es "delgado" porque es un LLM y hay que ahorrarle contexto.
En Java el orquestador puede ser código determinista puro, que es más barato y fiable. La inteligencia queda solo en los especialistas.

3. Bucle de aclaraciones (el que más valor aporta)

Hoy, si la tarea de Jira es ambigua, el coder improvisa o no propone nada. MetaOrchestrator hace esto:
1. El especialista devuelve preguntas.
2. Se persisten en pending_delegation.
3. La fase pasa a blocked y el gate a paused.
4. Las preguntas llegan al humano.
5. Con la respuesta se reanuda el mismo especialista.
6. Hasta que esa pregunta se resuelve, no se avanza nada más.

En dev-relay ya tienes casi todas las piezas:
- El canal: los comentarios de Jira.
- La detección de respuestas: CommandService y ProcessedCommentsTracker.

Lo que falta:
- que el planner o el coder puedan devolver preguntas;
- que la tarea quede bloqueada esperando respuesta;
- que al llegar la respuesta se reanude esa fase, en vez de empezar de cero.

Preguntar bien es lo que más diferencia a un senior de un junior.

4. Clasificar por tracks: no todo merece el mismo recorrido

Ahora todas las tareas pasan por la misma tubería. meta-sdd clasifica primero (simple | moderate | complex | exhaustive), con heurísticas deterministas y luego el LLM, y cada track activa unas fases u otras. La filosofía es elegir el track más ligero que sea seguro y promocionarlo si aparece alcance real.

Idea: usar el rol ROUTER, o uno nuevo, para clasificar la tarea al cogerla:
- simple (texto, constante, log): coder directo.
- moderate: plan ligero, coder y verificación.
- complex (multirepo, contratos, OpenAPI, esquema de BD): criterios de aceptación explícitos, plan, preguntas y verificación contra esos criterios.

Por lo que he leído, el track debería decidir sobre todo si hay fase de plan y si se pregunta antes de codificar. Varios repos, o cambios de contrato u OpenAPI, deberían forzar complex.

5. Fases que faltan: plan, criterios de aceptación, verificación contra ellos y retro

- Plan: LlmRoles.PLANNER está declarado como "reservado" pero no se usa. Añadir un plan técnico antes del coder, con repos, ficheros, enfoque y riesgos, persistido como artefacto y visible en Jira o en el panel. RepoSelector pasaría a formar parte de él.
- Criterios de aceptación: extraerlos de la tarea de Jira al principio. Todo lo demás se mide contra ellos.
- Verificación: hoy es "¿compila?", y eso es un gate de build, no de requisitos. El equivalente a MetaVerifier mapea cada criterio a su evidencia: ¿el diff lo cubre?, ¿hay un test que lo demuestre? El resultado es un informe por criterio.
- Retro / acumular conocimiento: es lo que hace que el sistema aprenda. Cuando una tarea llega a TEST, y sobre todo cuando ha habido correcciones, se extraen lecciones ("el cliente corrigió X porque en este repo se hace Y") y se escriben en el AGENTS.md/CLAUDE.md del repo mediante una PR, o en un almacén que alimente los prompts siguientes. MetaRetro hace exactamente eso.

6. Bucle de remediación con causa raíz y antes de la aprobación

CorrectionService ya aplica el concepto de presupuesto (max_iterations y escalado). Le faltan dos cosas del remediation-loop de meta-sdd:
- Clasificar la causa raíz y mandar el arreglo a quien le toca:
    - defecto de código → coder;
    - plan equivocado → planner;
    - criterio mal definido → vuelve al humano y el contador se reinicia.
- Ejecutarlo antes de que intervenga un humano. Hoy la corrección abre PRs nuevas después del merge y del despliegue, que es caro. Si la verificación de la rama falla, el diagnóstico (DIAGNOSE) y el coder deberían reintentar sobre la misma PR, pasando solo los fallos, dentro del presupuesto, sin intervención humana. Al revisor solo le llegaría lo que ya está en verde o lo que agotó el presupuesto.

7. El contexto del coder (preflight de workspace)

MetaInit y los AGENTS.md/ARCHITECTURE.md existen porque un agente sin contexto del repo escribe código genérico. Tu coder recibe:
- el árbol del repo recortado,
- los ficheros que el LLM elige,
- cada fichero truncado.

Ideas:
- Leer siempre el CLAUDE.md/AGENTS.md del repo si existe. Las trampas que tienes documentadas en ms-pim-java/CLAUDE.md son justo lo que el coder necesita.
- Hacer un preflight por repo: si no tiene guía, la tarea queda bloqueada o se genera la guía primero.
- Cachear la detección de stack por hash de los ficheros de build, como hace .metacontext/.cache/repo-detection.

8. La palanca más grande: no reimplementar el coder

Hoy el coder es de un solo intento, sin herramientas, y reescribe ficheros enteros en JSON:
- Riesgo de truncado: ya te pasó, por eso existe LlmTruncatedException.
- Riesgo de pisar código.
- No puede compilar ni ejecutar tests antes de entregar.

MetaCoder funciona en bucle agéntico: lee, busca, edita parches, compila, corrige. Recomendación: que dev-relay sea solo el orquestador y delegue la implementación a un runtime de agente de verdad (Claude Agent SDK, o Claude Code headless en un contenedor con el repo clonado y tus agentes y skills cargados), con MetaCoder como especialista. Ganas:
- ediciones por parche,
- compilación local antes del commit,
- las convenciones de meta-code,

y te ahorras mantener un agente casero. Es lo que más calidad añadiría con menos código propio.

9. Trazabilidad y decisiones

TaskEvent es buena base. Hay que llevarla a lo que hace trace.md, que relaciona criterio → cambios → test → evidencia → veredicto, y a un decisions[]: quién decidió qué y por qué (aprobaciones, riesgos aceptados, cambios de track). ApprovalService ya guarda quién aprueba; es cuestión de generalizarlo.

Orden recomendado

1. Máquina de estados + contrato de handoff. Es la base: sin ella, lo demás son parches sobre el flujo implícito.
2. Bucle de aclaraciones por Jira. Máximo valor con lo que ya tienes.
3. Clasificación por tracks + fase de plan (activa el PLANNER reservado).
4. Remediación antes de la aprobación, con causa raíz y sobre la misma PR.
5. Coder delegado a un runtime de agente (MetaCoder).
6. Verificación contra criterios + retro que escriba en los repos.

Qué no copiaría

- Las restricciones de lectura del orquestador: existen para ahorrar contexto a un LLM, y tu orquestador es código.
- Los cinco tracks: empieza con 2 o 3.
- Artefactos en markdown: una tabla con JSON es suficiente; el valor está en el estado y los gates, no en el formato.

Si quieres, puedo convertir el punto 1 en un diseño de estados y transiciones concreto para TaskRun, sin código.