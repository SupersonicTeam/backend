package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.ResultadoRota;
import br.com.agrocontabil.assistente.RotaDados;
import br.com.agrocontabil.assistente.RotaLegislacao;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Orquestração da Ceres (issue #157): identifica a intenção e decide qual camada responde.
 *
 * <table>
 *   <caption>Rotas</caption>
 *   <tr><th>Intenção</th><th>Camada</th></tr>
 *   <tr><td>DADOS</td><td>{@link RotaDados}: modelo + só as funções de dados (#142)</td></tr>
 *   <tr><td>LEGISLACAO</td><td>{@link RotaLegislacao}: RAG com citação validada (#156)</td></tr>
 *   <tr><td>MISTO</td><td>as duas, cada uma com os próprios validadores</td></tr>
 *   <tr><td>FORA_DE_ESCOPO ou confiança baixa</td><td>resposta fixa, sem nova chamada ao modelo</td>
 *   </tr>
 * </table>
 *
 * <p>No MISTO, a parte de dados e a parte de lei são geradas e validadas separadamente (números
 * pelo {@code ValidadorNumeros} contra as funções; citações pelo {@link ValidadorCitacao} contra
 * os trechos) e depois juntadas. Uma resposta única gerada sobre as duas fontes misturaria números
 * das funções com números da lei, e nenhum dos validadores conseguiria dizer de onde cada um veio.
 */
@Component
public class OrquestradorService {

  private final ClassificadorIntencao classificador;
  private final RotaDados rotaDados;
  private final RotaLegislacao rotaLegislacao;
  private final double confiancaMinima;

  /** Constrói o orquestrador. */
  public OrquestradorService(ClassificadorIntencao classificador, RotaDados rotaDados,
      RotaLegislacao rotaLegislacao, AssistenteProperties propriedades) {
    this.classificador = classificador;
    this.rotaDados = rotaDados;
    this.rotaLegislacao = rotaLegislacao;
    this.confiancaMinima = propriedades.getOrquestracao().getConfiancaMinima();
  }

  /**
   * Classifica e responde.
   *
   * @param pergunta      pergunta do usuário
   * @param contexto      contexto da pergunta
   * @param aoDecidirRota avisado assim que a rota é decidida (evento {@code rota} do SSE)
   * @return resposta validada da rota escolhida
   */
  public ResultadoOrquestracao responder(
      String pergunta, ContextoAssistente contexto, Consumer<Intencao> aoDecidirRota) {
    ClassificacaoIntencao classificacao = classificador.classificar(pergunta, contexto);
    Intencao rota = classificacao.confianca() < confiancaMinima
        ? Intencao.FORA_DE_ESCOPO
        : classificacao.intencao();
    aoDecidirRota.accept(rota);
    contexto.verificarAtiva();

    return switch (rota) {
      case DADOS -> deRota(rota, rotaDados.responder(pergunta, contexto));
      case LEGISLACAO -> deRota(rota, rotaLegislacao.responder(pergunta, contexto));
      case MISTO -> combinar(
          rotaDados.responder(pergunta, contexto), rotaLegislacao.responder(pergunta, contexto));
      case FORA_DE_ESCOPO -> new ResultadoOrquestracao(Intencao.FORA_DE_ESCOPO,
          PromptsCeres.FORA_DE_ESCOPO, DesfechoInteracao.FORA_DE_ESCOPO, List.of(), false);
    };
  }

  /** Fontes de dados só na rota de dados, e só com a resposta aceita. */
  private static ResultadoOrquestracao deRota(Intencao rota, ResultadoRota resultado) {
    return new ResultadoOrquestracao(rota, resultado.texto(), resultado.desfecho(),
        resultado.citacoes(), rota == Intencao.DADOS && resultado.aceita());
  }

  /**
   * Junta as duas partes do MISTO. Parte descartada não entra com texto do modelo: no lugar vai a
   * mensagem fixa da rota. O desfecho é {@code RESPONDIDA} se ao menos uma parte foi aceita.
   */
  private static ResultadoOrquestracao combinar(ResultadoRota dados, ResultadoRota lei) {
    String parteLei = lei.aceita() ? lei.texto() : ValidadorCitacao.SEM_FUNDAMENTO;
    String texto = dados.aceita() || lei.aceita()
        ? dados.texto() + "\n\n" + parteLei
        : dados.texto();
    DesfechoInteracao desfecho = dados.aceita() || lei.aceita()
        ? DesfechoInteracao.RESPONDIDA
        : dados.desfecho();
    return new ResultadoOrquestracao(
        Intencao.MISTO, texto, desfecho, lei.citacoes(), dados.aceita());
  }
}
