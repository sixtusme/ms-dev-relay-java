package es.colorbaby.microservices.dev.relay.ai.skill;

import java.util.List;

public interface Skill {
    String id();
    String name();
    String description();
    String instructions();
    List<String> requiredTools();
}
