package es.colorbaby.microservices.dev.relay.config;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Verificación de las PRs antes de aprobarlas ({@code maestro.verification}).
 *
 * <p>Resuelve un hueco concreto: quien aprueba una PR de sixai no tenía forma de saber si el código
 * generado siquiera compila, y el primer aviso real llegaba en el build de develop, ya mergeado.
 */
@ConfigurationProperties(prefix = "maestro.verification")
@Validated
@Getter
@Setter
public class VerificationProperties {

  /** Interruptor. Apagado, las PRs se abren igual pero nadie las compila antes de aprobarlas. */
  private boolean enabled = false;

  /** Con {@code true} se registra qué se compilaría, sin lanzar ningún job. */
  private boolean dryRun = true;

  /**
   * Si una verificación fallida <b>impide</b> aprobar.
   *
   * <p>Por defecto NO bloquea, y es a propósito: el valor está en que quien aprueba lo vea, y un
   * bloqueo duro deja la entrega atascada cuando el que falla es Jenkins y no el código. Se pone en
   * {@code true} cuando la verificación lleve tiempo siendo fiable.
   */
  private boolean blockApproval = false;

  /** Intervalo del barrido (ms). */
  @Positive
  private long pollIntervalMs = 30000;

  /** Tope de sondeos por etapa antes de rendirse; se reinicia al pasar de etapa. */
  @Positive
  private int maxAttempts = 60;

  /** Si al fallar la verificación se comenta el diagnóstico del LLM en la PR. */
  private boolean diagnoseOnFailure = true;

  /** Cuánta consola se le pasa al modelo para diagnosticar. */
  @Positive
  private int consoleMaxChars = 8000;
}
