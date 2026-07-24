package es.colorbaby.microservices.dev.relay.command;

import org.springframework.data.jpa.repository.JpaRepository;

/** Ajustes internos persistidos de sixai. */
public interface SixaiSettingRepository extends JpaRepository<SixaiSetting, String> {
}
