package es.colorbaby.microservices.dev.relay.monitor;

/**
 * Una tarea en curso, tal y como la pinta el panel flotante: en qué etapa está ahora mismo y desde
 * cuándo.
 *
 * @param issueKey  clave de la tarea en Jira
 * @param title     título de la tarea
 * @param stage     etapa concreta, ya en texto legible ("Compilando develop", "Esperando aprobación")
 * @param stageKey  identificador de la etapa, para que el front elija color/icono sin parsear texto
 * @param detail    matiz de la etapa (el repo que se está compilando, por ejemplo)
 * @param startedAt cuándo empezó
 * @param elapsedMs cuánto lleva
 * @param prCount   PRs abiertas de la tarea
 */
public record ActiveTaskDto(String issueKey, String title, String stage, String stageKey,
    String detail, String startedAt, long elapsedMs, int prCount) {
}
