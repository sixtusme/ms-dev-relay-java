package es.colorbaby.microservices.dev.relay.config;

import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * El planner de sixai ({@code maestro.planner}): antes de que el coder escriba nada, entiende la
 * tarea, inspecciona el árbol del repo y decide un plan de implementación (qué tocar y cómo).
 * Requiere además {@code maestro.llm.enabled=true}. Solo usa tools de lectura — nunca escribe.
 *
 * <p>Con {@code false}, el coder sigue funcionando exactamente igual que antes de que existiera el
 * planner (decide por su cuenta qué leer y cómo implementar, sin plan previo).
 */
@Data
@Validated
@ConfigurationProperties(prefix = "maestro.planner")
public class PlannerProperties {

  private boolean enabled = false;

  /** Máximo de rutas del árbol del repo que se le pasan al modelo. */
  @Positive
  private int treeMaxEntries = 300;
}
