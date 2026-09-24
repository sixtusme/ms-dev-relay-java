package es.colorbaby.microservices.dev.relay.control.lifecycle;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Evidencias de las fases. */
public interface PhaseEvidenceRepository extends JpaRepository<PhaseEvidence, Long> {

  /** Evidencias de una pasada por una fase, en el orden en que llegaron. */
  List<PhaseEvidence> findByTaskPhaseIdOrderByIdAsc(Long taskPhaseId);
}
