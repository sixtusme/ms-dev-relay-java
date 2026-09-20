package es.colorbaby.microservices.dev.relay.ai.skill;

import java.util.Optional;

public interface SkillRegistry {

    Optional<Skill> find(String id);
}