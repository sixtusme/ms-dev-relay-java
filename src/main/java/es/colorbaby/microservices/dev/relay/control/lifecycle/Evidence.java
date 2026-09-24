package es.colorbaby.microservices.dev.relay.control.lifecycle;

/**
 * Una prueba de lo que ha pasado en una fase: qué es, el detalle legible y, si es un artefacto,
 * dónde verlo.
 *
 * @param url enlace al artefacto (PR, build, informe), o null
 */
public record Evidence(EvidenceKind kind, String detail, String url) {

  public static Evidence of(final EvidenceKind kind, final String detail) {
    return new Evidence(kind, detail, null);
  }
}
