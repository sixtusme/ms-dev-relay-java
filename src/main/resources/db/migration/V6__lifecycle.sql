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
