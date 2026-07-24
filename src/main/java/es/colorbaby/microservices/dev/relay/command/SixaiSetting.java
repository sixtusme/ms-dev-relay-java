package es.colorbaby.microservices.dev.relay.command;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Ajuste interno persistido de sixai (clave/valor). */
@Entity
@Table(name = "sixai_setting")
@Getter
@Setter
@NoArgsConstructor
public class SixaiSetting {

  /** Instante desde el que se atienden comandos; se fija en el primer arranque. */
  public static final String COMMANDS_SINCE = "commands_since";

  @Id
  private String name;

  @Column(nullable = false)
  private String value;

  public SixaiSetting(final String name, final String value) {
    this.name = name;
    this.value = value;
  }
}
