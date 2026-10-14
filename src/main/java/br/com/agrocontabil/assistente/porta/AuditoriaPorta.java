package br.com.agrocontabil.assistente.porta;

import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Porta da auditoria das perguntas à Ceres (issues #141 e #144).
 *
 * <p>O registro chega pronto e já sanitizado: o módulo decide o que auditar, a implementação só
 * grava. <strong>Não há campo para o texto da pergunta nem da resposta</strong>, e não deve haver.
 */
public interface AuditoriaPorta {

  /**
   * Grava o registro numa transação curta e própria.
   *
   * @param registro interação a registrar
   * @throws RuntimeException se não gravar — quem chama decide que a resposta não é entregue
   */
  void registrar(RegistroInteracao registro);

  /**
   * Uma interação com a Ceres, sem conteúdo.
   *
   * @param produtorId       quem perguntou
   * @param rota             camada que respondeu (ou {@code INDEFINIDA})
   * @param desfecho         como terminou
   * @param funcoesJson      funções usadas e parâmetros, mascarados (JSON)
   * @param chamadasFuncao   funções pedidas pelo modelo, inclusive as que falharam
   * @param trechosCitadosJson ids dos trechos de lei citados (JSON) ou nulo
   * @param modelo           {@code provedor/modelo}
   * @param latenciaMs       duração total
   * @param tokensEntrada    tokens de entrada, se o provedor informou
   * @param tokensSaida      tokens de saída, se o provedor informou
   * @param tokensEstimados  {@code true} se o provedor não informou e os tokens foram estimados
   *                         por caracteres (issue #166)
   * @param tamanhoPergunta  tamanho da pergunta em caracteres (métrica, não conteúdo)
   * @param criadoEm         instante do registro
   */
  record RegistroInteracao(
      UUID produtorId, String rota, DesfechoInteracao desfecho, String funcoesJson,
      int chamadasFuncao, String trechosCitadosJson, String modelo, long latenciaMs,
      Integer tokensEntrada, Integer tokensSaida, boolean tokensEstimados, int tamanhoPergunta,
      OffsetDateTime criadoEm) {}
}
