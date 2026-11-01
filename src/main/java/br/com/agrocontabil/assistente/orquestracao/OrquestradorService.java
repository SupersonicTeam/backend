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
import java.util.Map;
import java.util.Objects;
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

  /** Ligação entre a parte de dados e a regra legal no MISTO (issue #170). */
  public static final String LIGACAO_REGRA = "Sobre a regra legal: ";

  /** Ligação quando a parte de dados não encontrou nada: a regra vale em tese, não "para você". */
  public static final String LIGACAO_SEM_DADOS = "De forma geral, a legislacao preve: ";

  /** Parte de dados do MISTO sem nada encontrado, pela primeira função consultada (#170). */
  static final Map<String, String> SEM_DADOS_POR_FUNCAO = Map.ofEntries(
      Map.entry("totalComprasPorInsumo",
          "Nao encontrei compras desse tipo nas suas notas no periodo consultado."),
      Map.entry("listarNotas", "Nao encontrei notas com esse filtro no periodo consultado."),
      Map.entry("proximosVencimentos", "Nao encontrei guias a vencer no periodo consultado."),
      Map.entry("guiasEmAtraso", "Nao encontrei guias em atraso."),
      Map.entry("vencimentosDoMes", "Nao encontrei guias para pagar no mes consultado."),
      Map.entry("consultarNota", "Nao encontrei essa nota nas suas notas."),
      Map.entry("totalVendasPorProduto",
          "Nao encontrei vendas desse produto nas suas notas no periodo consultado."),
      Map.entry("consultarCreditos", "Nao encontrei compras com credito no periodo consultado."),
      Map.entry("consultarApuracao", "Nao encontrei notas na apuracao do mes consultado."),
      Map.entry("listarAlertas", "Nao encontrei prazos vencendo nos proximos dias."),
      Map.entry("consultarRetencoes", "Nao encontrei vendas autorizadas no periodo consultado."));

  /** Parte de dados do MISTO sem consulta nenhuma: o modelo não buscou dado algum. */
  public static final String SEM_DADOS =
      "Nao encontrei nos seus dados do sistema informacao para essa pergunta.";

  private static final Logger log = LoggerFactory.getLogger(OrquestradorService.class);

  private final ClassificadorIntencao classificador;
  private final RotaDados rotaDados;
  private final RotaLegislacao rotaLegislacao;
  private final ModoFallback fallback;
  private final DisponibilidadeModelo disponibilidade;
  private final TrechosDasCompras trechosDasCompras;
  private final FundamentoRetencao fundamentoRetencao;
  private final double confiancaMinima;

  /** Constrói o orquestrador. */
  public OrquestradorService(ClassificadorIntencao classificador, RotaDados rotaDados,
      RotaLegislacao rotaLegislacao, TrechosDasCompras trechosDasCompras,
      FundamentoRetencao fundamentoRetencao, ModoFallback fallback,
      DisponibilidadeModelo disponibilidade, AssistenteProperties propriedades) {
    this.classificador = classificador;
    this.rotaDados = rotaDados;
    this.rotaLegislacao = rotaLegislacao;
    this.fallback = fallback;
    this.disponibilidade = disponibilidade;
    this.trechosDasCompras = trechosDasCompras;
    this.fundamentoRetencao = fundamentoRetencao;
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
    ClassificacaoIntencao classificacao = classificar(pergunta, contexto);
    Intencao rota = classificacao.intencao();
    aoDecidirRota.accept(rota);
    contexto.verificarAtiva();

    ResultadoOrquestracao resultado = switch (rota) {
      case DADOS -> deRota(rota, dados(pergunta, contexto));
      case LEGISLACAO -> deRota(rota, lei(pergunta, contexto));
      case MISTO -> misto(pergunta, classificacao.perguntaLei(), contexto);
      case FORA_DE_ESCOPO -> new ResultadoOrquestracao(Intencao.FORA_DE_ESCOPO,
          PromptsCeres.FORA_DE_ESCOPO, DesfechoInteracao.FORA_DE_ESCOPO, List.of(), false);
    };
    // Retencao (RN009): as duas regras e as citacoes vem do backend, nao da memoria do modelo.
    return fundamentoRetencao.aplicar(resultado, pergunta, contexto);
  }

  /** Intenção da pergunta; confiança abaixo do mínimo vira fora de escopo. */
  private ClassificacaoIntencao classificar(String pergunta, ContextoAssistente contexto) {
    if (FiltroInjecao.pareceInjecao(pergunta)) {
      log.info("Ceres: pergunta com forma de prompt injection tratada como fora de escopo");
      return new ClassificacaoIntencao(Intencao.FORA_DE_ESCOPO, 1);
    }
    if (disponibilidade.disponivel()) {
      try {
        ClassificacaoIntencao classificacao = classificador.classificar(pergunta, contexto);
        return classificacao.confianca() < confiancaMinima
            ? new ClassificacaoIntencao(Intencao.FORA_DE_ESCOPO, classificacao.confianca())
            : classificacao;
      } catch (AssistenteIndisponivelException e) {
        semModelo(contexto, "classificacao");
      }
    }
    return new ClassificacaoIntencao(fallback.classificar(pergunta), 1);
  }

  /**
   * As duas partes do MISTO. A de lei recebe só a pergunta legal, se o classificador a separou e
   * ela passou nas conferências ({@link PerguntaLei}); senão, a pergunta inteira. O desfecho de
   * cada parte fica no contexto para a auditoria.
   */
  private ResultadoOrquestracao misto(
      String pergunta, String reescrita, ContextoAssistente contexto) {
    ResultadoRota dados = dados(pergunta, contexto);
    contexto.registrarDesfechoDados(dados.desfecho());
    PerguntaLei perguntaLei = PerguntaLei.escolher(pergunta, reescrita);
    if (reescrita != null && !perguntaLei.reescrita()) {
      log.info("Ceres: pergunta de lei do classificador descartada; vai a pergunta inteira");
    }
    ResultadoRota lei = leiRegraGeral(perguntaLei, contexto);
    contexto.registrarDesfechoLegislacao(lei.desfecho(), perguntaLei.reescrita());
    return combinar(dados, lei, contexto);
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

  /**
   * Parte de lei do MISTO: só a regra geral, sem falar dos dados (issue #170).
   *
   * @param pergunta a pergunta legal ({@link PerguntaLei}), não necessariamente a do usuário
   */
  private ResultadoRota leiRegraGeral(PerguntaLei pergunta, ContextoAssistente contexto) {
    if (disponibilidade.disponivel()) {
      try {
        return rotaLegislacao.responderRegraGeral(pergunta.texto(), contexto,
            trechosDasCompras.buscar(contexto), pergunta.reescrita());
      } catch (AssistenteIndisponivelException e) {
        semModelo(contexto, "legislacao");
      }
    }
    return fallback.parteLei(pergunta.texto(), contexto);
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
   * Junta as duas partes do MISTO com texto de ligação fixo (issue #170):
   * <ul>
   *   <li>parte de dados aceita mas sem nada encontrado nas consultas (ou sem consulta nenhuma)
   *       vira {@link #fraseSemDados frase fixa do backend}, e a lei entra como "de forma geral" —
   *       nunca "não tenho a informação" seguido de "sim, as suas compras têm direito";</li>
   *   <li>parte descartada não entra com texto do modelo: no lugar vai a mensagem fixa da rota;</li>
   *   <li>parte em fallback entra com o texto dela (trechos verbatim ou atalhos);</li>
   *   <li>lei que falou dos dados entra só como "veja os trechos citados".</li>
   * </ul>
   * Desfecho: {@code MODO_FALLBACK} se alguma parte veio sem modelo; {@code AFIRMACAO_SOBRE_DADOS}
   * se a lei falou dos dados (#170); senão {@code RESPONDIDA} se ao menos uma parte foi aceita;
   * senão o da parte de dados. Lei descartada com trechos (#258) entra com a frase fixa dela e as
   * citações; o desfecho dela fica no contexto (e na auditoria).
   */
  private static ResultadoOrquestracao combinar(
      ResultadoRota dados, ResultadoRota lei, ContextoAssistente contexto) {
    boolean semDados = dados.aceita() && contexto.nenhumDadoEncontrado();
    String parteDados = semDados ? fraseSemDados(contexto) : dados.texto();
    // Lei descartada mas com trechos citados (AFIRMACAO_SOBRE_DADOS, #170; numero sem fonte ou
    // marca inventada ao lado de marcas certas, #258): entra o SO_TRECHOS com as citacoes, nunca
    // "nao encontrei fundamento legal" ao lado dos artigos.
    boolean leiSoTrechos = lei.soTrechos();
    String parteLei;
    if (lei.aceita()) {
      parteLei = (semDados ? LIGACAO_SEM_DADOS : LIGACAO_REGRA) + lei.texto();
    } else if (lei.emFallback() || leiSoTrechos) {
      parteLei = lei.texto();
    } else {
      parteLei = ValidadorCitacao.SEM_FUNDAMENTO;
    }
    boolean dadosUtil = dados.aceita() || dados.emFallback();
    boolean leiUtil = lei.aceita() || lei.emFallback() || leiSoTrechos;
    String texto = dadosUtil || leiUtil
        ? parteDados + "\n\n" + parteLei
        : parteDados;
    boolean emFallback = dados.emFallback() || lei.emFallback();
    DesfechoInteracao desfecho;
    if (emFallback) {
      desfecho = DesfechoInteracao.MODO_FALLBACK;
    } else if (lei.desfecho() == DesfechoInteracao.AFIRMACAO_SOBRE_DADOS) {
      desfecho = DesfechoInteracao.AFIRMACAO_SOBRE_DADOS;
    } else if (dados.aceita() || lei.aceita()) {
      desfecho = DesfechoInteracao.RESPONDIDA;
    } else {
      desfecho = dados.desfecho();
    }
    List<TelaAtalho> atalhos = dados.atalhos();
    return new ResultadoOrquestracao(Intencao.MISTO, texto, desfecho, lei.citacoes(),
        dados.aceita(), emFallback, atalhos);
  }

  /**
   * Frase fixa da parte de dados sem nada encontrado. Texto do modelo não entra: sem dado, ele só
   * poderia afirmar o que não viu.
   */
  static String fraseSemDados(ContextoAssistente contexto) {
    return contexto.fontes().stream()
        .map(fonte -> SEM_DADOS_POR_FUNCAO.get(fonte.funcao()))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(SEM_DADOS);
  }
}
