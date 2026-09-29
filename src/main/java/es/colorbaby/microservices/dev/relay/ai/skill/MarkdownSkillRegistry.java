package es.colorbaby.microservices.dev.relay.ai.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Skills cargadas como ficheros Markdown con front-matter desde {@code classpath:ai/skills/*.md}
 * (ubicados en {@code src/main/resources/ai/skills/}). El front-matter declara metadatos; el resto
 * del fichero son las instrucciones que se le pasan al agente tal cual.
 *
 * <p>Formato esperado:
 * <pre>{@code
 * ---
 * id: command-routing
 * name: Enrutado de comandos /sixai
 * requiredTools: jira.get_comments
 * ---
 * Instrucciones en Markdown para el agente...
 * }</pre>
 */
@Slf4j
@Component
public class MarkdownSkillRegistry implements SkillRegistry {

  private static final String LOCATION_PATTERN = "classpath*:ai/skills/*.md";

  private final Map<String, Skill> skillsById;

  public MarkdownSkillRegistry() {
    this.skillsById = loadAll();
  }

  @Override
  public Optional<Skill> find(final String id) {
    return Optional.ofNullable(skillsById.get(id));
  }

  private Map<String, Skill> loadAll() {
    final Map<String, Skill> result = new LinkedHashMap<>();
    try {
      final Resource[] resources =
          new PathMatchingResourcePatternResolver().getResources(LOCATION_PATTERN);
      for (final Resource resource : resources) {
        final Skill skill = parse(resource);
        result.put(skill.id(), skill);
      }
    } catch (IOException e) {
      log.warn("No se pudieron cargar las skills desde {}", LOCATION_PATTERN, e);
    }
    return Map.copyOf(result);
  }

  private Skill parse(final Resource resource) throws IOException {
    final String raw = resource.getContentAsString(StandardCharsets.UTF_8);
    final String filename = resource.getFilename();

    if (!raw.startsWith("---")) {
      throw new IllegalStateException("Skill sin front-matter: " + filename);
    }
    final int end = raw.indexOf("\n---", 3);
    if (end < 0) {
      throw new IllegalStateException("Front-matter sin cerrar en: " + filename);
    }

    final Map<String, String> fields = new LinkedHashMap<>();
    for (final String line : raw.substring(3, end).strip().lines().toList()) {
      final int colon = line.indexOf(':');
      if (colon > 0) {
        fields.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
      }
    }
    final String instructions = raw.substring(end + 4).strip();

    final String id = fields.getOrDefault("id", stripExtension(filename));
    final String name = fields.getOrDefault("name", id);
    final String description = fields.getOrDefault("description", "");
    final String rawTools = fields.get("requiredTools");
    final List<String> requiredTools = rawTools == null || rawTools.isBlank()
        ? List.of()
        : Arrays.stream(rawTools.split(",")).map(String::strip).toList();

    return new MarkdownSkill(id, name, description, instructions, requiredTools);
  }

  private String stripExtension(final String filename) {
    if (filename == null) {
      return "unknown";
    }
    final int dot = filename.lastIndexOf('.');
    return dot > 0 ? filename.substring(0, dot) : filename;
  }

  private record MarkdownSkill(
      String id,
      String name,
      String description,
      String instructions,
      List<String> requiredTools
  ) implements Skill {
  }
}
