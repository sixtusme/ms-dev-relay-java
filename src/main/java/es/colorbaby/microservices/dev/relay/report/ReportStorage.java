package es.colorbaby.microservices.dev.relay.report;

import java.util.List;

/**
 * Almacén remoto de los informes y recursos de cada tarea. Hay dos implementaciones (SFTP y FTP) y
 * se elige por configuración, igual que en {@code ms-aduana-java}: el resto del código no sabe cuál
 * está usando.
 */
public interface ReportStorage {

  /** Un fichero guardado, con lo justo para listarlo en el panel. */
  record StoredFile(String name, long size, long lastModifiedMillis) {
  }

  /**
   * Crea la carpeta remota (y las intermedias) si no existe. Idempotente.
   *
   * @param remoteDir ruta absoluta de la carpeta
   */
  void ensureFolder(String remoteDir);

  /**
   * Sube un fichero, sobreescribiendo si ya estaba.
   *
   * @param remoteDir carpeta destino
   * @param fileName  nombre del fichero
   * @param content   contenido
   */
  void upload(String remoteDir, String fileName, byte[] content);

  /**
   * Ficheros de una carpeta (sin subcarpetas). Vacío si la carpeta no existe.
   *
   * @param remoteDir carpeta a listar
   * @return ficheros con nombre, tamaño y fecha
   */
  List<StoredFile> list(String remoteDir);

  /**
   * Descarga un fichero.
   *
   * @param remoteDir carpeta que lo contiene
   * @param fileName  nombre del fichero
   * @return contenido
   */
  byte[] download(String remoteDir, String fileName);
}
