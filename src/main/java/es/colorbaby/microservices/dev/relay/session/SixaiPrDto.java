package es.colorbaby.microservices.dev.relay.session;

/** Una PR abierta de sixai dentro de una sesión: repo, número, url y ramas origen/destino. */
public record SixaiPrDto(String repo, int number, String url, String branch, String base) {
}
