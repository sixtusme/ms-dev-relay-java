-- Índice del Knowledge: documentos de la documentación del proyecto, con su embedding, para
-- retrieval semántico. content_hash evita reincrustar (llamar al LLM) un documento que no ha
-- cambiado desde la última vez que arrancó el indexador.
CREATE TABLE indexed_document (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    source          VARCHAR(255)  NOT NULL,
    title           VARCHAR(255)  NOT NULL,
    content         MEDIUMTEXT    NOT NULL,
    content_hash    VARCHAR(64)   NOT NULL,
    -- Vector de embedding serializado como JSON (array de doubles). Sin vector DB dedicado: el
    -- corpus es la documentación del proyecto, de tamaño pequeño, así que comparar en memoria con
    -- coseno es más que suficiente y no añade infraestructura nueva.
    embedding       MEDIUMTEXT    NOT NULL,
    embedding_model VARCHAR(100)  NOT NULL,
    indexed_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uq_indexed_document_source (source)
);
