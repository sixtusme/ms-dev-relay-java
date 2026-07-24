-- Estado persistente de sixai.
--
-- Antes vivía en memoria y eso era un problema serio: un despliegue (build + deploy) dura ~30
-- minutos, así que un reinicio a mitad de una promoción a PRODUCCIÓN dejaba la tarea colgada para
-- siempre, sin aviso. Aquí queda el estado que debe sobrevivir a reinicios.

-- Un lote de despliegue: lo que hay que desplegar de UNA tarea para UNA fase (PRE o PROD).
-- La tarea solo avanza cuando TODOS los repos del lote han terminado bien.
CREATE TABLE deployment_batch (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    issue_key           VARCHAR(64)  NOT NULL,
    phase               VARCHAR(10)  NOT NULL,
    reporter_account_id VARCHAR(128) NULL,
    status              VARCHAR(20)  NOT NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_batch_issue_phase (issue_key, phase),
    INDEX idx_batch_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- El despliegue de UN repo dentro de un lote, con su punto exacto del recorrido:
-- build encolado -> build corriendo -> (versión en Harbor) -> deploy encolado -> deploy corriendo.
CREATE TABLE deployment_run (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id            BIGINT       NOT NULL,
    issue_key           VARCHAR(64)  NOT NULL,
    repo                VARCHAR(150) NOT NULL,
    service             VARCHAR(150) NOT NULL,
    branch              VARCHAR(255) NOT NULL,
    environment         VARCHAR(50)  NOT NULL,
    phase               VARCHAR(10)  NOT NULL,
    build_job           VARCHAR(255) NOT NULL,
    deploy_job          VARCHAR(255) NOT NULL,
    stage               VARCHAR(30)  NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    queue_url           VARCHAR(500) NULL,
    build_number        INT          NOT NULL DEFAULT 0,
    version             VARCHAR(100) NULL,
    deploy_build_number INT          NOT NULL DEFAULT 0,
    attempts            INT          NOT NULL DEFAULT 0,
    -- VARCHAR y no TEXT: con ddl-auto=validate, Hibernate compara tipos y un TEXT contra un String
    -- da problemas. El motivo del fallo se trunca a este tamaño al guardarlo.
    failure_reason      VARCHAR(2000) NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_run_batch FOREIGN KEY (batch_id) REFERENCES deployment_batch (id),
    INDEX idx_run_status (status),
    INDEX idx_run_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Comandos (/sixai, @sixai) ya atendidos. La idempotencia es POR COMENTARIO, no por tarea: una
-- tarea en curso es una conversación que recibe N órdenes, así que la etiqueta sixai-procesada no
-- sirve de marca. Persistirlo evita que un reinicio reejecute un "pásalo a PROD" antiguo.
CREATE TABLE processed_comment (
    comment_id   VARCHAR(64) PRIMARY KEY,
    issue_key    VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_processed_issue (issue_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ajustes internos de sixai. De momento guarda `commands_since`: el instante a partir del cual se
-- atienden comandos, fijado la primera vez que arranca. Sin esto, al estrenar la base de datos
-- sixai ejecutaría comentarios antiguos de tareas viejas como si fueran órdenes nuevas.
CREATE TABLE sixai_setting (
    name       VARCHAR(64)  PRIMARY KEY,
    value      VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
