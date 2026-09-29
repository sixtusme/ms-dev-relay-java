# Por qué esta organización de paquetes (y en qué sentido escala más)

## El problema que había

Antes de esta reorganización, `src/main/java/.../dev/relay/` tenía **27 paquetes de primer nivel**:
`activity, api, approval, chat, coder, command, config, correction, deploy, event, exception,
filter, guardrail, insight, jira, llm, monitor, pullrequest, report, responder, scheduler,
service, session, tracker, verification, web` (más `ai`, que ya estaba bien organizado).

Esto no es "código desordenado" — cada paquete tenía sentido por sí solo y el código dentro era
correcto. El problema es de **escala de navegación**: al abrir el proyecto, la lista de carpetas no
cuenta ninguna historia. `approval`, `correction` y `command` están relacionados (los tres son "el
humano dirigiendo lo que pasa con /sixai") pero viven como si no se conocieran. `monitor`, `session`,
`report`, `chat` e `insight` son las cinco patas de lo mismo (servir al panel), separadas sin motivo.
Añadir una feature nueva significaba adivinar en cuál de 27 sitios ponerla, y entender el flujo
completo de una tarea significaba saltar entre carpetas sin relación aparente.

## La alternativa que NO elegimos: capas técnicas planas

La primera idea que surge es `/controller`, `/service`, `/repository`, `/dto` — el paquete-por-capa
clásico de tutorial. Lo descartamos a propósito: a este tamaño, esa estructura **mezcla dominios de
negocio no relacionados dentro de la misma carpeta técnica**. `/service` acabaría con
`CommandService`, `ReportService`, `VerificationService` y `DeploymentService` codo con codo, sin
que el nombre de la carpeta diga nada sobre qué hace cada uno. Es fácil de explicar en una charla,
pero no ayuda a nadie a orientarse en un proyecto real con más de una docena de servicios.

## Lo que hicimos: paquete por dominio (bounded context)

La regla: **agrupar por lo que el código HACE en el negocio, no por su tipo técnico**. Cada paquete
de primer nivel es una pregunta que alguien podría hacer sobre el sistema, y dentro puede haber
capas (entidad, repositorio, servicio) si hace falta, pero nunca al revés.

| Paquete | Pregunta que responde | Contenido |
|---|---|---|
| `intake/` | ¿Cómo entra una tarea al sistema? | detección de elegibilidad, dedupe, polling/webhook, cliente Jira en dry-run |
| `delivery/` | ¿Cómo se produce y verifica el código? | responder inicial, apertura de PRs, verificación de que compilan |
| `control/` | ¿Quién decide qué pasa a partir de aquí? | comandos `/sixai`, corrección, aprobación y promoción a producción |
| `deploy/` | ¿Cómo se despliega? | orquestación de builds/despliegues, lotes, resolución de versión de imagen |
| `panel/` | ¿Qué puede consultar una persona? | monitor de tareas en curso, sesiones, informes, chat, insights |
| `ai/` | ¿Cómo razona y actúa el agente? | agentes, tools, skills, orquestación, guardarraíles de tools |
| `guardrail/` | ¿Qué protege al sistema de sí mismo? | redacción de secretos, escudo de prompts, validación de cambios |
| `activity/` | ¿Qué ha pasado, exactamente? | la auditoría: línea de tiempo, llamadas al LLM |
| `api/` | ¿Cómo entra/sale HTTP? | webhook de Jira, API del panel |
| `llm/`, `config/`, `exception/` | infraestructura transversal | sin dueño de negocio único; se quedan como estaban |

Fíjate en el patrón: **`ai/` ya estaba organizado así desde el principio** (`agent/`, `tool/`,
`skill/`, `orchestration/`, `knowledge/` — cada subcarpeta es una pieza del razonamiento del agente,
no un tipo técnico genérico) y es precisamente el paquete que no generaba la sensación de caos. Esta
reorganización simplemente aplica esa misma idea al resto del proyecto.

## En qué sentido esto es "más escalable"

Escalar no es solo "que aguante más carga" — aquí importa sobre todo la **escala de las personas
(humanas o IA) que van a tocar el código**:

1. **Añadir una feature toca un paquete, no la raíz del proyecto.** Una nueva forma de disparar
   correcciones va a `control/correction/`; nadie tiene que decidir si crear un paquete nuevo de
   primer nivel para ello.
2. **El blast radius de un cambio es visible por la carpeta.** Si vas a tocar `delivery/`, sabes que
   afecta a cómo se produce/verifica código, no al panel ni al despliegue. Con paquetes por capa
   técnica, tocar `/service` no te dice nada sobre qué parte del negocio te la vas a jugar.
3. **Onboarding más rápido, humano o agente.** Doce carpetas con nombre de negocio se leen en un
   minuto y ya orientan; 27 carpetas técnicas sin agrupar obligan a abrir cada una para saber qué es.
   Esto importa doblemente cuando quien navega el código es un LLM con contexto limitado: menos
   ruido estructural es más presupuesto de contexto para el código real.
4. **Los límites de paquete documentan las reglas de negocio sin comentarios.** Que `verification`
   viva dentro de `delivery/` y no de `control/` ya dice que verificar si compila es parte de
   *producir* la PR, no de *decidir* qué hacer con ella. Que `coder` (los tipos `ChangeSet`/
   `FileChange`) viva dentro de `ai/agent/impl/` y no como paquete propio dice que no es un dominio
   de negocio: es el vocabulario de salida de un agente concreto.
5. **No hay una tubería única que crezca sin límite.** Si mañana aparece un dominio nuevo de verdad
   (por ejemplo, un `ReviewerAgent` con su propia lógica de revisión), tiene sitio natural como
   paquete de primer nivel propio — la estructura no obliga a forzarlo dentro de uno existente ni a
   inflar más `/service`.

## Regla para el futuro

Antes de crear un paquete de primer nivel nuevo, pregúntate: *¿esto responde a una pregunta de
negocio distinta a las de la tabla de arriba, o es una variante de una que ya existe?* Si es una
variante, va dentro del paquete existente (como subpaquete si crece lo suficiente, como en
`control/command`, `control/correction`, `control/approval`). Si de verdad es una pregunta nueva,
entonces sí, paquete nuevo — pero eso debería ser raro, no la costumbre.
