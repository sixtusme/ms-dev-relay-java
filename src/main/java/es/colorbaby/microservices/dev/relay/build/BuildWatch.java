package es.colorbaby.microservices.dev.relay.build;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Un build a vigilar: la rama de un repo (ej. {@code develop} tras el merge) cuyo build sigue a una
 * issue, y la ruta de su job en Jenkins. {@code attempts} lleva la cuenta de sondeos hechos (mutable
 * a propósito, para el tope).
 */
@Getter
@RequiredArgsConstructor
public class BuildWatch {

  /** Clave de la issue de Jira a la que pertenece la PR. */
  private final String issueKey;

  /** Repo donde se abrió la PR. */
  private final String repo;

  /** Rama de trabajo de sixai. */
  private final String branch;

  /** Ruta del job en Jenkins (ya con {repo}/{branch} sustituidos y la rama URL-encoded). */
  private final String jobPath;

  /** Sondeos realizados sobre este build. */
  private int attempts;

  /** Suma un sondeo y devuelve el total. */
  public int incrementAttempts() {
    return ++attempts;
  }
}
