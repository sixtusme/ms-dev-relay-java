# Ciclo de vida de tareas — Paso 1: Modelo · Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Crear el modelo del ciclo de vida explícito (tablas, tipos, reglas y `TaskLifecycle` en modo registro), sin que ningún servicio lo use todavía.

**Architecture:** Paquete nuevo `control/lifecycle/` con tipos de dominio (fases, estados, handoff), dos entidades JPA sobre tablas nuevas de `V6`, un componente de reglas puro (`LifecycleRules`) y el único escritor del estado (`TaskLifecycle`), que envuelve todo en una transacción programática con captura de errores para no tumbar nunca el trabajo real. Nadie lo invoca en este paso: el flujo actual no cambia.

**Tech Stack:** Java 17+ · Spring Boot (JPA, validation) · Lombok · Flyway · MariaDB (`ddl-auto=validate`).

**Spec:** `docs/superpowers/specs/2026-09-24-task-lifecycle-design.md` (§3, §4.1, §5, §6, §8; paso 1 de §10).

## Global Constraints

- Repo `ms-dev-relay-java`, rama `feature/agents-mcp-config`. **No** tocar el clon `C:\Users\sixtusme\Dev\proyectos\sixai\`.
- **No se escribe código de testing en ningún momento** (ni clases de test, ni `spring-boot-starter-test`, ni `assert` de autocomprobación). La verificación es compilar + revisión manual.
- **Compilar solo con permiso del usuario y con el servicio parado** (lo arranca él desde el IDE). Comprobar siempre el código de salida: `mvn -q` se traga errores, así que se usa `mvn compile` sin `-q`.
- `ms-dev-relay-lib-java` **no cambia** en este paso.
- Migración: `V6__lifecycle.sql` (`V5` ya es `knowledge`). Columnas de texto en `VARCHAR`, nunca `TEXT` (por `ddl-auto=validate`).
- `maestro.lifecycle.enforce` por defecto `false` (`MAESTRO_LIFECYCLE_ENFORCE`).
- El presupuesto de correcciones es `maestro.correction.max-cycles` (existente, por defecto `3`); `maestro.verification.block-approval` (existente, por defecto `false`) decide R1.
- Estilo del repo: 2 espacios, parámetros `final`, Lombok, javadoc en español explicando el porqué.
- Sin commits salvo que el usuario lo pida.

## Decisiones de implementación (derivadas de la spec, a validar en la revisión)

1. **Sin `UNIQUE (task_run_id, phase, iteration)`** en `task_phase` (la spec lo proponía). La fase actual es **la última fila insertada** de la tarea; reutilizar una fila antigua la sacaría de ese orden. El reintento de `PROMOTION` reabre su propia fila (que es la última). Índice normal `(task_run_id, phase)`.
2. **R3 (presupuesto) es vinculante en los dos modos.** Sustituirá a `countCycles`, que hoy ya frena; si en modo registro dejara pasar, la corrección podría girar sin límite.
3. **`INTAKE` y `REPO_SELECTION` en `FAILED` terminan la tarea en `FAILED`** (spec §3.2 "error irrecuperable": ninguna de las dos admite corrección ni reintento).
4. **Sin tarea viva en `task_run` → el lifecycle no juzga** (devuelve permitido, sin aviso). Sin registro no hay con qué decidir, y no debe frenar el trabajo.
5. **Evidencia de una fase ya pasada** (resultado tardío) se guarda en la última fila de esa fase y **no mueve la tarea hacia atrás**.
6. `TaskLifecycle.snapshot(...)` (lectura para panel/`STATUS`) **no** entra aquí: llega en el paso 5, que es quien lo usa.

## Review Focus

- **BD caída o `task_phase` inexistente**: `check`/`accept`/`reroute` devuelven permitido y el flujo sigue (salvo R3, que no puede evaluarse y también devuelve permitido: misma paridad que el `countCycles` actual, que devuelve 0 ante error).
- **Tarea con varias ejecuciones (`task_run`) de la misma issue**: todo se resuelve sobre la tarea `RUNNING` más reciente (`findFirstByIssueKeyAndStatusOrderByStartedAtDesc`), igual que `TaskRecorder`.
- **Resultados por repo que llegan desordenados** (un veredicto de verificación antes de cerrar `IMPLEMENTATION`): en modo registro se aplican saltando de fase y dejan `LIFECYCLE_VIOLATION`; en estricto se rechazan. El orden correcto lo garantizan los pasos 2–3.
- **Relleno de `V6` sobre tareas sin eventos**: quedan con `current_phase = NULL` y sin fila en `task_phase` → el lifecycle solo acepta `INTAKE` para ellas.
- **Evento `LIFECYCLE_VIOLATION` como último de la línea de tiempo**: el panel no debe mostrarlo como etapa; se ignora al calcularla.

---

### Task 1: Migración `V6` y `TaskRun`

**Files:**
- Create: `src/main/resources/db/migration/V6__lifecycle.sql`
- Modify: `src/main/java/es/colorbaby/microservices/dev/relay/activity/TaskRun.java`
- Modify: `src/main/java/es/colorbaby/microservices/dev/relay/activity/TaskRecorder.java:1-5,82-89`

**Interfaces:**
- Produces: columna `task_run.remediation_iterations`; tablas `task_phase`, `task_phase_evidence`; `TaskRun.getRemediationIterations()/setRemediationIterations(int)`; `TaskRun.close(String finalStatus)`.

- [ ] **Step 1: Crear `V6__lifecycle.sql`**

```sql
-- Ciclo de vida explícito de cada tarea: en qué fase está, cómo terminó cada fase (su gate) y con
-- qué evidencia. Hasta ahora la etapa se DEDUCÍA del último task_event y de varios sitios más
-- (lotes de despliegue, ramas de GitHub, conteo de eventos); aquí pasa a ser un dato.

-- Presupuesto de correcciones consumido. Antes se contaban los CORRECTION_STARTED de la línea de
-- tiempo en cada consulta.
ALTER TABLE task_run ADD COLUMN remediation_iterations INT NOT NULL DEFAULT 0;

