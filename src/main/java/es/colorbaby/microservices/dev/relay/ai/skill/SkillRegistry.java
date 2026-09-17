package es.colorbaby.microservices.dev.relay.ai.skill;

import java.util.List;
import java.util.Optional;

public interface SkillRegistry {

    Optional<Skill> find(String id);

    List<Skill> findAll();

    List<Skill> findByIds(List<String> ids);
}