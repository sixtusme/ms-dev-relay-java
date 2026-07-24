package es.colorbaby.microservices.dev.relay.session;

/**
 * Una PR abierta de sixai dentro de una sesión: repo, número, url, ramas origen/destino y si su
 * rama compila.
 *
 * @param verification veredicto de la verificación: {@code PENDING} (compilando), {@code OK},
 *                     {@code FAILED}, o {@code NONE} si no se ha verificado
 * @param verificationDetail motivo del fallo, cuando lo hay
 */
public record SixaiPrDto(String repo, int number, String url, String branch, String base,
    String verification, String verificationDetail) {

  /** Estados posibles de la verificación de una PR. */
  public static final String NONE = "NONE";
  public static final String PENDING = "PENDING";
  public static final String OK = "OK";
  public static final String FAILED = "FAILED";
}
