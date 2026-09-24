package es.colorbaby.microservices.dev.relay.control.lifecycle;

/**
 * Respuesta del lifecycle a una consulta o a un resultado: si se permite y, si no, qué regla lo
 * impide y por qué, para poder contárselo a una persona en la propia tarea.
 *
 * @param rule regla incumplida (R1…R5, ORDER), o null si se permite
 */
public record Verdict(boolean allowed, String rule, String reason) {

  private static final Verdict ALLOWED = new Verdict(true, null, null);

  public static Verdict allow() {
    return ALLOWED;
  }

  public static Verdict deny(final String rule, final String reason) {
    return new Verdict(false, rule, reason);
  }

  public boolean denied() {
    return !allowed;
  }
}
