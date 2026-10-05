package es.colorbaby.microservices.dev.relay.intake;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
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
 *
 * <p>Cada entrada guarda cuánto tiempo sigue marcada. Una issue cuyo procesamiento
 * falló ({@link #retryLater}) se desmarca antes, con espera creciente, en vez de
 * quedarse bloqueada todo el TTL por un fallo pasajero (sin crédito, sin memoria).
 */
@Component
public class ProcessedIssuesTracker {

  /** Primera espera tras un fallo; se duplica en cada fallo seguido, hasta el TTL. */
  private static final Duration RETRY_BASE = Duration.ofMinutes(5);

  /** Cuánto se recuerdan los fallos seguidos de una issue, para que la espera siga creciendo. */
  private static final Duration FAILURES_TTL = Duration.ofHours(24);

  private final Duration ttl;
  private final Cache<String, Duration> processedIssues;
  private final Cache<String, Integer> failures;

  public ProcessedIssuesTracker(JiraSyncProperties syncProperties) {
    this.ttl = Duration.ofMinutes(syncProperties.getDedupTtlMinutes());
    this.processedIssues = Caffeine.newBuilder()
        .expireAfter(new Expiry<String, Duration>() {
          @Override
          public long expireAfterCreate(String key, Duration markedFor, long currentTime) {
            return markedFor.toNanos();
          }

          @Override
          public long expireAfterUpdate(String key, Duration markedFor, long currentTime,
              long currentDuration) {
            return markedFor.toNanos();
          }

          @Override
          public long expireAfterRead(String key, Duration markedFor, long currentTime,
              long currentDuration) {
            return currentDuration;
          }
        })
        .maximumSize(syncProperties.getDedupMaxSize())
        .build();
    this.failures = Caffeine.newBuilder()
        .expireAfterWrite(FAILURES_TTL)
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
      return ttl;
    });
    return isNew[0];
  }

  /**
   * El procesamiento de la issue falló: deja de estar marcada antes del TTL, para que el
   * polling la reintente. La espera se duplica en cada fallo seguido (5, 10, 20, 40 min…) y
   * nunca pasa del TTL, así un fallo que persiste no se reintenta cada ciclo.
   *
   * @return número de fallos seguidos de la issue, contando este (1 = primer fallo)
   */
  public int retryLater(String issueKey) {
    int failed = failures.asMap().merge(issueKey, 1, Integer::sum);
    // Tope al desplazamiento: 2^10 * 5 min ya supera cualquier TTL razonable.
    Duration wait = RETRY_BASE.multipliedBy(1L << Math.min(failed - 1, 10));
    processedIssues.put(issueKey, wait.compareTo(ttl) < 0 ? wait : ttl);
    return failed;
  }
}
