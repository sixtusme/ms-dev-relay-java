package es.colorbaby.microservices.dev.relay.ai.orchestration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Una ejecución de agente parada en {@code NEEDS_INPUT}, a la espera de que una persona conteste
 * en la propia tarea de Jira. Guarda justo lo que hace falta para reconstruir el
 * {@code AgentContext} tal cual estaba (mensajes y estado, serializados como JSON) y retomar el
 * mismo bucle LLM→tool→LLM — nunca reiniciar al agente desde cero (mejoras-senior §8.2).
 *
 * <p>{@code taskTitle}/{@code taskDescription} son el mismo resumen y descripción (con la
 * corrección ya anexada, si la había) con los que se llamó al agente: sirven tal cual para
 * reconstruir el título/cuerpo de la PR al terminar, sin tener que volver a leer Jira.
 */
@Entity
@Table(name = "agent_suspension")
@Getter
@NoArgsConstructor
public class AgentSuspension {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "execution_id", nullable = false)
  private String executionId;

  @Column(name = "agent_id", nullable = false)
  private String agentId;

  @Column(name = "issue_key", nullable = false)
  private String issueKey;

  @Column(nullable = false)
  private String repo;

  @Column(name = "task_title")
  private String taskTitle;

  @Column(name = "task_description", columnDefinition = "MEDIUMTEXT")
  private String taskDescription;

  @Column(nullable = false, length = 2000)
  private String question;

  /** Mensajes acumulados del {@code AgentContext}, serializados como JSON. */
  @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
  private String messages;

  /** Estado (repo, branch, plan…) del {@code AgentContext}, serializado como JSON. */
  @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
  private String state;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  public AgentSuspension(final String executionId, final String agentId, final String issueKey,
      final String repo, final String taskTitle, final String taskDescription,
      final String question, final String messages, final String state) {
    this.executionId = executionId;
    this.agentId = agentId;
    this.issueKey = issueKey;
    this.repo = repo;
    this.taskTitle = taskTitle;
    this.taskDescription = taskDescription;
    this.question = question.length() <= 2000 ? question : question.substring(0, 2000);
    this.messages = messages;
    this.state = state;
  }
}
