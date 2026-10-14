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
    boolean exibirFontes, boolean modoFallback, List<TelaAtalho> atalhos) {

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
}
