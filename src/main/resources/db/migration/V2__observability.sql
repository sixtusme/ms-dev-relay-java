-- Memoria de sixai: qué se hizo, quién lo pidió, cuánto tardó y qué costó.
--
-- Sin esto sixai no puede responder preguntas sobre su propio trabajo, ni el panel de /sixai puede
-- mostrar historial, ni se puede saber cuánto cuesta cada tarea. Es lo que convierte una tubería en
-- una aplicación con memoria: el evento pequeño de hoy es el dato importante cuando esto escale.

-- ---------------------------------------------------------------------------------------------
-- La tarea: una fila por tarea de Jira que sixai coge. Es el eje del que cuelga todo lo demás.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE task_run (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    issue_key               VARCHAR(64)  NOT NULL,
    title                   VARCHAR(500) NULL,
    epic                    VARCHAR(500) NULL,
    -- Sistema al que pertenece (pim, docs, b2b2c...), tal y como lo resuelve el mapeo de repos.
    system_name             VARCHAR(100) NULL,
    -- Quién lo mandó: el autor del comentario que disparó la tarea.
    requested_by_account_id VARCHAR(128) NULL,
    requested_by_name       VARCHAR(255) NULL,
    -- Cómo se detectó: POLLING o WEBHOOK.
    trigger_source          VARCHAR(20)  NULL,
    -- Dónde está: RUNNING, AWAITING_APPROVAL, PRE, TEST, PROD, DONE, FAILED.
    status                  VARCHAR(30)  NOT NULL,
    current_phase           VARCHAR(20)  NULL,
    started_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at             TIMESTAMP    NULL,
    -- Duración total, desnormalizada a propósito: es la pregunta más frecuente y así no hay que
    -- recalcularla recorriendo eventos.
    duration_ms             BIGINT       NULL,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_task_issue (issue_key),
    INDEX idx_task_status (status),
    INDEX idx_task_started (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------------------------
-- La línea de tiempo. Append-only: cada hito con su instante y su actor. Es la estructura correcta
-- para escalar (nunca se actualiza) y la base de "cuéntame qué pasó con esta tarea".
-- ---------------------------------------------------------------------------------------------
CREATE TABLE task_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_run_id BIGINT       NULL,
    issue_key   VARCHAR(64)  NOT NULL,
    -- DETECTED, REPLIED, IN_PROGRESS, PR_OPENED, CODE_GENERATED, APPROVED, MERGED, BUILD_STARTED,
    -- BUILD_OK, BUILD_FAILED, DEPLOYED_PRE, MOVED_TO_TEST, COMMAND_RECEIVED, PROMOTED, DEPLOYED_PROD...
    type        VARCHAR(40)  NOT NULL,
    -- Quién o qué lo provocó: una persona, "sixai", o un job.
    actor       VARCHAR(255) NULL,
    detail      VARCHAR(2000) NULL,
    occurred_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_event_task FOREIGN KEY (task_run_id) REFERENCES task_run (id) ON DELETE SET NULL,
    INDEX idx_event_task (task_run_id, occurred_at),
    INDEX idx_event_issue (issue_key, occurred_at),
    INDEX idx_event_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------------------------
-- Las PRs que abre sixai, con sus estadísticas. El panel /sixai las pinta, y permiten medir el
-- tamaño real de lo que escribe el coder.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE pull_request (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_run_id          BIGINT       NULL,
    issue_key            VARCHAR(64)  NOT NULL,
    repo                 VARCHAR(150) NOT NULL,
    number               INT          NOT NULL,
    url                  VARCHAR(500) NULL,
    head_branch          VARCHAR(255) NOT NULL,
    base_branch          VARCHAR(255) NOT NULL,
    -- OPEN, MERGED, CLOSED
    state                VARCHAR(20)  NOT NULL,
    files_changed        INT          NULL,
    additions            INT          NULL,
    deletions            INT          NULL,
    opened_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    merged_at            TIMESTAMP    NULL,
    -- Quién aprobó el merge a develop desde el front (la compuerta humana).
    approved_by_account_id VARCHAR(128) NULL,
    approved_at          TIMESTAMP    NULL,
    CONSTRAINT fk_pr_task FOREIGN KEY (task_run_id) REFERENCES task_run (id) ON DELETE SET NULL,
    UNIQUE KEY uk_pr_repo_number (repo, number),
    INDEX idx_pr_issue (issue_key),
    INDEX idx_pr_state (state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------------------------
-- Los comandos /sixai. Sustituye a processed_comment: hace de clave de idempotencia (comment_id
-- único) Y de historial, en vez de tener dos tablas diciendo lo mismo a medias.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE command_execution (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    comment_id        VARCHAR(64)  NOT NULL,
    task_run_id       BIGINT       NULL,
    issue_key         VARCHAR(64)  NOT NULL,
    author_account_id VARCHAR(128) NULL,
    author_name       VARCHAR(255) NULL,
    -- La orden tal cual la escribió la persona; es oro para afinar el intérprete.
    raw_text          VARCHAR(2000) NULL,
    issue_status      VARCHAR(50)  NULL,
    -- Intención resuelta: PROMOTE_TO_PROD, REVISE, REDEPLOY, STATUS, CANCEL, UNKNOWN.
    intent            VARCHAR(30)  NULL,
    -- Sobre todo para PROD: si quien lo pidió tenía permiso.
    authorized        BOOLEAN      NULL,
    outcome           VARCHAR(30)  NULL,
    error             VARCHAR(1000) NULL,
    received_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    handled_at        TIMESTAMP    NULL,
    duration_ms       BIGINT       NULL,
    CONSTRAINT fk_cmd_task FOREIGN KEY (task_run_id) REFERENCES task_run (id) ON DELETE SET NULL,
    UNIQUE KEY uk_cmd_comment (comment_id),
    INDEX idx_cmd_issue (issue_key),
    INDEX idx_cmd_intent (intent)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- La idempotencia de comandos pasa a vivir en command_execution (comment_id único): una sola
-- fuente de verdad en vez de dos tablas solapadas.
DROP TABLE processed_comment;

-- ---------------------------------------------------------------------------------------------
-- Cada llamada al modelo. Es lo que permite responder "cuánto cuesta una tarea" y detectar que un
-- rol se ha vuelto caro o lento. Encaja con la metadata que ya se manda al gateway de modelos.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE llm_call (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_run_id       BIGINT       NULL,
    issue_key         VARCHAR(64)  NULL,
    -- responder, selector, coder, router, diagnose...
    role              VARCHAR(30)  NOT NULL,
    model             VARCHAR(120) NULL,
    prompt_tokens     INT          NULL,
    completion_tokens INT          NULL,
    total_tokens      INT          NULL,
    cost_usd          DECIMAL(12,6) NULL,
    latency_ms        BIGINT       NULL,
    success           BOOLEAN      NOT NULL DEFAULT TRUE,
    error             VARCHAR(1000) NULL,
    called_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_llm_task FOREIGN KEY (task_run_id) REFERENCES task_run (id) ON DELETE SET NULL,
    INDEX idx_llm_role (role),
    INDEX idx_llm_called (called_at),
    INDEX idx_llm_issue (issue_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------------------------
-- Índice de los informes .md que genera sixai. El fichero puede vivir fuera (SFTP), pero el índice
-- vive aquí: así el panel lista y busca al instante y solo va a buscar el contenido al abrirlo.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE report (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_run_id  BIGINT        NULL,
    issue_key    VARCHAR(64)   NULL,
    -- DELIVERY, DIAGNOSIS, REVIEW, SUMMARY...
    kind         VARCHAR(40)   NOT NULL,
    title        VARCHAR(300)  NOT NULL,
    -- Dónde está el fichero: sftp://host/ruta/informe.md (o file://, s3://... si cambia el destino).
    storage_uri  VARCHAR(1000) NOT NULL,
    format       VARCHAR(20)   NOT NULL DEFAULT 'md',
    size_bytes   BIGINT        NULL,
    -- Para detectar que el fichero cambió o se corrompió sin tener que descargarlo entero.
    checksum     VARCHAR(128)  NULL,
    generated_by VARCHAR(120)  NULL,
    generated_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_report_task FOREIGN KEY (task_run_id) REFERENCES task_run (id) ON DELETE SET NULL,
    INDEX idx_report_issue (issue_key),
    INDEX idx_report_kind (kind),
    INDEX idx_report_generated (generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------------------------
-- El chat del panel: conversaciones para preguntar por lo hecho. Separado de los comandos de Jira
-- a propósito: aquí se consulta y se razona, allí se ordena y se actúa.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE chat_conversation (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_run_id          BIGINT       NULL,
    issue_key            VARCHAR(64)  NULL,
    title                VARCHAR(300) NULL,
    created_by_account_id VARCHAR(128) NULL,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_task FOREIGN KEY (task_run_id) REFERENCES task_run (id) ON DELETE SET NULL,
    INDEX idx_chat_issue (issue_key),
    INDEX idx_chat_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- OJO si algún día se mapea en JPA: `content` es TEXT, así que hace falta @JdbcTypeCode
-- (SqlTypes.LONGVARCHAR) o columnDefinition; si no, ddl-auto=validate se queja del tipo.
CREATE TABLE chat_message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT      NOT NULL,
    -- user, assistant, system
    role            VARCHAR(20) NOT NULL,
    content         TEXT        NOT NULL,
    -- Si la respuesta la generó el modelo, aquí se enlaza su coste y latencia.
    llm_call_id     BIGINT      NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_msg_conversation FOREIGN KEY (conversation_id)
        REFERENCES chat_conversation (id) ON DELETE CASCADE,
    CONSTRAINT fk_msg_llm FOREIGN KEY (llm_call_id) REFERENCES llm_call (id) ON DELETE SET NULL,
    INDEX idx_msg_conversation (conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
