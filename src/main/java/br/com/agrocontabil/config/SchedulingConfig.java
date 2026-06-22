package br.com.agrocontabil.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita o agendamento de tarefas ({@code @Scheduled}) no contexto Spring.
 *
 * <p>Necessário para o job de sincronização de status de NF-e pendentes com a SEFAZ
 * ({@link br.com.agrocontabil.sefaz.SefazStatusSyncJob}, issue #16). Os jobs em si são
 * condicionados por propriedade, de modo que este {@code @EnableScheduling} é inócuo
 * quando nenhum job está ativo (ex.: perfil de testes).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
