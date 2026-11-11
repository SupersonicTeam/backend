package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.fallback.TelaAtalho;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.util.List;

/**
 * Resposta final do orquestrador da Ceres (issues #157 e #146).
 *
 * @param rota          camada que respondeu
 * @param texto         texto já validado (números e citações conferidos pelas rotas)
 * @param desfecho      como a pergunta terminou (auditoria)
 * @param citacoes      trechos de lei citados
 * @param exibirFontes  {@code true} se as fontes de dados podem aparecer — só quando a parte de
 *                      dados foi aceita
 * @param modoFallback  {@code true} se alguma parte foi montada sem o modelo (#146)
 * @param atalhos       telas sugeridas no modo fallback
 */
public record ResultadoOrquestracao(
    Intencao rota, String texto, DesfechoInteracao desfecho, List<CitacaoLegal> citacoes,
    boolean exibirFontes, boolean modoFallback, List<TelaAtalho> atalhos,
    String perguntaConsiderada) {

  /** Cópias defensivas. */
  public ResultadoOrquestracao {
    citacoes = citacoes == null ? List.of() : List.copyOf(citacoes);
    atalhos = atalhos == null ? List.of() : List.copyOf(atalhos);
  }

  /** Resultado com o modelo (sem fallback). */
  public ResultadoOrquestracao(Intencao rota, String texto, DesfechoInteracao desfecho,
      List<CitacaoLegal> citacoes, boolean exibirFontes) {
    this(rota, texto, desfecho, citacoes, exibirFontes, false, List.of());
  }

  /**
   * Resultado sem pergunta considerada (sem continuidade).
   *
   * @param rota         rota
   * @param texto        texto
   * @param desfecho     desfecho
   * @param citacoes     citações
   * @param exibirFontes mostrar as fontes de dados
   * @param modoFallback sem o modelo
   * @param atalhos      telas sugeridas
   */
  public ResultadoOrquestracao(Intencao rota, String texto, DesfechoInteracao desfecho,
      List<CitacaoLegal> citacoes, boolean exibirFontes, boolean modoFallback,
      List<TelaAtalho> atalhos) {
    this(rota, texto, desfecho, citacoes, exibirFontes, modoFallback, atalhos, null);
  }

  /**
   * O mesmo resultado com a pergunta considerada pela continuidade (#499).
   *
   * @param pergunta pergunta considerada
   * @return o resultado
   */
  public ResultadoOrquestracao comPerguntaConsiderada(String pergunta) {
    return new ResultadoOrquestracao(rota, texto, desfecho, citacoes, exibirFontes, modoFallback,
        atalhos, pergunta);
  }

  /**
   * O mesmo resultado com uma abertura do backend antes do texto (#499, autocorreção).
   *
   * @param abertura abertura
   * @return o resultado
   */
  public ResultadoOrquestracao comAbertura(String abertura) {
    return new ResultadoOrquestracao(rota, abertura + "\n\n" + texto, desfecho, citacoes,
        exibirFontes, modoFallback, atalhos, perguntaConsiderada);
  }
}
