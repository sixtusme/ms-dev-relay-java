package es.colorbaby.microservices.dev.relay.tracker;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import es.colorbaby.microservices.dev.relay.config.JiraSyncProperties;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Deduplicación en memoria de issues ya procesadas, para que el polling no
 * vuelva a comentar/publicar el evento en cada ciclo mientras el comentario
 * con la keyword siga presente.
 *
 * <p>Respaldado por un cache Caffeine con TTL y tamaño máximo, de modo que no
 * crece indefinidamente (evita el leak de un Set sin expiración): las entradas
 * caducan pasado maestro.jira.sync.dedup-ttl-minutes y se expulsan las menos
 * usadas al superar maestro.jira.sync.dedup-max-size. Sigue siendo estado
 * local a la instancia; si en el futuro se necesita compartir entre varias
 * instancias o sobrevivir a reinicios, deberá persistirse (fuera de alcance).
 */
@Component
public class ProcessedIssuesTracker {

  private final Cache<String, Boolean> processedIssues;

  public ProcessedIssuesTracker(JiraSyncProperties syncProperties) {
    this.processedIssues = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(syncProperties.getDedupTtlMinutes()))
        .maximumSize(syncProperties.getDedupMaxSize())
        .build();
  }

  /**
   * Marca la issue como procesada si no lo estaba ya (de forma atómica).
   *
   * @return true si es la primera vez que se marca (hay que procesarla).
   */
  public boolean markIfNew(String issueKey) {
    boolean[] isNew = {false};
    processedIssues.get(issueKey, key -> {
      isNew[0] = true;
      return Boolean.TRUE;
    });
    return isNew[0];
  }
}
