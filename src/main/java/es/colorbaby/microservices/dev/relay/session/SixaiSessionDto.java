package es.colorbaby.microservices.dev.relay.session;

import java.util.List;

/**
 * Una sesión de sixai: el trabajo en curso de una issue, con sus PRs abiertas. Se reconstruye
 * leyendo GitHub, así que representa lo que hay abierto ahora mismo (no un histórico persistido).
 */
public record SixaiSessionDto(String issueKey, String title, List<SixaiPrDto> prs) {
}
