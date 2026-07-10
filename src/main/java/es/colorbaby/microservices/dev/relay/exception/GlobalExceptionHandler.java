package es.colorbaby.microservices.dev.relay.exception;

import es.colorbaby.microservices.dev.relay.openapi.model.ErrorResponseDto;
import es.colorbaby.microservices.essential.common.exceptions.ColorbabyException;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * Manejo de errores del endpoint de webhook de Jira: secreto compartido
 * inválido (401) o payload no parseable (400), en el formato ErrorResponseDto
 * del contrato.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ColorbabyException.class)
  public ResponseEntity<ErrorResponseDto> handleColorbabyException(ColorbabyException e,
      WebRequest request) {
    log.warn("Error de dominio: {}", e.getMessage());
    return ResponseEntity.status(e.getHttpStatus()).body(toErrorResponse(e.getHttpStatus(),
        e.getCode(), e.getMessage(), request));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponseDto> handleIllegalArgument(IllegalArgumentException e,
      WebRequest request) {
    log.warn("Payload inválido: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(toErrorResponse(
        HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage(), request));
  }

  private ErrorResponseDto toErrorResponse(HttpStatus status, String error, String message,
      WebRequest request) {
    ErrorResponseDto body = new ErrorResponseDto();
    body.setStatus(status.value());
    body.setError(error);
    body.setMessage(message);
    body.setPath(request.getDescription(false).replace("uri=", ""));
    body.setTimestamp(OffsetDateTime.now());
    return body;
  }
}
