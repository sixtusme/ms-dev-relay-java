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
    // OJO: un ThreadPoolExecutor solo crea hilos por encima del core CUANDO LA COLA SE LLENA. Con
    // una cola de 500 nunca se llenaba, así que el maxPoolSize era papel mojado y solo trabajaban
    // 2 hilos. Con la cola corta, una ráfaga de tareas sí escala hasta 8 (que es lo que se quería).
    executor.setQueueCapacity(10);
    executor.setThreadNamePrefix("jira-proc-");
    // Si la cola se llena, el trabajo se ejecuta en el hilo llamante
    // (backpressure) en vez de descartarse.
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
  }
}
