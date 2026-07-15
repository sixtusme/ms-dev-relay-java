package es.colorbaby.microservices.dev.relay.jira;

import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraCommentDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraTransitionDto;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Decorador de {@link JiraClient} para el modo simulación ({@code maestro.responder.dry-run=true}).
 *
 * <p>Deja pasar todas las LECTURAS al cliente real (para que la tubería corra de verdad: búsqueda,
 * comentarios, transiciones disponibles) y CORTA todas las ESCRITURAS: en vez de llamar a Jira, las
 * loguea con prefijo {@code [DRY-RUN]}. Es el único punto que garantiza que nada se escribe, así no
 * hay que sembrar {@code if (dryRun)} por el responder ni por la capa de detección.
 */
@Slf4j
public class DryRunJiraClient implements JiraClient {

  private final JiraClient delegate;

  public DryRunJiraClient(JiraClient delegate) {
    this.delegate = delegate;
  }

  // --- Lecturas: delegan en el cliente real ---

  @Override
  public JiraIssueDto getIssue(String issueKey) {
    return delegate.getIssue(issueKey);
  }

  @Override
  public List<JiraIssueDto> searchIssuesByJql(String jql) {
    return delegate.searchIssuesByJql(jql);
  }

  @Override
  public List<JiraCommentDto> getComments(String issueKey) {
    return delegate.getComments(issueKey);
  }

  @Override
  public List<JiraTransitionDto> getTransitions(String issueKey) {
    return delegate.getTransitions(issueKey);
  }

  // --- Escrituras: se loguean y NO se ejecutan ---

  @Override
  public JiraCommentDto addComment(String issueKey, String plainTextBody) {
    log.info("[DRY-RUN] Omitido comentario en {}:\n{}", issueKey, plainTextBody);
    return null;
  }

  @Override
  public JiraCommentDto addCommentAdf(String issueKey, Object adfDocument) {
    log.info("[DRY-RUN] Omitido comentario (ADF con mención/cita) en {}", issueKey);
    return null;
  }

  @Override
  public void transitionIssue(String issueKey, String transitionId) {
    log.info("[DRY-RUN] Omitida transición {} en {}", transitionId, issueKey);
  }

  @Override
  public void transitionIssueByStatusName(String issueKey, String targetStatusName) {
    log.info("[DRY-RUN] Omitido mover {} al estado '{}'", issueKey, targetStatusName);
  }

  @Override
  public void assignIssue(String issueKey, String accountIdOrUsername) {
    log.info("[DRY-RUN] Omitida reasignación de {} a {}", issueKey, accountIdOrUsername);
  }

  @Override
  public void addLabel(String issueKey, String label) {
    log.info("[DRY-RUN] Omitida etiqueta '{}' en {}", label, issueKey);
  }
}
