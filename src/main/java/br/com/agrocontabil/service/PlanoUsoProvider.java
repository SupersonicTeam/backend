package br.com.agrocontabil.service;

import br.com.agrocontabil.model.TipoTitularCota;
import java.util.UUID;

/**
 * De onde vem o limite semanal da Ceres de um titular (issue #166). Nesta etapa (A) é o plano
 * padrão por configuração ({@link PlanoUsoPorConfiguracao}); quando as assinaturas existirem
 * (épico SupersonicTeam/Agrocontabil#72, etapa B), outra implementação lê o plano do titular —
 * sem mexer na Ceres nem no mecanismo da cota.
 */
public interface PlanoUsoProvider {

  /**
   * Plano vigente do titular.
   *
   * @param tipo      produtor ou escritório
   * @param titularId id do titular
   * @return nome e créditos por semana
   */
  PlanoUso planoDe(TipoTitularCota tipo, UUID titularId);

  /**
   * Plano de uso da Ceres.
   *
   * @param nome              nome mostrado ao usuário
   * @param creditosPorSemana limite da semana (segunda a domingo, sem acúmulo)
   */
  record PlanoUso(String nome, long creditosPorSemana) {}
}
