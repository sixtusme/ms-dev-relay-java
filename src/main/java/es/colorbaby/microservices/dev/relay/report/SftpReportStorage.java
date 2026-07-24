package es.colorbaby.microservices.dev.relay.report;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import es.colorbaby.microservices.dev.relay.config.ReportStorageProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Informes sobre SFTP (jsch). Abre y cierra conexión por operación: sixai escribe unos pocos
 * ficheros por tarea, así que un pool añadiría complejidad sin ganancia real.
 */
@Slf4j
@RequiredArgsConstructor
public class SftpReportStorage implements ReportStorage {

  private final ReportStorageProperties properties;

  @Override
  public void ensureFolder(final String remoteDir) {
    execute("creando la carpeta " + remoteDir, channel -> {
      makeDirectories(channel, remoteDir);
      return null;
    });
  }

  @Override
  public void upload(final String remoteDir, final String fileName, final byte[] content) {
    execute("subiendo " + fileName + " a " + remoteDir, channel -> {
      makeDirectories(channel, remoteDir);
      try {
        channel.put(new ByteArrayInputStream(content), remoteDir + "/" + fileName,
            ChannelSftp.OVERWRITE);
      } catch (SftpException e) {
        throw new ReportStorageException("No se pudo subir " + fileName, e);
      }
      return null;
    });
  }

  @Override
  public List<StoredFile> list(final String remoteDir) {
    return execute("listando " + remoteDir, channel -> {
      final List<StoredFile> files = new ArrayList<>();
      try {
        for (final Object entry : channel.ls(remoteDir)) {
          if (entry instanceof ChannelSftp.LsEntry item && !item.getAttrs().isDir()) {
            files.add(new StoredFile(item.getFilename(), item.getAttrs().getSize(),
                item.getAttrs().getMTime() * 1000L));
          }
        }
      } catch (SftpException e) {
        // Carpeta inexistente: para el panel es simplemente "todavía no hay nada".
        log.debug("No se pudo listar {}: {}", remoteDir, e.getMessage());
      }
      return files;
    });
  }

  @Override
  public byte[] download(final String remoteDir, final String fileName) {
    return execute("descargando " + fileName + " de " + remoteDir, channel -> {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      try {
        channel.get(remoteDir + "/" + fileName, out);
      } catch (SftpException e) {
        throw new ReportStorageException("No se pudo descargar " + fileName, e);
      }
      return out.toByteArray();
    });
  }

  /** Crea la ruta completa tramo a tramo; un tramo que ya exista no es un error. */
  private void makeDirectories(final ChannelSftp channel, final String remoteDir) {
    final StringBuilder path = new StringBuilder();
    for (final String segment : remoteDir.split("/")) {
      if (segment.isBlank()) {
        continue;
      }
      path.append('/').append(segment);
      final String current = path.toString();
      try {
        channel.stat(current);
      } catch (SftpException notThere) {
        try {
          channel.mkdir(current);
        } catch (SftpException e) {
          log.debug("mkdir {} — puede que ya exista: {}", current, e.getMessage());
        }
      }
    }
  }

  private <T> T execute(final String what, final Function<ChannelSftp, T> action) {
    Session session = null;
    ChannelSftp channel = null;
    try {
      session = openSession();
      channel = (ChannelSftp) session.openChannel("sftp");
      channel.connect(properties.getConnectionTimeout());
      return action.apply(channel);
    } catch (JSchException e) {
      throw new ReportStorageException("Error " + what, e);
    } finally {
      if (channel != null) {
        channel.disconnect();
      }
      if (session != null) {
        session.disconnect();
      }
    }
  }

  private Session openSession() throws JSchException {
    final JSch jsch = new JSch();
    if (properties.getPrivateKeyPath() != null && !properties.getPrivateKeyPath().isBlank()) {
      jsch.addIdentity(properties.getPrivateKeyPath());
    }
    if (properties.getKnownHostsPath() != null && !properties.getKnownHostsPath().isBlank()) {
      jsch.setKnownHosts(properties.getKnownHostsPath());
    }
    final Session session = jsch.getSession(properties.getUsername(), properties.getHost(),
        properties.getPort());
    if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
      session.setPassword(properties.getPassword());
    }
    final Properties config = new Properties();
    config.put("StrictHostKeyChecking", properties.isStrictHostChecking() ? "yes" : "no");
    session.setConfig(config);
    session.connect(properties.getConnectionTimeout());
    return session;
  }
}
