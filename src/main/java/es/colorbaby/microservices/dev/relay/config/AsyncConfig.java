package es.colorbaby.microservices.dev.relay.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor acotado para el procesamiento en background de las issues
 * detectadas. Permite que el webhook responda 200 de inmediato (el trabajo
 * pesado contra Jira se hace fuera del hilo de respuesta) y que el polling
 * procese issues en paralelo con una cota de recursos.
 */
@Configuration
public class AsyncConfig {

  public static final String JIRA_TASK_EXECUTOR = "jiraTaskExecutor";

  @Bean(JIRA_TASK_EXECUTOR)
  public Executor jiraTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(500);
    executor.setThreadNamePrefix("jira-proc-");
    // Si la cola se llena, el trabajo se ejecuta en el hilo llamante
    // (backpressure) en vez de descartarse.
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
  }
}
