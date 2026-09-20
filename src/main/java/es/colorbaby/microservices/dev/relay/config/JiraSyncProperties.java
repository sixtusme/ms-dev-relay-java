package es.colorbaby.microservices.dev.relay.config;

import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configura cómo se detectan las tareas de Jira asignadas: webhook, polling
 * o ambos, intercambiable sin recompilar vía maestro.jira.sync.mode.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "maestro.jira.sync")
public class JiraSyncProperties {

  /**
   * WEBHOOK | POLLING | BOTH.
   */
  private SyncMode mode = SyncMode.POLLING;

  /**
   * Intervalo entre ciclos de polling, en milisegundos.
   */
  @Positive
  private long pollingIntervalMs = 60000;

  /**
   * Secreto compartido esperado en la cabecera X-Maestro-Webhook-Secret.
   */
  private String webhookSecret;

  /**
   * Minutos que una issue permanece marcada como "ya procesada" (dedup). Al
   * expirar, una issue que siga siendo elegible podría volver a procesarse.
   */
  @Positive
  private long dedupTtlMinutes = 60;

  /**
   * Nº máximo de issues retenidas en el dedup en memoria (cota superior de
   * memoria; se expulsan las menos usadas al superarlo).
   */
  @Positive
  private long dedupMaxSize = 10000;
}
