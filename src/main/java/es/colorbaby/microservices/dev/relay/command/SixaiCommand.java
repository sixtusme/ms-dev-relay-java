package es.colorbaby.microservices.dev.relay.command;

import es.colorbaby.microservices.dev.relay.openapi.model.JiraUserDto;

/**
 * Un comentario dirigido a sixai, ya parseado: de qué issue es, qué comentario lo originó (clave de
 * idempotencia), quién lo escribió (para autorizar) y la instrucción en texto libre que sigue al
 * prefijo. El estado de la tarea viaja aparte porque es el que da contexto a la intención.
 */
public record SixaiCommand(String issueKey, String commentId, JiraUserDto author,
    String instruction, String issueStatus) {
}
