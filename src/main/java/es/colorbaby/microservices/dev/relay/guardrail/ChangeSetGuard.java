package es.colorbaby.microservices.dev.relay.guardrail;

import es.colorbaby.microservices.dev.relay.coder.ChangeSet;
import es.colorbaby.microservices.dev.relay.coder.FileChange;
import es.colorbaby.microservices.dev.relay.config.GuardrailProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Filtra lo que el coder quiere escribir ANTES de tocar el repositorio.
 *
 * <p>Es el guardarraíl de salida que más importa: sin él, sixai escribía en cualquier ruta que
 * devolviera el modelo. Y hay rutas que convierten un cambio de código en un problema de seguridad:
 * <ul>
 *   <li><b>La CI</b> ({@code .github/workflows}, {@code Jenkinsfile}): quien la edita, ejecuta lo
 *       que quiera con las credenciales del pipeline. Es la vía más directa para secuestrarlo.</li>
 *   <li><b>Material criptográfico</b> ({@code .pem}, {@code .key}, {@code id_rsa}): una clave no se
 *       edita, se rota.</li>
 *   <li><b>Rutas fuera del repo</b> ({@code ../}, rutas absolutas): no deberían existir siquiera.</li>
 * </ul>
 *
 * <p>Lo descartado no se aplica pero <b>sí se registra</b>: un intento de escribir en la CI es
 * justo lo que hay que poder ver después.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChangeSetGuard {

  private final GuardrailProperties properties;

  /** Resultado del filtrado: lo que se puede aplicar y lo que se ha bloqueado. */
  public record Result(ChangeSet allowed, List<String> rejected) {

    public boolean hasRejections() {
      return !rejected.isEmpty();
    }
  }

  /** Deja solo los cambios admisibles. */
  public Result filter(final ChangeSet changeSet) {
    if (!properties.isEnabled() || !properties.isGuardChangeSets()) {
      return new Result(changeSet, List.of());
    }
    final List<FileChange> allowed = new ArrayList<>();
    final List<String> rejected = new ArrayList<>();

    for (final FileChange change : changeSet.changes()) {
      final String reason = reject(change);
      if (reason == null) {
        allowed.add(change);
      } else {
        rejected.add(change.path() + " — " + reason);
        log.warn("Cambio BLOQUEADO por guardarraíl: {} — {}", change.path(), reason);
      }
    }
    return new Result(new ChangeSet(changeSet.summary(), allowed), rejected);
  }

  /** Motivo por el que se rechaza un cambio, o null si es admisible. */
  private String reject(final FileChange change) {
    final String path = change.path() == null ? "" : change.path().strip().replace('\\', '/');

    if (path.isBlank()) {
      return "ruta vacía";
    }
    if (path.startsWith("/") || path.matches("^[A-Za-z]:/.*")) {
      return "ruta absoluta";
    }
    if (path.contains("..")) {
      return "sale del repositorio";
    }

    final String lower = path.toLowerCase(Locale.ROOT);
    for (final String denied : properties.getDeniedPaths()) {
      if (denied != null && !denied.isBlank()
          && lower.contains(denied.toLowerCase(Locale.ROOT))) {
        return "ruta protegida (" + denied + ")";
      }
    }

    final int size = change.content() == null
        ? 0 : change.content().getBytes(StandardCharsets.UTF_8).length;
    if (size > properties.getMaxFileBytes()) {
      return "fichero demasiado grande (" + size + " bytes)";
    }
    return null;
  }
}
