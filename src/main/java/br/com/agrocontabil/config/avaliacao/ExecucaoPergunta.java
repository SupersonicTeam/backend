package br.com.agrocontabil.config.avaliacao;

import br.com.agrocontabil.assistente.FonteDado;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.model.EstadoEmbeddingClassificacao;
import java.util.List;

/**
 * O que a Ceres fez com uma pergunta do gabarito (issue #158).
 *
 * @param rota         rota escolhida; nula se a pergunta falhou antes da classificação
 * @param texto        resposta entregue ao usuário; nula em falha
 * @param desfecho     desfecho da pergunta (o mesmo da auditoria)
 * @param citacoes     citações legais aceitas pelo backend
 * @param fontes       funções chamadas, com os parâmetros efetivamente usados
 * @param modoFallback {@code true} se alguma parte foi montada sem o modelo
 * @param latenciaMs   tempo da pergunta inteira, da classificação à resposta validada
 * @param erro         tipo da exceção, se a pergunta falhou (nunca a mensagem)
 * @param desfechoDados      no MISTO, desfecho da parte de dados (o mesmo da auditoria)
 * @param desfechoLegislacao no MISTO, desfecho da parte de lei
 * @param perguntaLeiReescrita no MISTO, se a parte de lei recebeu a pergunta legal separada pelo
 *                     classificador
 * @param camadaClassificacao camada que decidiu a rota (#265); nula se a pergunta acabou antes
 * @param embeddingClassificacao estado do embedding da camada de embeddings (#624): o rastro da
 *                     pausa; nulo se a camada não foi consultada
 */
public record ExecucaoPergunta(
    Intencao rota,
    String texto,
    DesfechoInteracao desfecho,
    List<CitacaoLegal> citacoes,
    List<FonteDado> fontes,
    boolean modoFallback,
    long latenciaMs,
    String erro,
    DesfechoInteracao desfechoDados,
    DesfechoInteracao desfechoLegislacao,
    Boolean perguntaLeiReescrita,
    String camadaClassificacao,
    EstadoEmbeddingClassificacao embeddingClassificacao) {

  /** Cópias defensivas. */
  public ExecucaoPergunta {
    citacoes = citacoes == null ? List.of() : List.copyOf(citacoes);
    fontes = fontes == null ? List.of() : List.copyOf(fontes);
  }

  /** Execução sem o estado do embedding da classificação (#624). */
  public ExecucaoPergunta(Intencao rota, String texto, DesfechoInteracao desfecho,
      List<CitacaoLegal> citacoes, List<FonteDado> fontes, boolean modoFallback, long latenciaMs,
      String erro, DesfechoInteracao desfechoDados, DesfechoInteracao desfechoLegislacao,
      Boolean perguntaLeiReescrita, String camadaClassificacao) {
    this(rota, texto, desfecho, citacoes, fontes, modoFallback, latenciaMs, erro, desfechoDados,
        desfechoLegislacao, perguntaLeiReescrita, camadaClassificacao, null);
  }

  /** Execução sem as partes do MISTO. */
  public ExecucaoPergunta(Intencao rota, String texto, DesfechoInteracao desfecho,
      List<CitacaoLegal> citacoes, List<FonteDado> fontes, boolean modoFallback, long latenciaMs,
      String erro) {
    this(rota, texto, desfecho, citacoes, fontes, modoFallback, latenciaMs, erro, null, null,
        null, null);
  }
}
