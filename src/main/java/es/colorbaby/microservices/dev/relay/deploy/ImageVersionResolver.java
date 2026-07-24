package es.colorbaby.microservices.dev.relay.deploy;

import es.colorbaby.microservices.dev.relay.harbor.client.HarborClient;
import es.colorbaby.microservices.dev.relay.jenkins.client.JenkinsClient;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Con qué VERSION hay que desplegar, es decir: qué imagen produjo <b>este</b> build.
 *
 * <p>Parece lo mismo que "la última etiqueta del servicio en Harbor", y no lo es. Harbor ordena por
 * fecha de subida, así que esa etiqueta es la del último build que <b>terminó</b>, sea de quien
 * sea. Si entre que acaba nuestro build y se consulta Harbor entra otro del mismo servicio —un
 * compañero compilando, o el propio sixai promocionando a producción mientras corre un PRE— se
 * despliega la imagen ajena. Y el fallo es mudo: el job va verde y en la máquina hay otra cosa.
 *
 * <p>La versión sí es un dato del build concreto: el pipeline la calcula, la escribe en el proyecto
 * y la echa por consola antes de hacer {@code docker push ...:VERSION}. Leerla de la consola de ese
 * número de build la ata al build que la produjo, que es justo la garantía que hacía falta.
 *
 * <p>Si el formato de la consola cambiara y no se encontrara, se cae a la etiqueta más reciente de
 * Harbor: se vuelve al comportamiento de antes, pero avisando, en vez de dejar de desplegar.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageVersionResolver {

  /**
   * Lo que echa el pipeline al fijar la versión en el proyecto. Es la definitiva: es la que acaba
   * en el tag de la imagen y en el tag de git. El punto en lugar de la "ó" evita depender de cómo
   * venga codificada la consola.
   */
  private static final Pattern SET_VERSION =
      Pattern.compile("Estableciendo nueva versi.n \\[([^\\]]+)]");

  /** La que anuncia el cálculo, un paso antes. Sirve de reserva por si cambiara el primer mensaje. */
  private static final Pattern CANDIDATE_VERSION =
      Pattern.compile("La pr.xima versi.n ser.: (\\S+)");

  private final JenkinsClient jenkinsClient;
  private final HarborClient harborClient;

  /**
   * Versión que publicó el build de este despliegue.
   *
   * @param run despliegue con el job y el número de build ya resueltos
   * @return la versión, o vacío si no se pudo averiguar ni por consola ni por Harbor
   */
  public Optional<String> resolve(final DeploymentRun run) {
    final Optional<String> fromConsole = fromConsole(run);
    if (fromConsole.isPresent()) {
      return fromConsole;
    }
    log.warn("No pude leer la versión en la consola del build #{} de {}; tiro de la última etiqueta "
        + "de {} en Harbor, que puede no ser la de este build",
        run.getBuildNumber(), run.getBuildJob(), run.getService());
    return harborClient.latestTag(run.getService());
  }

  private Optional<String> fromConsole(final DeploymentRun run) {
    final String console;
    try {
      console = jenkinsClient.getConsoleLog(run.getBuildJob(), run.getBuildNumber());
    } catch (RuntimeException e) {
      log.warn("No se pudo leer la consola del build #{} de {}: {}",
          run.getBuildNumber(), run.getBuildJob(), e.getMessage());
      return Optional.empty();
    }
    if (console == null || console.isBlank()) {
      return Optional.empty();
    }
    // Se busca la ÚLTIMA aparición: si el pipeline reintentara un paso, la buena es la de después.
    return lastMatch(SET_VERSION, console).or(() -> lastMatch(CANDIDATE_VERSION, console));
  }

  private static Optional<String> lastMatch(final Pattern pattern, final String text) {
    final Matcher matcher = pattern.matcher(text);
    String found = null;
    while (matcher.find()) {
      found = matcher.group(1).trim();
    }
    return found == null || found.isEmpty() ? Optional.empty() : Optional.of(found);
  }
}
