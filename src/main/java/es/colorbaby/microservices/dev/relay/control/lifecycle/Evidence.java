package es.colorbaby.microservices.dev.relay.control.lifecycle;

/**
 * Una prueba de lo que ha pasado en una fase: qué es, el detalle legible, si es un artefacto
 * dónde verlo, si es un fallo con causa reconocida su {@link FailureReason} y, si se sabe, de
 * dónde viene ({@link EvidenceSource}).
 *
 * @param url        enlace al artefacto (PR, build, informe), o null
 * @param reasonCode causa reconocida del fallo, o null si no aplica o no se sabe todavía
 * @param source     qué sistema o quién la produjo, o null si no se clasificó
 */
public record Evidence(EvidenceKind kind, String detail, String url, FailureReason reasonCode,
    EvidenceSource source) {

  public Evidence(final EvidenceKind kind, final String detail, final String url) {
    this(kind, detail, url, null, null);
  }

  public Evidence(final EvidenceKind kind, final String detail, final String url,
      final EvidenceSource source) {
    this(kind, detail, url, null, source);
  }

  public static Evidence of(final EvidenceKind kind, final String detail) {
    return new Evidence(kind, detail, null, null, null);
  }

  public static Evidence of(final EvidenceKind kind, final String detail,
      final FailureReason reasonCode) {
    return new Evidence(kind, detail, null, reasonCode, null);
  }

  public static Evidence of(final EvidenceKind kind, final String detail,
      final EvidenceSource source) {
    return new Evidence(kind, detail, null, null, source);
  }

  public static Evidence of(final EvidenceKind kind, final String detail,
      final FailureReason reasonCode, final EvidenceSource source) {
    return new Evidence(kind, detail, null, reasonCode, source);
  }
}