-- Una fila por cada vez que la tarea entra en una fase. Una corrección no sobrescribe: abre filas
-- nuevas con la iteración siguiente. La fase actual es la ÚLTIMA fila de la tarea (por id), por
-- eso no hay UNIQUE sobre (tarea, fase, iteración).
CREATE TABLE task_phase (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_run_id BIGINT       NOT NULL,
    phase       VARCHAR(20)  NOT NULL,
    iteration   INT          NOT NULL DEFAULT 0,
    status      VARCHAR(20)  NOT NULL,
    -- Quién cerró el gate: "sixai" o la persona que aprobó / pidió la promoción.
    decided_by  VARCHAR(255) NULL,
    started_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP    NULL,
    CONSTRAINT fk_task_phase_run FOREIGN KEY (task_run_id) REFERENCES task_run (id),
    INDEX idx_task_phase_run (task_run_id, phase),
    INDEX idx_task_phase_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Append-only: cada resultado que un servicio entrega sobre una fase, con su evidencia (PR, build,
-- plan, revisión, decisión, motivo). repo NULL = resultado agregado de la fase.
CREATE TABLE task_phase_evidence (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_phase_id  BIGINT        NOT NULL,
    repo           VARCHAR(150)  NULL,
    recommendation VARCHAR(20)   NOT NULL,
    kind           VARCHAR(20)   NOT NULL,
    -- VARCHAR y no TEXT: con ddl-auto=validate Hibernate compara tipos. Se trunca al guardar.
    detail         VARCHAR(2000) NULL,
    url            VARCHAR(500)  NULL,
    actor          VARCHAR(255)  NULL,
    recorded_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_task_phase_evidence_phase FOREIGN KEY (task_phase_id) REFERENCES task_phase (id),
    INDEX idx_task_phase_evidence_phase (task_phase_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Relleno de las tareas en vuelo, para que el panel no se quede en blanco al estrenar esto.
-- Es una aproximación a partir del último evento que cambia la etapa (los comandos y las
-- peticiones de corrección no la cambian). Las tareas cerradas no se tocan.

-- Mismo criterio que CorrectionService.countCycles: por issue, contando CORRECTION_STARTED.
UPDATE task_run t
SET t.remediation_iterations = (
    SELECT COUNT(*) FROM task_event e
    WHERE e.issue_key = t.issue_key AND e.type = 'CORRECTION_STARTED')
WHERE t.status = 'RUNNING';

UPDATE task_run t
SET t.current_phase = (
    SELECT CASE e.type
               WHEN 'DETECTED'           THEN 'INTAKE'
               WHEN 'IN_PROGRESS'        THEN 'REPO_SELECTION'
               WHEN 'CODE_GENERATED'     THEN 'IMPLEMENTATION'
               WHEN 'PR_OPENED'          THEN 'IMPLEMENTATION'
               WHEN 'CORRECTION_STARTED' THEN 'IMPLEMENTATION'
               WHEN 'VERIFY_STARTED'     THEN 'VERIFICATION'
               WHEN 'VERIFY_FAILED'      THEN 'VERIFICATION'
               WHEN 'VERIFY_OK'          THEN 'APPROVAL'
               WHEN 'APPROVED'           THEN 'DEPLOY_PRE'
               WHEN 'MERGED'             THEN 'DEPLOY_PRE'
               WHEN 'BUILD_STARTED'      THEN 'DEPLOY_PRE'
               WHEN 'BUILD_OK'           THEN 'DEPLOY_PRE'
               WHEN 'BUILD_FAILED'       THEN 'DEPLOY_PRE'
               WHEN 'DEPLOYED_PRE'       THEN 'DEPLOY_PRE'
               WHEN 'FAILED'             THEN 'DEPLOY_PRE'
               WHEN 'MOVED_TO_TEST'      THEN 'CLIENT_TEST'
               WHEN 'PROMOTED'           THEN 'PROMOTION'
               WHEN 'GAVE_UP'            THEN 'ESCALATED'
           END
    FROM task_event e
    WHERE e.task_run_id = t.id
      AND e.type NOT IN ('COMMAND_RECEIVED', 'CORRECTION_REQUESTED')
    ORDER BY e.id DESC
    LIMIT 1)
WHERE t.status = 'RUNNING';

INSERT INTO task_phase (task_run_id, phase, iteration, status, decided_by, started_at, finished_at)
SELECT t.id,
       t.current_phase,
       t.remediation_iterations,
       CASE
           WHEN t.current_phase = 'ESCALATED' THEN 'FAILED'
           WHEN (SELECT e.type FROM task_event e
                 WHERE e.task_run_id = t.id
                   AND e.type NOT IN ('COMMAND_RECEIVED', 'CORRECTION_REQUESTED')
                 ORDER BY e.id DESC LIMIT 1) IN ('VERIFY_FAILED', 'BUILD_FAILED', 'FAILED')
               THEN 'FAILED'
           ELSE 'IN_PROGRESS'
       END,
       CASE WHEN t.current_phase = 'ESCALATED' THEN 'sixai' END,
       CURRENT_TIMESTAMP,
       CASE WHEN t.current_phase = 'ESCALATED' THEN CURRENT_TIMESTAMP END
FROM task_run t
WHERE t.status = 'RUNNING'
  AND t.current_phase IS NOT NULL;
```

- [ ] **Step 2: Añadir a `TaskRun` el presupuesto y `close`**

En `TaskRun.java`, añadir el import `import java.time.Duration;` junto a `import java.time.Instant;`, y justo después del campo `private Long durationMs;` añadir:

```java
  /**
   * Correcciones ya consumidas (vueltas a implementar). Es el presupuesto que limita
   * {@code maestro.correction.max-cycles}; antes se contaba recorriendo la línea de tiempo.
   */
  @Column(name = "remediation_iterations", nullable = false)
  private int remediationIterations;
```

y antes de la llave final de la clase:

```java
  /** Cierra la tarea midiendo cuánto tardó desde que se cogió. */
  public void close(final String finalStatus) {
    final Instant now = Instant.now();
    this.status = finalStatus;
    this.finishedAt = now;
    this.durationMs = Duration.between(startedAt, now).toMillis();
  }
```

- [ ] **Step 3: Que `TaskRecorder.finish` use `TaskRun.close`**

En `TaskRecorder.java`, sustituir el cuerpo de `finish`:

```java
  public void finish(final String issueKey, final String status) {
    update(issueKey, task -> task.close(status));
  }
```

y borrar los imports `import java.time.Duration;` e `import java.time.Instant;` (ya no se usan en ese fichero).

- [ ] **Step 4: Compilar (con permiso del usuario y el servicio parado)**

Run (desde `repos/ms-dev-relay-java`): `mvn compile; echo "exit=$?"`
Expected: `BUILD SUCCESS` y `exit=0`. La migración no se ejecuta al compilar: se aplica al arrancar el servicio.

---

### Task 2: Tipos del dominio y persistencia del lifecycle

**Files (todos en `src/main/java/es/colorbaby/microservices/dev/relay/control/lifecycle/`):**
- Create: `TaskPhase.java`, `PhaseStatus.java`, `Recommendation.java`, `EvidenceKind.java`
- Create: `Evidence.java`, `PhaseOutcome.java`, `Verdict.java`
- Create: `TaskPhaseRun.java`, `PhaseEvidence.java`, `TaskPhaseRunRepository.java`, `PhaseEvidenceRepository.java`, `PhaseSnapshot.java`

**Interfaces:**
- Consumes: tablas de Task 1.
- Produces (usado por Task 4, Task 5 y pasos 2–5):
  - `enum TaskPhase { INTAKE, REPO_SELECTION, IMPLEMENTATION, VERIFICATION, APPROVAL, DEPLOY_PRE, CLIENT_TEST, PROMOTION, DONE, ESCALATED, FAILED, CANCELLED }` con `boolean isTerminal()`, `TaskPhase next()`, `boolean failureIsTerminal()`, `boolean isBefore(TaskPhase)`.
  - `enum PhaseStatus { PENDING, IN_PROGRESS, BLOCKED, PASSED, FAILED, SKIPPED }` con `boolean isClosed()`, `boolean letsAdvance()`.
  - `enum Recommendation { STARTED, PASS, FAIL, SKIPPED, BLOCKED }` con `PhaseStatus toStatus()`, `boolean isVerdict()`.
  - `enum EvidenceKind { PR, COMMENT, BUILD, REPORT, PLAN, REVIEW, DECISION, REASON }`.
  - `record Evidence(EvidenceKind kind, String detail, String url)` + `static Evidence of(EvidenceKind, String)`.
  - `record PhaseOutcome(TaskPhase phase, Recommendation recommendation, String repo, List<Evidence> evidence, String actor)` + `static PhaseOutcome of(TaskPhase, Recommendation, Evidence...)`, `static PhaseOutcome forRepo(TaskPhase, Recommendation, String, Evidence...)`, `PhaseOutcome by(String actor)`, `boolean isAggregate()`, constante `SIXAI = "sixai"`.
  - `record Verdict(boolean allowed, String rule, String reason)` + `static Verdict allow()`, `static Verdict deny(String rule, String reason)`, `boolean denied()`.
  - Entidad `TaskPhaseRun` (tabla `task_phase`) con `close(PhaseStatus, String actor)` y `reopen(PhaseStatus)`.
  - Entidad `PhaseEvidence` (tabla `task_phase_evidence`).
  - `TaskPhaseRunRepository.findByTaskRunIdOrderByIdAsc(Long)`; `PhaseEvidenceRepository.findByTaskPhaseIdOrderByIdAsc(Long)`.
  - `record PhaseSnapshot(TaskPhaseRun current, Map<TaskPhase, TaskPhaseRun> latest)` + `static PhaseSnapshot of(List<TaskPhaseRun>)`, `TaskPhaseRun latest(TaskPhase)`.

- [ ] **Step 1: `TaskPhase.java`**

```java
package es.colorbaby.microservices.dev.relay.control.lifecycle;

/**
 * Fases de la vida de una tarea en sixai, en el orden en que se recorren. Las cuatro últimas son
 * terminales: una tarea que llega a ellas ya no transiciona.
 *
 * <p>El orden importa: {@link #next()} y {@link #isBefore(TaskPhase)} se apoyan en él.
 */
public enum TaskPhase {
  INTAKE,
  REPO_SELECTION,
  IMPLEMENTATION,
  VERIFICATION,
  APPROVAL,
  DEPLOY_PRE,
  CLIENT_TEST,
  PROMOTION,
  DONE,
  ESCALATED,
  FAILED,
  CANCELLED;

  public boolean isTerminal() {
    return ordinal() >= DONE.ordinal();
  }

  /** Siguiente fase del recorrido normal ({@code PROMOTION} → {@code DONE}). */
  public TaskPhase next() {
    if (isTerminal()) {
      throw new IllegalStateException("Una fase terminal no tiene siguiente: " + this);
    }
    return values()[ordinal() + 1];
  }

  /**
   * Fases cuyo fallo no tiene salida: no admiten corrección ni reintento, así que la tarea termina
   * en {@link #FAILED}. Sin esto se quedarían vivas para siempre, como pasaba con un fallo del
   * responder.
   */
  public boolean failureIsTerminal() {
    return this == INTAKE || this == REPO_SELECTION;
  }

  public boolean isBefore(final TaskPhase other) {
    return ordinal() < other.ordinal();
  }
}
```

- [ ] **Step 2: `PhaseStatus.java`**

```java
package es.colorbaby.microservices.dev.relay.control.lifecycle;

/**
 * Estado de una fase. El estado final ({@code PASSED}, {@code FAILED}, {@code SKIPPED}) es el gate:
 * junto con quién lo decidió y la evidencia, dice por qué la tarea pudo (o no) seguir.
 */
public enum PhaseStatus {
  PENDING,
  IN_PROGRESS,
  /** Reservado para las preguntas pendientes (fase 2): en este paso nadie lo produce. */
  BLOCKED,
  PASSED,
  FAILED,
  SKIPPED;

  public boolean isClosed() {
    return this == PASSED || this == FAILED || this == SKIPPED;
  }

  /** Si la tarea puede pasar a la fase siguiente. */
  public boolean letsAdvance() {
    return this == PASSED || this == SKIPPED;
  }
}
```

- [ ] **Step 3: `Recommendation.java`**

```java
package es.colorbaby.microservices.dev.relay.control.lifecycle;

/** Lo que un servicio recomienda para su fase al terminar su parte. Decide el lifecycle. */
public enum Recommendation {
  /** Ha arrancado algo que terminará más tarde (un build encolado, un despliegue). */
  STARTED,
  PASS,
  FAIL,
  SKIPPED,
  /** Reservado para las preguntas pendientes (fase 2). */
  BLOCKED;

  public PhaseStatus toStatus() {
    return switch (this) {
      case STARTED -> PhaseStatus.IN_PROGRESS;
      case PASS -> PhaseStatus.PASSED;
      case FAIL -> PhaseStatus.FAILED;
      case SKIPPED -> PhaseStatus.SKIPPED;
      case BLOCKED -> PhaseStatus.BLOCKED;
    };
  }

  /** Si es un veredicto final (sirve para cerrar una fase que se agrega por repos). */
  public boolean isVerdict() {
    return this == PASS || this == FAIL || this == SKIPPED;
  }
}
```

- [ ] **Step 4: `EvidenceKind.java`**

```java
package es.colorbaby.microservices.dev.relay.control.lifecycle;

/** Qué tipo de prueba respalda un resultado. Los artefactos (PR, informe, build) llevan URL. */
public enum EvidenceKind {
  PR,
  COMMENT,
  BUILD,
  REPORT,
  PLAN,
  REVIEW,
  DECISION,
  REASON
}
```

- [ ] **Step 5: `Evidence.java`**

```java
package es.colorbaby.microservices.dev.relay.control.lifecycle;

/**
 * Una prueba de lo que ha pasado en una fase: qué es, el detalle legible y, si es un artefacto,
 * dónde verlo.
 *
 * @param url enlace al artefacto (PR, build, informe), o null
 */
public record Evidence(EvidenceKind kind, String detail, String url) {

  public static Evidence of(final EvidenceKind kind, final String detail) {
    return new Evidence(kind, detail, null);
  }
}
```

- [ ] **Step 6: `PhaseOutcome.java`**

```java
package es.colorbaby.microservices.dev.relay.control.lifecycle;

import java.util.List;
import java.util.Objects;

/**
 * El handoff: lo que un servicio devuelve al terminar su parte de una fase. No toca el estado; se
 * lo entrega a {@link TaskLifecycle#accept}, que es quien decide.
 *
 * @param repo  repo al que se refiere, o null si es el resultado agregado de la fase (el que la
 *              cierra, salvo en VERIFICATION, que se cierra al tener veredicto de todos los repos)
 * @param actor quién lo decide: {@value #SIXAI} o la persona (el aprobador, el autor del comando)
 */
public record PhaseOutcome(TaskPhase phase, Recommendation recommendation, String repo,
    List<Evidence> evidence, String actor) {

  public static final String SIXAI = "sixai";

  public PhaseOutcome {
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(recommendation, "recommendation");
    evidence = evidence == null ? List.of() : List.copyOf(evidence);
    actor = actor == null || actor.isBlank() ? SIXAI : actor;
  }

  /** Resultado agregado de la fase. */
  public static PhaseOutcome of(final TaskPhase phase, final Recommendation recommendation,
      final Evidence... evidence) {
    return new PhaseOutcome(phase, recommendation, null, List.of(evidence), SIXAI);
  }

  /** Resultado de un solo repo dentro de la fase. */
  public static PhaseOutcome forRepo(final TaskPhase phase, final Recommendation recommendation,
      final String repo, final Evidence... evidence) {
    return new PhaseOutcome(phase, recommendation, repo, List.of(evidence), SIXAI);
  }

  /** El mismo resultado, decidido por otra persona. */
  public PhaseOutcome by(final String who) {
    return new PhaseOutcome(phase, recommendation, repo, evidence, who);
  }

  public boolean isAggregate() {
    return repo == null;
  }
}
```

- [ ] **Step 7: `Verdict.java`**

```java
package es.colorbaby.microservices.dev.relay.control.lifecycle;

/**
 * Respuesta del lifecycle a una consulta o a un resultado: si se permite y, si no, qué regla lo
 * impide y por qué, para poder contárselo a una persona en la propia tarea.
 *
 * @param rule regla incumplida (R1…R5, ORDER), o null si se permite
 */
public record Verdict(boolean allowed, String rule, String reason) {

  private static final Verdict ALLOWED = new Verdict(true, null, null);

  public static Verdict allow() {
    return ALLOWED;
  }

  public static Verdict deny(final String rule, final String reason) {
    return new Verdict(false, rule, reason);
  }

  public boolean denied() {
    return !allowed;
  }
}
```

- [ ] **Step 8: `TaskPhaseRun.java`**

```java
package es.colorbaby.microservices.dev.relay.control.lifecycle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Una pasada de una tarea por una fase. Una corrección no la sobrescribe: abre otra con la
 * iteración siguiente, así queda el historial de cada intento. La fase actual de la tarea es su
 * última fila.
 */
@Entity
@Table(name = "task_phase")
@Getter
@Setter
@NoArgsConstructor
public class TaskPhaseRun {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "task_run_id", nullable = false)
  private Long taskRunId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TaskPhase phase;

  @Column(nullable = false)
  private int iteration;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PhaseStatus status;

  /** Quién cerró el gate: "sixai" o una persona. */
  @Column(name = "decided_by")
  private String decidedBy;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt = Instant.now();

  @Column(name = "finished_at")
  private Instant finishedAt;

  public TaskPhaseRun(final Long taskRunId, final TaskPhase phase, final int iteration,
      final PhaseStatus status) {
    this.taskRunId = taskRunId;
    this.phase = phase;
    this.iteration = iteration;
    this.status = status;
  }

  /** Cierra el gate con su estado final y quién lo decidió. */
  public void close(final PhaseStatus finalStatus, final String actor) {
    this.status = finalStatus;
    this.decidedBy = actor;
    this.finishedAt = Instant.now();
  }

  /** Vuelve a dejar la fase abierta (reintento de la promoción, o un estado no final). */
  public void reopen(final PhaseStatus openStatus) {
    this.status = openStatus;
    this.decidedBy = null;
    this.finishedAt = null;
  }
}
```

- [ ] **Step 9: `PhaseEvidence.java`**

```java
package es.colorbaby.microservices.dev.relay.control.lifecycle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Una evidencia de un resultado entregado sobre una fase. Append-only: nunca se modifica. */
@Entity
@Table(name = "task_phase_evidence")
@Getter
@NoArgsConstructor
public class PhaseEvidence {

  /** Topes; deben coincidir con las columnas. */
  public static final int DETAIL_MAX = 2000;
  public static final int URL_MAX = 500;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "task_phase_id", nullable = false)
  private Long taskPhaseId;

  /** Repo al que se refiere; null si es el resultado agregado de la fase. */
  private String repo;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Recommendation recommendation;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private EvidenceKind kind;

  @Column(length = DETAIL_MAX)
  private String detail;

  @Column(length = URL_MAX)
  private String url;

  private String actor;

  @Column(name = "recorded_at", nullable = false)
  private Instant recordedAt = Instant.now();

  public PhaseEvidence(final Long taskPhaseId, final String repo,
      final Recommendation recommendation, final EvidenceKind kind, final String detail,
      final String url, final String actor) {
    this.taskPhaseId = taskPhaseId;
    this.repo = repo;
    this.recommendation = recommendation;
    this.kind = kind;
    this.detail = truncate(detail, DETAIL_MAX);
    this.url = truncate(url, URL_MAX);
    this.actor = actor;
  }

  private static String truncate(final String value, final int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
```

- [ ] **Step 10: `TaskPhaseRunRepository.java`**

```java
package es.colorbaby.microservices.dev.relay.control.lifecycle;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Pasadas de las tareas por sus fases. */
public interface TaskPhaseRunRepository extends JpaRepository<TaskPhaseRun, Long> {

  /** Todas las pasadas de una tarea, en el orden en que se abrieron: la última es la actual. */
  List<TaskPhaseRun> findByTaskRunIdOrderByIdAsc(Long taskRunId);
}
```

- [ ] **Step 11: `PhaseEvidenceRepository.java`**

```java
package es.colorbaby.microservices.dev.relay.control.lifecycle;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Evidencias de las fases. */
public interface PhaseEvidenceRepository extends JpaRepository<PhaseEvidence, Long> {

  /** Evidencias de una pasada por una fase, en el orden en que llegaron. */
  List<PhaseEvidence> findByTaskPhaseIdOrderByIdAsc(Long taskPhaseId);
}
```

- [ ] **Step 12: `PhaseSnapshot.java`**

```java
package es.colorbaby.microservices.dev.relay.control.lifecycle;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Foto del estado de una tarea para evaluar las reglas: la fase actual y la última pasada por
 * cada fase.
 *
 * @param current fase actual (la última abierta), o null si la tarea no tiene ninguna registrada
 */
public record PhaseSnapshot(TaskPhaseRun current, Map<TaskPhase, TaskPhaseRun> latest) {

  /** @param rows pasadas de la tarea en orden de apertura */
  public static PhaseSnapshot of(final List<TaskPhaseRun> rows) {
    final Map<TaskPhase, TaskPhaseRun> latest = new EnumMap<>(TaskPhase.class);
    rows.forEach(row -> latest.put(row.getPhase(), row));
    final TaskPhaseRun current = rows.isEmpty() ? null : rows.get(rows.size() - 1);
    return new PhaseSnapshot(current, Collections.unmodifiableMap(latest));
  }

  /** Última pasada por una fase, o null si nunca se entró en ella. */
  public TaskPhaseRun latest(final TaskPhase phase) {
    return latest.get(phase);
  }
}
```

- [ ] **Step 13: Compilar (con permiso y servicio parado)**

Run: `mvn compile; echo "exit=$?"`
Expected: `BUILD SUCCESS`, `exit=0`.

---

### Task 3: Propiedad `enforce` y evento `LIFECYCLE_VIOLATION`

**Files:**
- Create: `src/main/java/es/colorbaby/microservices/dev/relay/config/LifecycleProperties.java`
- Modify: `src/main/resources/application.yml` (antes del bloque `correction:`, ~línea 304)
- Modify: `src/main/java/es/colorbaby/microservices/dev/relay/activity/TaskEventType.java` (antes de `FAILED`)
- Modify: `src/main/java/es/colorbaby/microservices/dev/relay/panel/monitor/TaskMonitorService.java:99-102` y el `switch` de `fromEvent` (~línea 138)

**Interfaces:**
- Produces: `LifecycleProperties.isEnforce()`; `TaskEventType.LIFECYCLE_VIOLATION`.

- [ ] **Step 1: `LifecycleProperties.java`**

```java
package es.colorbaby.microservices.dev.relay.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ciclo de vida explícito de las tareas ({@code maestro.lifecycle}).
 *
 * <p>Arranca en modo registro a propósito: las reglas se estrenan observando el flujo real, y cada
 * cosa que el modo estricto habría frenado queda como {@code LIFECYCLE_VIOLATION} sin cambiar el
 * comportamiento. Se pasa a {@code true} cuando esos avisos lleven tiempo sin aparecer.
 */
@Data
@ConfigurationProperties(prefix = "maestro.lifecycle")
public class LifecycleProperties {

  /** Con {@code true}, una regla incumplida bloquea; con {@code false}, solo avisa. */
  private boolean enforce = false;
}
```

(Se registra solo: `DevRelayApplication` ya tiene `@ConfigurationPropertiesScan`.)

- [ ] **Step 2: `application.yml`**

Justo antes del comentario que precede a `  correction:` (el que empieza por `# Cierra el bucle:`), insertar:

```yaml
  # Ciclo de vida explícito de cada tarea: fase → gate → evidencia. Con enforce=false solo
  # registra y avisa (LIFECYCLE_VIOLATION) de lo que el modo estricto habría bloqueado; con true,
  # bloquea. Se pasa a true cuando los avisos lleven tiempo sin aparecer.
  lifecycle:
    enforce: ${MAESTRO_LIFECYCLE_ENFORCE:false}

```

- [ ] **Step 3: `TaskEventType.LIFECYCLE_VIOLATION`**

En `TaskEventType.java`, sustituir:

```java
  /** Algo falló y se abortó. */
  FAILED
}
```

por:

```java
  /**
   * El ciclo de vida detectó algo que el modo estricto habría frenado (o frenó). No cambia la
   * etapa de la tarea: es un aviso.
   */
  LIFECYCLE_VIOLATION,
  /** Algo falló y se abortó. */
  FAILED
}
```

- [ ] **Step 4: Que el panel ignore los avisos al calcular la etapa**

En `TaskMonitorService.stageOf`, sustituir:

```java
    if (timeline.isEmpty()) {
      return new Stage("Arrancando", "STARTING", null);
    }
    return fromEvent(timeline.get(timeline.size() - 1));
```

por:

```java
    // Un aviso del ciclo de vida no es una etapa: la etapa es el último hito real.
    final Optional<TaskEvent> last = timeline.stream()
        .filter(event -> event.getType() != TaskEventType.LIFECYCLE_VIOLATION)
        .reduce((previous, next) -> next);
    if (last.isEmpty()) {
      return new Stage("Arrancando", "STARTING", null);
    }
    return fromEvent(last.get());
```

y en el `switch` de `fromEvent`, añadir antes de `case FAILED -> …`:

```java
      // No se llega aquí (stageOf lo filtra); el switch es exhaustivo y lo exige.
      case LIFECYCLE_VIOLATION -> new Stage("En curso", "IN_PROGRESS", detail);
```

(`Optional`, `TaskEvent` y `TaskEventType` ya están importados en ese fichero.)

- [ ] **Step 5: Compilar (con permiso y servicio parado)**

Run: `mvn compile; echo "exit=$?"`
Expected: `BUILD SUCCESS`, `exit=0`. Si falla por otro `switch` exhaustivo sobre `TaskEventType`, añadir el mismo `case` allí (hoy solo existe el de `TaskMonitorService`).

---

### Task 4: `LifecycleRules`

**Files:**
- Create: `src/main/java/es/colorbaby/microservices/dev/relay/control/lifecycle/LifecycleRules.java`

**Interfaces:**
- Consumes: todo lo de Task 2; `VerificationProperties.isBlockApproval()`; `CorrectionProperties.getMaxCycles()`; `TaskRun.getRemediationIterations()`.
- Produces (usado por Task 5):
  - `Verdict canEnter(PhaseSnapshot snapshot, TaskPhase target)`
  - `Verdict canAccept(PhaseSnapshot snapshot, PhaseOutcome outcome)`
  - `Verdict canReroute(PhaseSnapshot snapshot)`
  - `Verdict budget(TaskRun task)`
  - `PhaseStatus resolve(TaskPhaseRun row, PhaseOutcome outcome, List<PhaseEvidence> evidence, Set<String> expectedRepos)`
  - `static boolean isPromotionRetry(TaskPhaseRun row, PhaseOutcome outcome)`

- [ ] **Step 1: Escribir `LifecycleRules.java`**

```java
package es.colorbaby.microservices.dev.relay.control.lifecycle;

import es.colorbaby.microservices.dev.relay.activity.TaskRun;
import es.colorbaby.microservices.dev.relay.config.CorrectionProperties;
import es.colorbaby.microservices.dev.relay.config.VerificationProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Las reglas del ciclo de vida, sin persistencia ni efectos: dada la foto de una tarea, dicen si
 * algo se permite y en qué estado queda una fase. Decidir aquí y escribir en
 * {@link TaskLifecycle} permite leer las reglas de un vistazo.
 *
 * <p>Reglas (spec §3.3): R1 aprobación, R2 despliegues, R3 presupuesto de correcciones, R4 desde
 * dónde se corrige, R5 terminales. {@code ORDER} es un resultado fuera de secuencia.
 */
@Component
@RequiredArgsConstructor
public class LifecycleRules {

  static final String R1 = "R1";
  static final String R2 = "R2";
  static final String R3 = "R3";
  static final String R4 = "R4";
  static final String R5 = "R5";
  static final String ORDER = "ORDER";

  private final VerificationProperties verificationProperties;
  private final CorrectionProperties correctionProperties;

  /**
   * Si la tarea puede entrar (o seguir) en {@code target}. Se consulta antes de una acción con
   * efectos: aprobar, desplegar, promocionar.
   */
  public Verdict canEnter(final PhaseSnapshot snapshot, final TaskPhase target) {
    final TaskPhaseRun current = snapshot.current();
    if (current == null) {
      // Sin fase registrada no hay con qué juzgar, y el registro nunca frena el trabajo.
      return Verdict.allow();
    }
    if (current.getPhase().isTerminal()) {
      return Verdict.deny(R5, "la tarea ya terminó en " + current.getPhase());
    }
    return switch (target) {
      case APPROVAL -> canApprove(snapshot);
      case DEPLOY_PRE -> current.getPhase() == TaskPhase.DEPLOY_PRE
          && current.getStatus() == PhaseStatus.IN_PROGRESS
          ? Verdict.allow()
          : Verdict.deny(R2, "solo se despliega en PRE tras aprobar; la tarea está en "
              + describe(current));
      case PROMOTION -> current.getPhase() == TaskPhase.PROMOTION
          && (current.getStatus() == PhaseStatus.IN_PROGRESS
              || current.getStatus() == PhaseStatus.FAILED)
          ? Verdict.allow()
          : Verdict.deny(R2, "solo se promociona tras las pruebas del cliente; la tarea está en "
              + describe(current));
      default -> current.getPhase() == target
          ? Verdict.allow()
          : Verdict.deny(ORDER, "la tarea está en " + describe(current) + ", no en " + target);
    };
  }

  /** Si se puede aceptar este resultado con la tarea donde está. */
  public Verdict canAccept(final PhaseSnapshot snapshot, final PhaseOutcome outcome) {
    final TaskPhaseRun current = snapshot.current();
    if (current == null) {
      return outcome.phase() == TaskPhase.INTAKE
          ? Verdict.allow()
          : Verdict.deny(ORDER, "la tarea no tiene fase registrada y llega un resultado de "
              + outcome.phase());
    }
    if (current.getPhase().isTerminal()) {
      return Verdict.deny(R5, "la tarea ya terminó en " + current.getPhase());
    }
    if (outcome.phase() == current.getPhase()) {
      if (!current.getStatus().isClosed() || !outcome.isAggregate()
          || isPromotionRetry(current, outcome)) {
        return Verdict.allow();
      }
      return Verdict.deny(ORDER, outcome.phase() + " ya estaba cerrada en " + current.getStatus());
    }
    if (outcome.phase().isBefore(current.getPhase())) {
      // Evidencia tardía de una fase ya pasada: se guarda y no mueve la tarea.
      return Verdict.allow();
    }
    // Salto adelante: solo si la regla de esa fase lo permite (p. ej. aprobar con la verificación
    // fallida cuando block-approval está apagado).
    return canEnter(snapshot, outcome.phase());
  }

  /** R4/R5: si desde donde está la tarea se puede volver a implementar. */
  public Verdict canReroute(final PhaseSnapshot snapshot) {
    final TaskPhaseRun current = snapshot.current();
    if (current == null) {
      return Verdict.allow();
    }
    if (current.getPhase().isTerminal()) {
      return Verdict.deny(R5, "la tarea ya terminó en " + current.getPhase());
    }
    final boolean failed = current.getStatus() == PhaseStatus.FAILED;
    final boolean allowed = switch (current.getPhase()) {
      case IMPLEMENTATION, VERIFICATION, DEPLOY_PRE -> failed;
      case CLIENT_TEST -> true;
      default -> false;
    };
    return allowed
        ? Verdict.allow()
        : Verdict.deny(R4, "no se corrige desde " + describe(current)
            + "; solo desde una implementación, verificación o despliegue a PRE fallidos, o "
            + "desde las pruebas del cliente");
  }

  /**
   * R3: presupuesto de correcciones. Es vinculante también en modo registro: sustituye a un tope
   * que ya frenaba antes, y sin él la corrección podría girar sin fin.
   */
  public Verdict budget(final TaskRun task) {
    final int max = correctionProperties.getMaxCycles();
    return task.getRemediationIterations() >= max
        ? Verdict.deny(R3, "agotados los " + max + " ciclos de corrección")
        : Verdict.allow();
  }

  /**
   * Estado de la fase tras recibir un resultado. Uno agregado lo decide por sí solo; uno por repo
   * solo cierra VERIFICATION, cuando todos los repos esperados tienen veredicto.
   *
   * @param evidence      evidencias de la fila, ya incluida la del resultado recibido
   * @param expectedRepos repos que deben dar veredicto (solo se usa en VERIFICATION)
   */
  public PhaseStatus resolve(final TaskPhaseRun row, final PhaseOutcome outcome,
      final List<PhaseEvidence> evidence, final Set<String> expectedRepos) {
    if (isPromotionRetry(row, outcome)) {
      return PhaseStatus.IN_PROGRESS;
    }
    if (row.getStatus().isClosed()) {
      return row.getStatus();
    }
    if (outcome.isAggregate()) {
      return outcome.recommendation().toStatus();
    }
    if (row.getPhase() != TaskPhase.VERIFICATION) {
      return row.getStatus();
    }
    return aggregateByRepo(evidence, expectedRepos, row.getStatus());
  }

  /** Un {@code PROMOTE} nuevo tras una promoción fallida: la fase se reabre. */
  static boolean isPromotionRetry(final TaskPhaseRun row, final PhaseOutcome outcome) {
    return row.getPhase() == TaskPhase.PROMOTION
        && row.getStatus() == PhaseStatus.FAILED
        && outcome.phase() == TaskPhase.PROMOTION
        && outcome.isAggregate()
        && outcome.recommendation() == Recommendation.STARTED;
  }

  private Verdict canApprove(final PhaseSnapshot snapshot) {
    final TaskPhaseRun implementation = snapshot.latest(TaskPhase.IMPLEMENTATION);
    if (implementation == null || implementation.getStatus() != PhaseStatus.PASSED) {
      return Verdict.deny(R1, "no hay código que aprobar: la implementación está en "
          + describe(implementation));
    }
    final TaskPhaseRun current = snapshot.current();
    if (current.getPhase() == TaskPhase.APPROVAL) {
      return Verdict.allow();
    }
    if (current.getPhase() == TaskPhase.VERIFICATION
        && current.getStatus() == PhaseStatus.FAILED) {
      return verificationProperties.isBlockApproval()
          ? Verdict.deny(R1, "alguna PR no compila y block-approval está activo")
          : Verdict.allow();
    }
    return Verdict.deny(R1, "la tarea está en " + describe(current)
        + "; solo se aprueba con la verificación cerrada");
  }

  /** Último veredicto de cada repo; la fase se cierra cuando están todos los esperados. */
  private static PhaseStatus aggregateByRepo(final List<PhaseEvidence> evidence,
      final Set<String> expectedRepos, final PhaseStatus unchanged) {
    if (expectedRepos.isEmpty()) {
      return unchanged;
    }
    final Map<String, Recommendation> verdicts = new HashMap<>();
    for (final PhaseEvidence item : evidence) {
      if (item.getRepo() != null && item.getRecommendation().isVerdict()) {
        verdicts.put(item.getRepo(), item.getRecommendation());
      }
    }
    if (!verdicts.keySet().containsAll(expectedRepos)) {
      return unchanged;
    }
    if (expectedRepos.stream().anyMatch(repo -> verdicts.get(repo) == Recommendation.FAIL)) {
      return PhaseStatus.FAILED;
    }
    return expectedRepos.stream().allMatch(repo -> verdicts.get(repo) == Recommendation.SKIPPED)
        ? PhaseStatus.SKIPPED : PhaseStatus.PASSED;
  }

  private static String describe(final TaskPhaseRun row) {
    return row == null ? "(sin registrar)" : row.getPhase() + " (" + row.getStatus() + ")";
  }
}
```

- [ ] **Step 2: Compilar (con permiso y servicio parado)**

Run: `mvn compile; echo "exit=$?"`
Expected: `BUILD SUCCESS`, `exit=0`.

---

### Task 5: `TaskLifecycle`

**Files:**
- Create: `src/main/java/es/colorbaby/microservices/dev/relay/control/lifecycle/TaskLifecycle.java`

**Interfaces:**
- Consumes: Task 1–4; `TaskRunRepository.findFirstByIssueKeyAndStatusOrderByStartedAtDesc(String, String)`; `TaskRecorder.record(String, TaskEventType, String, String)`; `TransactionTemplate` (lo registra Spring Boot con JPA).
- Produces (usado por los pasos 2–4):
  - `Verdict check(String issueKey, TaskPhase target)`
  - `Verdict accept(String issueKey, PhaseOutcome outcome)`
  - `Verdict reroute(String issueKey, String reason, String actor)` — si devuelve denegado con `rule() == "R3"`, la tarea ya quedó en `ESCALATED`.

- [ ] **Step 1: Escribir `TaskLifecycle.java`**

```java
package es.colorbaby.microservices.dev.relay.control.lifecycle;

import es.colorbaby.microservices.dev.relay.activity.TaskEventType;
import es.colorbaby.microservices.dev.relay.activity.TaskRecorder;
import es.colorbaby.microservices.dev.relay.activity.TaskRun;
import es.colorbaby.microservices.dev.relay.activity.TaskRunRepository;
import es.colorbaby.microservices.dev.relay.config.LifecycleProperties;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El único que escribe el estado de una tarea: en qué fase está, cómo terminó cada fase y con qué
 * evidencia. Los servicios le entregan su resultado ({@link PhaseOutcome}) o le preguntan antes de
 * actuar ({@link #check}); las reglas viven en {@link LifecycleRules}.
 *
 * <p>Con {@code maestro.lifecycle.enforce=false} (modo registro) una regla incumplida se anota como
 * {@code LIFECYCLE_VIOLATION} y se deja pasar, aplicando igualmente el resultado: el estado sigue a
 * la realidad. Con {@code true}, se rechaza. La excepción es R3 (presupuesto de correcciones), que
 * frena siempre.
 *
 * <p><b>Nunca tumba el trabajo real:</b> todo va en una transacción propia y cualquier fallo se
 * loguea y se responde "permitido", igual que {@code TaskRecorder}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskLifecycle {

  private final TaskRunRepository tasks;
  private final TaskPhaseRunRepository phases;
  private final PhaseEvidenceRepository evidences;
  private final TaskRecorder taskRecorder;
  private final LifecycleRules rules;
  private final LifecycleProperties properties;
  private final TransactionTemplate transactions;

  /** Si se puede hacer algo que lleva a la tarea a {@code target}. Se llama ANTES de actuar. */
  public Verdict check(final String issueKey, final TaskPhase target) {
    return safely(issueKey, "check " + target, () -> {
      final Optional<TaskRun> task = liveTask(issueKey);
      if (task.isEmpty()) {
        return Verdict.allow();
      }
      return decide(issueKey, rules.canEnter(snapshot(task.get()), target));
    });
  }

  /** Recibe el resultado de un servicio: lo valida, guarda la evidencia y avanza si procede. */
  public Verdict accept(final String issueKey, final PhaseOutcome outcome) {
    return safely(issueKey, "accept " + outcome.phase(), () -> {
      final Optional<TaskRun> task = liveTask(issueKey);
      if (task.isEmpty()) {
        return Verdict.allow();
      }
      final PhaseSnapshot snapshot = snapshot(task.get());
      final Verdict verdict = decide(issueKey, rules.canAccept(snapshot, outcome));
      if (verdict.denied()) {
        return verdict;
      }
      apply(task.get(), snapshot, outcome);
      return Verdict.allow();
    });
  }

  /**
   * Vuelve a implementar (ciclo de corrección): consume una iteración del presupuesto y abre
   * IMPLEMENTATION de nuevo. Con el presupuesto agotado, la tarea pasa a ESCALATED y se devuelve
   * denegado con la regla R3, en los dos modos.
   */
  public Verdict reroute(final String issueKey, final String reason, final String actor) {
    return safely(issueKey, "reroute", () -> {
      final Optional<TaskRun> live = liveTask(issueKey);
      if (live.isEmpty()) {
        return Verdict.allow();
      }
      final TaskRun task = live.get();
      final PhaseSnapshot snapshot = snapshot(task);
      final Verdict budget = rules.budget(task);
      if (budget.denied()) {
        escalate(task, snapshot);
        return budget;
      }
      final Verdict verdict = decide(issueKey, rules.canReroute(snapshot));
      if (verdict.denied()) {
        return verdict;
      }
      task.setRemediationIterations(task.getRemediationIterations() + 1);
      final TaskPhaseRun row = open(task, TaskPhase.IMPLEMENTATION);
      saveEvidence(row, PhaseOutcome.of(TaskPhase.IMPLEMENTATION, Recommendation.STARTED,
          Evidence.of(EvidenceKind.REASON, reason)).by(actor));
      return Verdict.allow();
    });
  }

  private void apply(final TaskRun task, final PhaseSnapshot snapshot,
      final PhaseOutcome outcome) {
    final TaskPhaseRun current = snapshot.current();
    if (current != null && outcome.phase().isBefore(current.getPhase())) {
      // Evidencia tardía de una fase ya pasada: se guarda, pero no mueve la tarea hacia atrás.
      final TaskPhaseRun past = snapshot.latest(outcome.phase());
      if (past != null) {
        saveEvidence(past, outcome);
      }
      return;
    }
    final TaskPhaseRun row = current != null && current.getPhase() == outcome.phase()
        ? current : open(task, outcome.phase());
    saveEvidence(row, outcome);

    final PhaseStatus before = row.getStatus();
    final PhaseStatus after = rules.resolve(row, outcome,
        evidences.findByTaskPhaseIdOrderByIdAsc(row.getId()), expectedRepos(snapshot, row));
    if (after == before) {
      return;
    }
    if (after.isClosed()) {
      row.close(after, outcome.actor());
    } else {
      row.reopen(after);
    }
    phases.save(row);

    if (after.letsAdvance()) {
      advance(task, row.getPhase());
    } else if (after == PhaseStatus.FAILED && row.getPhase().failureIsTerminal()) {
      terminate(task, TaskPhase.FAILED);
    }
  }

  private void advance(final TaskRun task, final TaskPhase from) {
    final TaskPhase next = from.next();
    if (next == TaskPhase.DONE) {
      terminate(task, TaskPhase.DONE);
    } else {
      open(task, next);
    }
  }

  private TaskPhaseRun open(final TaskRun task, final TaskPhase phase) {
    task.setCurrentPhase(phase.name());
    tasks.save(task);
    return phases.save(new TaskPhaseRun(task.getId(), phase, task.getRemediationIterations(),
        PhaseStatus.IN_PROGRESS));
  }

  /**
   * Lleva la tarea a un terminal. ESCALATED deja {@code task_run} en RUNNING a propósito: así sigue
   * en el panel como "Necesita una persona", igual que tras un GAVE_UP.
   */
  private void terminate(final TaskRun task, final TaskPhase terminal) {
    final PhaseStatus status = terminal == TaskPhase.DONE ? PhaseStatus.PASSED : PhaseStatus.FAILED;
    final TaskPhaseRun row =
        new TaskPhaseRun(task.getId(), terminal, task.getRemediationIterations(), status);
    row.close(status, PhaseOutcome.SIXAI);
    phases.save(row);
    task.setCurrentPhase(terminal.name());
    if (terminal != TaskPhase.ESCALATED) {
      task.close(terminal == TaskPhase.DONE ? TaskRun.DONE : TaskRun.FAILED);
    }
    tasks.save(task);
  }

  private void escalate(final TaskRun task, final PhaseSnapshot snapshot) {
    final TaskPhaseRun current = snapshot.current();
    if (current == null || current.getPhase() != TaskPhase.ESCALATED) {
      terminate(task, TaskPhase.ESCALATED);
    }
  }

  private void saveEvidence(final TaskPhaseRun row, final PhaseOutcome outcome) {
    if (outcome.evidence().isEmpty()) {
      // Sin evidencias igualmente consta la recomendación: es lo que cierra una fase por repos.
      evidences.save(new PhaseEvidence(row.getId(), outcome.repo(), outcome.recommendation(),
          EvidenceKind.REASON, null, null, outcome.actor()));
      return;
    }
    for (final Evidence item : outcome.evidence()) {
      evidences.save(new PhaseEvidence(row.getId(), outcome.repo(), outcome.recommendation(),
          item.kind(), item.detail(), item.url(), outcome.actor()));
    }
  }

  /** Repos que deben dar veredicto en VERIFICATION: los que tuvieron PR en esta iteración. */
  private Set<String> expectedRepos(final PhaseSnapshot snapshot, final TaskPhaseRun row) {
    if (row.getPhase() != TaskPhase.VERIFICATION) {
      return Set.of();
    }
    final TaskPhaseRun implementation = snapshot.latest(TaskPhase.IMPLEMENTATION);
    if (implementation == null || implementation.getIteration() != row.getIteration()) {
      return Set.of();
    }
    return evidences.findByTaskPhaseIdOrderByIdAsc(implementation.getId()).stream()
        .filter(item -> item.getKind() == EvidenceKind.PR && item.getRepo() != null)
        .map(PhaseEvidence::getRepo)
        .collect(Collectors.toSet());
  }

  /** Anota una regla incumplida y, según el modo, la hace valer o la deja pasar. */
  private Verdict decide(final String issueKey, final Verdict verdict) {
    if (verdict.allowed()) {
      return verdict;
    }
    log.warn("Ciclo de vida de {}: {} — {} ({})", issueKey, verdict.rule(), verdict.reason(),
        properties.isEnforce() ? "bloqueado" : "solo aviso");
    taskRecorder.record(issueKey, TaskEventType.LIFECYCLE_VIOLATION, PhaseOutcome.SIXAI,
        verdict.rule() + ": " + verdict.reason());
    return properties.isEnforce() ? verdict : Verdict.allow();
  }

  private Optional<TaskRun> liveTask(final String issueKey) {
    return tasks.findFirstByIssueKeyAndStatusOrderByStartedAtDesc(issueKey, TaskRun.RUNNING);
  }

  private PhaseSnapshot snapshot(final TaskRun task) {
    return PhaseSnapshot.of(phases.findByTaskRunIdOrderByIdAsc(task.getId()));
  }

  /**
   * Transacción propia y captura de errores FUERA de ella: si algo falla dentro (incluso al
   * confirmar), el llamante recibe "permitido" y sigue con su trabajo.
   */
  private Verdict safely(final String issueKey, final String what,
      final Supplier<Verdict> work) {
    try {
      final Verdict verdict = transactions.execute(status -> work.get());
      return verdict == null ? Verdict.allow() : verdict;
    } catch (RuntimeException e) {
      log.warn("El ciclo de vida no pudo registrar {} de {}: {}", what, issueKey, e.getMessage());
      return Verdict.allow();
    }
  }
}
```

- [ ] **Step 2: Compilar (con permiso y servicio parado)**

Run: `mvn compile; echo "exit=$?"`
Expected: `BUILD SUCCESS`, `exit=0`.

- [ ] **Step 3: Verificación manual del usuario (sin código de test)**

1. Arrancar el servicio desde el IDE y comprobar en el log que Flyway aplica `V6__lifecycle` y que Hibernate valida el esquema sin errores (`ddl-auto=validate`).
2. En MariaDB:
   - `SELECT id, issue_key, status, current_phase, remediation_iterations FROM task_run WHERE status = 'RUNNING';` → cada tarea en curso con su fase rellenada.
   - `SELECT * FROM task_phase ORDER BY id;` → una fila por tarea en curso.
3. Abrir el panel de tareas en curso: las etapas se ven igual que antes (en este paso nadie escribe todavía en el lifecycle).

---

## Self-review (hecha al escribir el plan)

- **Cobertura de la spec, paso 1 de §10:** V6 (Task 1) · `TaskPhase`/`PhaseStatus`/entidades/repositorios/`PhaseOutcome`/`Evidence`/`Verdict` (Task 2) · `LIFECYCLE_VIOLATION` + `enforce` (Task 3) · reglas R1–R5 (Task 4) · `TaskLifecycle` en modo registro con `check`/`accept`/`reroute` (Task 5). `snapshot` queda para el paso 5 (decisión 6).
- **Desviaciones de la spec, explícitas arriba:** sin `UNIQUE` en `task_phase` (decisión 1); R3 vinculante en los dos modos (decisión 2); `INTAKE`/`REPO_SELECTION` fallidas terminan la tarea (decisión 3); la regla de relleno "VERIFICATION si hubo `VERIFY_STARTED` después del último `PR_OPENED`" se reduce a "último evento `VERIFY_STARTED`", que es equivalente porque `VERIFY_STARTED` siempre se registra después del `PR_OPENED` de su repo.
- **Coherencia de nombres:** `TaskPhaseRun.close/reopen`, `PhaseSnapshot.of/latest`, `LifecycleRules.canEnter/canAccept/canReroute/budget/resolve/isPromotionRetry`, `TaskLifecycle.check/accept/reroute` usados igual en todas las tareas.
- **Notas para pasos posteriores:** en el paso 4, `DeploymentCoordinator` debe dejar de llamar a `taskRecorder.phase(...)` y `taskRecorder.finish(...)` cuando delegue en el lifecycle, para no cerrar la tarea dos veces ni sobrescribir `current_phase` con `TEST`/`PROD`.
