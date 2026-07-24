package es.colorbaby.microservices.dev.relay.command;

import java.time.Instant;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotencia de los comandos, a nivel de COMENTARIO y <b>persistida</b>. Con los comandos una
 * tarea deja de ser "procesar una vez" y pasa a ser una conversación que recibe N órdenes, así que
 * la etiqueta {@code sixai-procesada} no vale como marca.
 *
 * <p>Antes esto vivía en memoria y obligaba a ignorar todo comentario anterior al arranque del
 * proceso: un reinicio perdía órdenes. Ahora el corte se fija <b>una sola vez</b>, la primera vez
 * que sixai arranca contra esta base de datos, y se guarda. Así:
 * <ul>
 *   <li>no se ejecutan comentarios antiguos de tareas viejas al estrenar la base de datos;</li>
 *   <li>un reinicio ya NO pierde órdenes: lo atendido se recuerda y lo pendiente se atiende.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessedCommentsTracker {

  private final CommandExecutionRepository commands;
  private final SixaiSettingRepository settings;

  /**
   * Marca el comentario como atendido si no lo estaba (idempotente y duradero). La fila creada es
   * además el registro histórico del comando, que se completa luego con la intención y el desenlace.
   *
   * @return true si es la primera vez (hay que procesarlo)
   */
  @Transactional
  public boolean markIfNew(final String commentId, final String issueKey) {
    if (commands.existsByCommentId(commentId)) {
      return false;
    }
    commands.save(new CommandExecution(commentId, issueKey));
    return true;
  }

  /**
   * True si el comentario es anterior al corte (comentarios que ya existían cuando sixai empezó a
   * mirar esta base de datos): no son órdenes para él.
   */
  @Transactional
  public boolean isBeforeWatermark(final OffsetDateTime created) {
    return created != null && created.toInstant().isBefore(commandsSince());
  }

  /** Instante de corte; se fija y persiste la primera vez que se necesita. */
  private Instant commandsSince() {
    return settings.findById(SixaiSetting.COMMANDS_SINCE)
        .map(s -> Instant.parse(s.getValue()))
        .orElseGet(this::initialiseWatermark);
  }

  private Instant initialiseWatermark() {
    final Instant now = Instant.now();
    settings.save(new SixaiSetting(SixaiSetting.COMMANDS_SINCE, now.toString()));
    log.info("Primer arranque con base de datos: se atenderán comandos posteriores a {}", now);
    return now;
  }
}
