package es.colorbaby.microservices.dev.relay.report;

import es.colorbaby.microservices.dev.relay.config.ReportStorageProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

/**
 * Informes sobre FTP (commons-net). Misma interfaz que la variante SFTP: se elige por
 * {@code maestro.reports.protocol} y el resto del código no se entera.
 */
@Slf4j
@RequiredArgsConstructor
public class FtpReportStorage implements ReportStorage {

  private final ReportStorageProperties properties;

  @Override
  public void ensureFolder(final String remoteDir) {
    execute("creando la carpeta " + remoteDir, client -> {
      makeDirectories(client, remoteDir);
      return null;
    });
  }

  @Override
  public void upload(final String remoteDir, final String fileName, final byte[] content) {
    execute("subiendo " + fileName + " a " + remoteDir, client -> {
      makeDirectories(client, remoteDir);
      try {
        client.storeFile(remoteDir + "/" + fileName, new ByteArrayInputStream(content));
      } catch (IOException e) {
        throw new ReportStorageException("No se pudo subir " + fileName, e);
      }
      return null;
    });
  }

  @Override
  public List<StoredFile> list(final String remoteDir) {
    return execute("listando " + remoteDir, client -> {
      final List<StoredFile> files = new ArrayList<>();
      try {
        for (final FTPFile file : client.listFiles(remoteDir)) {
          if (file != null && file.isFile()) {
            files.add(new StoredFile(file.getName(), file.getSize(),
                file.getTimestamp() == null ? 0L : file.getTimestamp().getTimeInMillis()));
          }
        }
      } catch (IOException e) {
        log.debug("No se pudo listar {}: {}", remoteDir, e.getMessage());
      }
      return files;
    });
  }

  @Override
  public byte[] download(final String remoteDir, final String fileName) {
    return execute("descargando " + fileName + " de " + remoteDir, client -> {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      try {
        client.retrieveFile(remoteDir + "/" + fileName, out);
      } catch (IOException e) {
        throw new ReportStorageException("No se pudo descargar " + fileName, e);
      }
      return out.toByteArray();
    });
  }

  /** Crea la ruta completa tramo a tramo; un tramo que ya exista no es un error. */
  private void makeDirectories(final FTPClient client, final String remoteDir) {
    final StringBuilder path = new StringBuilder();
    for (final String segment : remoteDir.split("/")) {
      if (segment.isBlank()) {
        continue;
      }
      path.append('/').append(segment);
      try {
        client.makeDirectory(path.toString());
      } catch (IOException e) {
        log.debug("mkdir {} — puede que ya exista: {}", path, e.getMessage());
      }
    }
  }

  private <T> T execute(final String what, final Function<FTPClient, T> action) {
    final FTPClient client = new FTPClient();
    client.setConnectTimeout(properties.getConnectionTimeout());
    client.setDataTimeout(java.time.Duration.ofMillis(properties.getDataTimeout()));
    try {
      client.connect(properties.getHost(), properties.getPort());
      if (!client.login(properties.getUsername(), properties.getPassword())) {
        throw new ReportStorageException("Login FTP rechazado para " + properties.getUsername(),
            null);
      }
      client.enterLocalPassiveMode();
      client.setFileType(FTP.BINARY_FILE_TYPE);
      return action.apply(client);
    } catch (IOException e) {
      throw new ReportStorageException("Error " + what, e);
    } finally {
      disconnect(client);
    }
  }

  private void disconnect(final FTPClient client) {
    try {
      if (client.isConnected()) {
        client.logout();
        client.disconnect();
      }
    } catch (IOException e) {
      log.debug("Error cerrando la conexión FTP: {}", e.getMessage());
    }
  }
}
