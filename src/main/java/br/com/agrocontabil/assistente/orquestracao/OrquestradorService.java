package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.ResultadoRota;
import br.com.agrocontabil.assistente.RotaDados;
import br.com.agrocontabil.assistente.RotaLegislacao;
import br.com.agrocontabil.assistente.fallback.ModoFallback;
import br.com.agrocontabil.assistente.fallback.TelaAtalho;
import br.com.agrocontabil.assistente.llm.DisponibilidadeModelo;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p><strong>Modo fallback (issue #146), parte por parte:</strong> com o disjuntor do modelo
 * aberto ({@link DisponibilidadeModelo}) nenhuma chamada ao modelo é feita; com o modelo falhando
 * no meio, só a parte que falhou cai no {@link ModoFallback} — num MISTO em que os dados já foram
 * respondidos, a lei vem da busca textual e os dados ficam. Sem o classificador, a intenção sai de
 * palavras-chave. Interrupção (tempo esgotado, cliente que saiu) nunca vira fallback: a pergunta
 * acabou.
 */
@Component
public class OrquestradorService {

  private static final Logger log = LoggerFactory.getLogger(OrquestradorService.class);

  private final ClassificadorIntencao classificador;
  private final RotaDados rotaDados;
  private final RotaLegislacao rotaLegislacao;
  private final ModoFallback fallback;
  private final DisponibilidadeModelo disponibilidade;
  private final double confiancaMinima;

  /** Constrói o orquestrador. */
  public OrquestradorService(ClassificadorIntencao classificador, RotaDados rotaDados,
      RotaLegislacao rotaLegislacao, ModoFallback fallback,
      DisponibilidadeModelo disponibilidade, AssistenteProperties propriedades) {
    this.classificador = classificador;
    this.rotaDados = rotaDados;
    this.rotaLegislacao = rotaLegislacao;
    this.fallback = fallback;
    this.disponibilidade = disponibilidade;
    this.confiancaMinima = propriedades.getOrquestracao().getConfiancaMinima();
  }

  /**
   * Classifica e responde.
   *
   * @param pergunta      pergunta do usuário
   * @param contexto      contexto da pergunta
   * @param aoDecidirRota avisado assim que a rota é decidida (evento {@code rota} do SSE)
   * @return resposta validada da rota escolhida
   * @throws AssistenteIndisponivelException se o modelo e o fallback falharem (base fora)
   */
  public ResultadoOrquestracao responder(
      String pergunta, ContextoAssistente contexto, Consumer<Intencao> aoDecidirRota) {
    Intencao rota = classificar(pergunta, contexto);
    aoDecidirRota.accept(rota);
    contexto.verificarAtiva();

    return switch (rota) {
      case DADOS -> deRota(rota, dados(pergunta, contexto));
      case LEGISLACAO -> deRota(rota, lei(pergunta, contexto));
      case MISTO -> combinar(dados(pergunta, contexto), lei(pergunta, contexto));
      case FORA_DE_ESCOPO -> new ResultadoOrquestracao(Intencao.FORA_DE_ESCOPO,
          PromptsCeres.FORA_DE_ESCOPO, DesfechoInteracao.FORA_DE_ESCOPO, List.of(), false);
    };
  }

  private Intencao classificar(String pergunta, ContextoAssistente contexto) {
    if (disponibilidade.disponivel()) {
      try {
        ClassificacaoIntencao classificacao = classificador.classificar(pergunta, contexto);
        return classificacao.confianca() < confiancaMinima
            ? Intencao.FORA_DE_ESCOPO
            : classificacao.intencao();
      } catch (AssistenteIndisponivelException e) {
        semModelo(contexto, "classificacao");
      }
    }
    return fallback.classificar(pergunta);
  }

  private ResultadoRota dados(String pergunta, ContextoAssistente contexto) {
    if (disponibilidade.disponivel()) {
      try {
        return rotaDados.responder(pergunta, contexto);
      } catch (AssistenteIndisponivelException e) {
        semModelo(contexto, "dados");
      }
    }
    return fallback.parteDados(pergunta);
  }

  private ResultadoRota lei(String pergunta, ContextoAssistente contexto) {
    if (disponibilidade.disponivel()) {
      try {
        return rotaLegislacao.responder(pergunta, contexto);
      } catch (AssistenteIndisponivelException e) {
        // Tambem quando so o embedding caiu: a busca textual nao depende do Ollama.
        semModelo(contexto, "legislacao");
      }
    }
    return fallback.parteLei(pergunta, contexto);
  }

  /** Falha durante uma pergunta interrompida não é indisponibilidade: a pergunta acabou. */
  private static void semModelo(ContextoAssistente contexto, String etapa) {
    contexto.verificarAtiva();
    log.info("Ceres em modo fallback: modelo indisponivel na etapa {}", etapa);
  }

  /** Fontes de dados só na rota de dados, e só com a resposta aceita. */
  private static ResultadoOrquestracao deRota(Intencao rota, ResultadoRota resultado) {
    return new ResultadoOrquestracao(rota, resultado.texto(), resultado.desfecho(),
        resultado.citacoes(), rota == Intencao.DADOS && resultado.aceita(),
        resultado.emFallback(), resultado.atalhos());
  }

  /**
   * Junta as duas partes do MISTO. Parte descartada não entra com texto do modelo: no lugar vai a
   * mensagem fixa da rota. Parte em fallback entra com o texto dela (trechos verbatim ou atalhos).
   * O desfecho é {@code MODO_FALLBACK} se alguma parte veio sem modelo; senão {@code RESPONDIDA}
   * se ao menos uma parte foi aceita.
   */
  private static ResultadoOrquestracao combinar(ResultadoRota dados, ResultadoRota lei) {
    boolean dadosUtil = dados.aceita() || dados.emFallback();
    boolean leiUtil = lei.aceita() || lei.emFallback();
    String parteLei = leiUtil ? lei.texto() : ValidadorCitacao.SEM_FUNDAMENTO;
    String texto = dadosUtil || leiUtil
        ? dados.texto() + "\n\n" + parteLei
        : dados.texto();
    boolean emFallback = dados.emFallback() || lei.emFallback();
    DesfechoInteracao desfecho;
    if (emFallback) {
      desfecho = DesfechoInteracao.MODO_FALLBACK;
    } else if (dados.aceita() || lei.aceita()) {
      desfecho = DesfechoInteracao.RESPONDIDA;
    } else {
      desfecho = dados.desfecho();
    }
    List<TelaAtalho> atalhos = dados.atalhos();
    return new ResultadoOrquestracao(Intencao.MISTO, texto, desfecho, lei.citacoes(),
        dados.aceita(), emFallback, atalhos);
  }
}
