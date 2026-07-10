package es.colorbaby.microservices.dev.relay.api;

import es.colorbaby.microservices.dev.relay.config.JiraSyncProperties;
import es.colorbaby.microservices.dev.relay.event.TriggerSource;
import es.colorbaby.microservices.dev.relay.openapi.api.WebhooksApiDelegate;
import es.colorbaby.microservices.dev.relay.openapi.model.RequestJiraWebhookEventDto;
import es.colorbaby.microservices.dev.relay.service.IssueTriggerService;
import es.colorbaby.microservices.essential.common.exceptions.InvalidCredentialsException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Implementación del delegate del contrato para el webhook de Jira. El
 * {@code @Controller} real es el WebhooksApiController generado por el
 * openapi-generator, que hace el {@code @RequestMapping} y delega en este
 * bean (mismo patrón que CustomsApiDelegateImpl en ms-aduana).
 *
 * <p>Solo se registra como bean (y por tanto responde 200/401) si
 * maestro.jira.sync.mode es WEBHOOK o BOTH; en POLLING no existe este bean y
 * el controller generado usa el delegate por defecto, que devuelve 501.
 * Valida el secreto compartido y delega en IssueTriggerService, que aplica
 * el mismo filtro de elegibilidad que el polling.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression(
    "'${maestro.jira.sync.mode:POLLING}' == 'WEBHOOK' or '${maestro.jira.sync.mode:POLLING}' == 'BOTH'")
public class WebhooksApiDelegateImpl implements WebhooksApiDelegate {

  private static final String WEBHOOK_SECRET_HEADER = "X-Maestro-Webhook-Secret";

  private final HttpServletRequest request;
  private final JiraSyncProperties jiraSyncProperties;
  private final IssueTriggerService issueTriggerService;

  @Override
  public ResponseEntity<Void> receiveJiraWebhook(RequestJiraWebhookEventDto event) {
    validateSharedSecret();

    // El contrato ya restringe webhookEvent a los tipos que nos interesan
    // (jira:issue_created, jira:issue_updated, comment_created); solo falta
    // comprobar que el payload trae la issue. El procesamiento es asíncrono
    // (IssueTriggerService.process es @Async): respondemos 200 de inmediato y
    // el trabajo pesado contra Jira ocurre fuera del hilo de respuesta, para
    // que Jira no reintente el webhook.
    if (event.getIssue() != null) {
      issueTriggerService.process(event.getIssue(), TriggerSource.WEBHOOK);
    }
    return ResponseEntity.ok().build();
  }

  private void validateSharedSecret() {
    String expected = jiraSyncProperties.getWebhookSecret();
    String received = request.getHeader(WEBHOOK_SECRET_HEADER);
    if (expected == null || expected.isBlank() || !constantTimeEquals(expected, received)) {
      throw new InvalidCredentialsException(
          "Secreto compartido del webhook ausente o inválido", null, Set.of());
    }
  }

  /**
   * Comparación en tiempo constante para no filtrar información del secreto a
   * través del tiempo de respuesta (timing attack).
   */
  private boolean constantTimeEquals(String expected, String received) {
    if (received == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        received.getBytes(StandardCharsets.UTF_8));
  }
}
