package es.colorbaby.microservices.dev.relay.guardrail;

import es.colorbaby.microservices.dev.relay.config.GuardrailProperties;
import es.colorbaby.microservices.dev.relay.harbor.config.HarborProperties;
import es.colorbaby.microservices.dev.relay.jenkins.config.JenkinsProperties;
import es.colorbaby.microservices.dev.relay.jira.config.JiraProperties;
import es.colorbaby.microservices.dev.relay.github.config.GithubProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Enmascara secretos en el texto que entra al modelo y en el que sale hacia Jira.
 *
 * <p>Hace falta porque sixai mete en el prompt consolas de Jenkins y logs de contenedores, que
 * arrastran tokens y contraseñas con toda naturalidad. Sin esto, un secreto puede acabar en el
 * proveedor del modelo o publicado en un comentario de una tarea.
 *
 * <p>Va en dos capas: <b>los secretos concretos que sixai conoce</b> (sus propias credenciales, que
 * es lo más probable que aparezca) y <b>formas genéricas</b> de secreto. La primera es la que de
 * verdad protege; la segunda es la red por si aparece algo ajeno.
 */
@Component
@RequiredArgsConstructor
public class SecretRedactor {

  private static final String MASK = "«oculto»";

  /** Formas habituales de secreto. Deliberadamente conservador: mejor tapar de más. */
  private static final List<Pattern> PATTERNS = List.of(
      // clave: valor / clave=valor
      Pattern.compile("(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|apikey|"
          + "authorization|access[_-]?key)\\b\\s*[:=]\\s*[^\\s,;\"']{4,}"),
      // Cabeceras de autenticación
      Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._\\-]{10,}"),
      Pattern.compile("(?i)\\bbasic\\s+[A-Za-z0-9+/=]{10,}"),
      // JWT
      Pattern.compile("\\beyJ[A-Za-z0-9._\\-]{20,}"),
      // Credenciales dentro de una URL (jdbc://user:pass@host)
      Pattern.compile("://[^/\\s:@]+:[^/\\s@]+@"),
      // Claves privadas completas
      Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")
  );

  private final GuardrailProperties properties;
  private final JiraProperties jiraProperties;
  private final GithubProperties githubProperties;
  private final JenkinsProperties jenkinsProperties;
  private final HarborProperties harborProperties;

  /** Devuelve el texto con los secretos enmascarados. */
  public String redact(final String text) {
    if (text == null || text.isBlank() || !properties.isEnabled()
        || !properties.isRedactSecrets()) {
      return text;
    }
    String result = text;

    // Primero los valores concretos: si el token real aparece, se tapa entero pase lo que pase.
    for (final String secret : knownSecrets()) {
      result = result.replace(secret, MASK);
    }
    for (final Pattern pattern : PATTERNS) {
      result = pattern.matcher(result).replaceAll(MASK);
    }
    return result;
  }

  /** Las credenciales que sixai tiene configuradas: lo más probable que aparezca en un log suyo. */
  private List<String> knownSecrets() {
    final List<String> secrets = new ArrayList<>();
    add(secrets, jiraProperties.getApiToken());
    add(secrets, githubProperties.getToken());
    add(secrets, jenkinsProperties.getApiToken());
    add(secrets, harborProperties.getPassword());
    return secrets;
  }

  /** Solo valores con longitud suficiente: enmascarar cadenas cortas destrozaría el texto. */
  private static void add(final List<String> secrets, final String value) {
    if (value != null && value.length() >= 8) {
      secrets.add(value);
    }
  }
}
