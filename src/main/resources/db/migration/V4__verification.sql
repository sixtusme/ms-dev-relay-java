-- Verificación de una PR antes de aprobarla: se compila su rama con FAST_SNAPSHOT_BUILD para saber
-- si el código del coder al menos compila. Se persiste por el mismo motivo que deployment_run: un
-- build tarda minutos y un reinicio a mitad no puede dejar la PR sin veredicto para siempre.
CREATE TABLE verification_run (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    issue_key      VARCHAR(64)  NOT NULL,
    repo           VARCHAR(150) NOT NULL,
    branch         VARCHAR(255) NOT NULL,
    pr_number      INT          NOT NULL DEFAULT 0,
    build_job      VARCHAR(255) NOT NULL,
    stage          VARCHAR(30)  NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    queue_url      VARCHAR(500) NULL,
    build_number   INT          NOT NULL DEFAULT 0,
    attempts       INT          NOT NULL DEFAULT 0,
    -- VARCHAR y no TEXT: con ddl-auto=validate Hibernate compara tipos y un TEXT contra un String
    -- da problemas. El motivo se trunca a este tamaño al guardarlo.
    failure_reason VARCHAR(2000) NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- El barrido busca por estado; el panel y la aprobación, por tarea.
    INDEX idx_verification_run_status (status),
    INDEX idx_verification_run_issue (issue_key)
);
