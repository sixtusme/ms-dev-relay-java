-- Ejecuciones de agente paradas en NEEDS_INPUT, a la espera de que una persona conteste en la
-- propia tarea de Jira (mejoras-senior §8: BLOCKED de primera clase + resumir el mismo contexto
-- de ejecución en vez de reiniciar el agente desde cero).
CREATE TABLE agent_suspension (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    execution_id     VARCHAR(64)   NOT NULL,
    agent_id         VARCHAR(50)   NOT NULL,
    issue_key        VARCHAR(50)   NOT NULL,
    repo             VARCHAR(150)  NOT NULL,
    task_title       VARCHAR(500)  NULL,
    -- MEDIUMTEXT porque puede llevar la corrección solicitada anexada; ver V5 (indexed_document)
    -- para el mismo criterio con ddl-auto=validate.
    task_description MEDIUMTEXT    NULL,
    question         VARCHAR(2000) NOT NULL,
    -- Mensajes y estado del AgentContext, serializados como JSON: lo que hace falta para
    -- reconstruirlo tal cual estaba y seguir el mismo bucle LLM→tool→LLM, no reiniciar el agente.
    messages         MEDIUMTEXT    NOT NULL,
    state            MEDIUMTEXT    NOT NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Una sola ejecución pendiente por tarea/repo/agente a la vez.
    UNIQUE INDEX uq_agent_suspension_task (issue_key, repo, agent_id)
);
