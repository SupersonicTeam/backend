package br.com.agrocontabil.assistente.porta;

import br.com.agrocontabil.model.BuscaReconferenciaLei;
import br.com.agrocontabil.model.DesfechoExplicacao;
import br.com.agrocontabil.model.DesfechoFidelidadeLei;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.model.EstadoEmbeddingClassificacao;
import br.com.agrocontabil.model.MotivoReconferenciaLei;
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
   * @return id do registro, para completá-lo com a explicação (#268)
   * @throws RuntimeException se não gravar — quem chama decide que a resposta não é entregue
   */
  UUID registrar(RegistroInteracao registro);

  /**
   * Completa o registro da pergunta com o desfecho da explicação depois da resposta de dados
   * (#268), numa transação curta e própria. Uma vez só por registro.
   *
   * @param registro desfecho e métricas da explicação, sem o texto
   * @throws RuntimeException se não gravar — quem chama não entrega a explicação
   */
  void registrarExplicacao(RegistroExplicacao registro);

  /**
   * Uma interação com a Ceres, sem conteúdo.
   *
   * @param produtorId       quem perguntou
   * @param rota             camada que respondeu (ou {@code INDEFINIDA})
   * @param desfecho         como terminou
   * @param desfechoDados    desfecho da parte de dados do MISTO; nulo nas outras rotas
   * @param desfechoLegislacao desfecho da parte de lei do MISTO; nulo nas outras rotas
   * @param perguntaLeiReescrita a parte de lei do MISTO recebeu a pergunta legal separada pelo
   *                         classificador (só o fato, nunca o texto); nulo nas outras rotas
   * @param camadaClassificacao camada que decidiu a rota (#265); nula se a pergunta acabou antes
   * @param funcoesJson      funções usadas e parâmetros, mascarados (JSON)
   * @param chamadasFuncao   funções executadas (pedidas pelo modelo ou, no atalho fixo de dados,
   *                         chamadas pelo backend, #343), inclusive as que falharam
   * @param trechosCitadosJson ids dos trechos de lei citados (JSON) ou nulo
   * @param modelo           {@code provedor/modelo}
   * @param latenciaMs       duração total
   * @param tokensEntrada    tokens de entrada, se o provedor informou
   * @param tokensSaida      tokens de saída, se o provedor informou
   * @param tokensEstimados  {@code true} se o provedor não informou e os tokens foram estimados
   *                         por caracteres (issue #166)
   * @param tamanhoPergunta  tamanho da pergunta em caracteres (métrica, não conteúdo)
   * @param criadoEm         instante do registro
   * @param fidelidadeLei    desfecho da conferência de fidelidade da resposta de lei (#269); nulo
   *                         quando ela não rodou (flag desligada, outra rota, sem trecho...)
   * @param latenciaJuizMs   duração da chamada do juiz da fidelidade (#269, camada 2); nula se ele
   *                         não rodou. {@code latenciaMs} é a da pergunta sem ela
   * @param tokensEntradaJuiz tokens de entrada do juiz; nulo se ele não rodou. {@code tokensEntrada}
   *                         é o da pergunta sem ele
   * @param tokensSaidaJuiz  tokens de saída do juiz; nulo se ele não rodou
   * @param reconferenciaLei por que a reconferência de lei da continuidade terminou como terminou e
   *                         o que foi ao prompt dela (#584), só códigos e contagens; nulo fora dela
   */
  record RegistroInteracao(
      UUID produtorId, String rota, DesfechoInteracao desfecho,
      DesfechoInteracao desfechoDados, DesfechoInteracao desfechoLegislacao,
      Boolean perguntaLeiReescrita, String camadaClassificacao, String funcoesJson,
      int chamadasFuncao, String trechosCitadosJson, String modelo, long latenciaMs,
      Integer tokensEntrada, Integer tokensSaida, boolean tokensEstimados, int tamanhoPergunta,
      OffsetDateTime criadoEm, DesfechoFidelidadeLei fidelidadeLei, Long latenciaJuizMs,
      Integer tokensEntradaJuiz, Integer tokensSaidaJuiz,
      RegistroReconferenciaLei reconferenciaLei,
      EstadoEmbeddingClassificacao embeddingClassificacao,
      Integer latenciaEmbeddingClassificacaoMs) {

    /** Registro sem o embedding da classificação (#624): a forma de antes da instrumentação. */
    public RegistroInteracao(
        UUID produtorId, String rota, DesfechoInteracao desfecho,
        DesfechoInteracao desfechoDados, DesfechoInteracao desfechoLegislacao,
        Boolean perguntaLeiReescrita, String camadaClassificacao, String funcoesJson,
        int chamadasFuncao, String trechosCitadosJson, String modelo, long latenciaMs,
        Integer tokensEntrada, Integer tokensSaida, boolean tokensEstimados, int tamanhoPergunta,
        OffsetDateTime criadoEm, DesfechoFidelidadeLei fidelidadeLei, Long latenciaJuizMs,
        Integer tokensEntradaJuiz, Integer tokensSaidaJuiz,
        RegistroReconferenciaLei reconferenciaLei) {
      this(produtorId, rota, desfecho, desfechoDados, desfechoLegislacao, perguntaLeiReescrita,
          camadaClassificacao, funcoesJson, chamadasFuncao, trechosCitadosJson, modelo, latenciaMs,
          tokensEntrada, tokensSaida, tokensEstimados, tamanhoPergunta, criadoEm, fidelidadeLei,
          latenciaJuizMs, tokensEntradaJuiz, tokensSaidaJuiz, reconferenciaLei, null, null);
    }

    /** Registro sem a reconferência de lei (#584): a forma de antes da instrumentação. */
    public RegistroInteracao(
        UUID produtorId, String rota, DesfechoInteracao desfecho,
        DesfechoInteracao desfechoDados, DesfechoInteracao desfechoLegislacao,
        Boolean perguntaLeiReescrita, String camadaClassificacao, String funcoesJson,
        int chamadasFuncao, String trechosCitadosJson, String modelo, long latenciaMs,
        Integer tokensEntrada, Integer tokensSaida, boolean tokensEstimados, int tamanhoPergunta,
        OffsetDateTime criadoEm, DesfechoFidelidadeLei fidelidadeLei, Long latenciaJuizMs,
        Integer tokensEntradaJuiz, Integer tokensSaidaJuiz) {
      this(produtorId, rota, desfecho, desfechoDados, desfechoLegislacao, perguntaLeiReescrita,
          camadaClassificacao, funcoesJson, chamadasFuncao, trechosCitadosJson, modelo, latenciaMs,
          tokensEntrada, tokensSaida, tokensEstimados, tamanhoPergunta, criadoEm, fidelidadeLei,
          latenciaJuizMs, tokensEntradaJuiz, tokensSaidaJuiz, null);
    }

    /** Registro sem a conferência de fidelidade (#269): a forma de antes da flag. */
    public RegistroInteracao(
        UUID produtorId, String rota, DesfechoInteracao desfecho,
        DesfechoInteracao desfechoDados, DesfechoInteracao desfechoLegislacao,
        Boolean perguntaLeiReescrita, String camadaClassificacao, String funcoesJson,
        int chamadasFuncao, String trechosCitadosJson, String modelo, long latenciaMs,
        Integer tokensEntrada, Integer tokensSaida, boolean tokensEstimados, int tamanhoPergunta,
        OffsetDateTime criadoEm) {
      this(produtorId, rota, desfecho, desfechoDados, desfechoLegislacao, perguntaLeiReescrita,
          camadaClassificacao, funcoesJson, chamadasFuncao, trechosCitadosJson, modelo, latenciaMs,
          tokensEntrada, tokensSaida, tokensEstimados, tamanhoPergunta, criadoEm, null, null, null,
          null, null);
    }
  }

  /**
   * Desfecho da explicação de uma pergunta (#268), sem o texto dela.
   *
   * @param interacaoId   registro da pergunta ({@link #registrar})
   * @param desfecho      aceita ou o motivo do descarte
   * @param latenciaMs    tempo da explicação
   * @param tokensEntrada tokens de entrada da chamada; nulo se ela não terminou
   * @param tokensSaida   tokens de saída da chamada; nulo se ela não terminou
   */
  record RegistroExplicacao(UUID interacaoId, DesfechoExplicacao desfecho, long latenciaMs,
      Integer tokensEntrada, Integer tokensSaida) {}

  /**
   * O que a reconferência de lei da continuidade (#499) registra para o diagnóstico (#584): o
   * motivo do desfecho e o que foi ao prompt. Só códigos, contagens e marcas: nenhum texto de
   * pergunta, de resposta, de lei ou do modelo (#144). Cada campo nulo é "não chegou a esse passo".
   *
   * @param motivo                 por que terminou como terminou
   * @param dispositivos           dispositivos que a resposta anterior citava (lei.r ou lei.c)
   * @param trechosCitadosAchados  desses, quantos trechos vigentes a base textual achou
   * @param busca                  resultado da busca vetorial; nulo se não chegou a ela
   * @param trechosBusca           trechos que a busca vetorial devolveu (acima do limiar)
   * @param trechosCompras         trechos dos itens de anexo das compras (só no MISTO)
   * @param trechosPrompt          trechos que foram ao prompt, depois do teto de 8 e de 24.000
   *                               caracteres
   * @param trechosPromptCitados   desses, os que a resposta anterior citava
   * @param caracteresTrechos      caracteres de texto de lei no prompt
   * @param tokensEntradaConcordancia tokens de entrada que a concordância (a rota de lei normal, a
   *                               guarda final do CORRIJO) gastou, já somados aos da pergunta; com
   *                               o juiz da fidelidade ligado, os do juiz também estão aqui, e
   *                               nulo se ela não rodou ou falhou (a estimativa de uma chamada que
   *                               falhou não é gasto)
   * @param desfechoConcordancia   desfecho da rota de lei normal da concordância; nulo se não rodou
   */
  record RegistroReconferenciaLei(
      MotivoReconferenciaLei motivo, Integer dispositivos, Integer trechosCitadosAchados,
      BuscaReconferenciaLei busca, Integer trechosBusca, Integer trechosCompras,
      Integer trechosPrompt, Integer trechosPromptCitados, Integer caracteresTrechos,
      Integer tokensEntradaConcordancia, DesfechoInteracao desfechoConcordancia) {

    /** Registro vazio: ainda sem nenhum passo. */
    public static RegistroReconferenciaLei vazio() {
      return new RegistroReconferenciaLei(null, null, null, null, null, null, null, null, null,
          null, null);
    }

    /** O motivo do desfecho. */
    public RegistroReconferenciaLei comMotivo(MotivoReconferenciaLei novo) {
      return new RegistroReconferenciaLei(novo, dispositivos, trechosCitadosAchados, busca,
          trechosBusca, trechosCompras, trechosPrompt, trechosPromptCitados, caracteresTrechos,
          tokensEntradaConcordancia, desfechoConcordancia);
    }

    /** Os dispositivos citados e os trechos que a busca textual achou para eles. */
    public RegistroReconferenciaLei comCitados(int novosDispositivos, int achados) {
      return new RegistroReconferenciaLei(motivo, novosDispositivos, achados, busca, trechosBusca,
          trechosCompras, trechosPrompt, trechosPromptCitados, caracteresTrechos,
          tokensEntradaConcordancia, desfechoConcordancia);
    }

    /** O resultado da busca vetorial e, se ela respondeu, quantos trechos e os das compras. */
    public RegistroReconferenciaLei comBusca(BuscaReconferenciaLei novaBusca, Integer daBusca,
        Integer dasCompras) {
      return new RegistroReconferenciaLei(motivo, dispositivos, trechosCitadosAchados, novaBusca,
          daBusca, dasCompras, trechosPrompt, trechosPromptCitados, caracteresTrechos,
          tokensEntradaConcordancia, desfechoConcordancia);
    }

    /** O que foi ao prompt. */
    public RegistroReconferenciaLei comPrompt(int trechos, int citados, int caracteres) {
      return new RegistroReconferenciaLei(motivo, dispositivos, trechosCitadosAchados, busca,
          trechosBusca, trechosCompras, trechos, citados, caracteres, tokensEntradaConcordancia,
          desfechoConcordancia);
    }

    /** A concordância (a segunda chamada ao modelo do CORRIJO): tokens e desfecho da rota. */
    public RegistroReconferenciaLei comConcordancia(Integer tokens, DesfechoInteracao desfecho) {
      return new RegistroReconferenciaLei(motivo, dispositivos, trechosCitadosAchados, busca,
          trechosBusca, trechosCompras, trechosPrompt, trechosPromptCitados, caracteresTrechos,
          tokens, desfecho);
    }
  }
}
