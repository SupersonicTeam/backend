package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.ResultadoRota;
import br.com.agrocontabil.assistente.RotaDados;
import br.com.agrocontabil.assistente.RotaLegislacao;
import br.com.agrocontabil.assistente.VerificadorNegativaDados;
import br.com.agrocontabil.assistente.fallback.ModoFallback;
import br.com.agrocontabil.assistente.fallback.TelaAtalho;
import br.com.agrocontabil.assistente.llm.DisponibilidadeModelo;
import br.com.agrocontabil.assistente.orquestracao.AtalhosFixos.AtalhoFixo;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
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

  /**
   * Parte de lei do MISTO sem trecho nenhum quando a pergunta tem uma parte legal (o classificador
   * a separou, ou os dados vieram em fallback): a recusa fala só dela (#260).
   */
  public static final String SEM_FUNDAMENTO_PARTE_LEGAL =
      "Sobre a parte de legislação da pergunta, não encontrei fundamento legal.";

  /** Ligação quando a parte de dados não encontrou nada: a regra vale em tese, não "para você". */
  public static final String LIGACAO_SEM_DADOS = "De forma geral, a legislação prevê: ";

  /** Parte de dados do MISTO sem consulta nenhuma: o modelo não buscou dado algum. */
  public static final String SEM_DADOS =
      "Não encontrei nos seus dados do sistema informação para essa pergunta.";

  private static final Logger log = LoggerFactory.getLogger(OrquestradorService.class);

  private final ClassificadorIntencao classificador;
  private final RotaDados rotaDados;
  private final RotaLegislacao rotaLegislacao;
  private final ModoFallback fallback;
  private final DisponibilidadeModelo disponibilidade;
  private final TrechosDasCompras trechosDasCompras;
  private final FundamentoRetencao fundamentoRetencao;
  private final TextosDoBackend textosDoBackend;
  private final AtalhoReformaTributaria atalhoReforma;
  private final TratamentoNaPergunta tratamentoNaPergunta;
  private final double confiancaMinima;

  /** Constrói o orquestrador. */
  public OrquestradorService(ClassificadorIntencao classificador, RotaDados rotaDados,
      RotaLegislacao rotaLegislacao, TrechosDasCompras trechosDasCompras,
      FundamentoRetencao fundamentoRetencao, TextosDoBackend textosDoBackend,
      AtalhoReformaTributaria atalhoReforma, TratamentoNaPergunta tratamentoNaPergunta,
      ModoFallback fallback,
      DisponibilidadeModelo disponibilidade, AssistenteProperties propriedades) {
    this.atalhoReforma = atalhoReforma;
    this.tratamentoNaPergunta = tratamentoNaPergunta;
    this.classificador = classificador;
    this.rotaDados = rotaDados;
    this.rotaLegislacao = rotaLegislacao;
    this.fallback = fallback;
    this.disponibilidade = disponibilidade;
    this.trechosDasCompras = trechosDasCompras;
    this.fundamentoRetencao = fundamentoRetencao;
    this.textosDoBackend = textosDoBackend;
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
    // Atalho da tela (#343): texto exato, antes do classificador e sem o modelo decidir nada. Uma
    // frase fixa da tela nunca tem forma de injecao, entao a ordem com o FiltroInjecao nao importa.
    Optional<AtalhoFixo> atalho = AtalhosFixos.reconhecer(pergunta);
    if (atalho.isPresent()) {
      return responderAtalho(atalho.get(), pergunta, contexto, aoDecidirRota);
    }
    ClassificacaoIntencao classificacao = classificar(pergunta, contexto);
    Intencao rota = classificacao.intencao();
    aoDecidirRota.accept(rota);
    contexto.verificarAtiva();

    ResultadoOrquestracao resultado = switch (rota) {
      case DADOS -> deRota(rota, dados(pergunta, contexto));
      case LEGISLACAO -> deRota(rota, lei(pergunta, contexto));
      case MISTO -> misto(pergunta, classificacao.perguntaLei(), contexto);
      // Classificacao cortada pelo teto (#270): mesma resposta fixa, desfecho proprio na auditoria.
      case FORA_DE_ESCOPO -> new ResultadoOrquestracao(Intencao.FORA_DE_ESCOPO,
          PromptsCeres.FORA_DE_ESCOPO, contexto.classificacaoCortada()
              ? DesfechoInteracao.RESPOSTA_CORTADA : DesfechoInteracao.FORA_DE_ESCOPO,
          List.of(), false);
    };
    // Tratamento de produto fora da tabela (#443): a pergunta de lei (ou de dados que nao
    // consultou a tabela) sobre um produto ganha o tratamento oficial com a ressalva.
    tratamentoDaTabela(rota, pergunta, contexto);
    // Tratamento do produto pela tabela oficial (#283): texto e citacao do dado, nao do modelo.
    resultado = textosDoBackend.aplicar(resultado, contexto);
    // Retencao (RN009): as duas regras e as citacoes vem do backend, nao da memoria do modelo.
    return fundamentoRetencao.aplicar(resultado, pergunta, contexto);
  }

  /**
   * Pergunta sobre o tratamento de um produto que a rota respondeu sem a tabela oficial (#443): a
   * rota de lei afirmava o percentual do artigo como definitivo, sem a ressalva do enquadramento.
   * Com o sinal da pergunta ({@link PerguntaTratamentoProduto}), o backend procura o produto nas
   * palavras dela ({@link TratamentoNaPergunta}, sem o modelo) e o texto da tabela, com a
   * ressalva, entra pelo {@link TextosDoBackend}: no lugar da resposta de lei, depois da de dados
   * ou do MISTO.
   */
  private void tratamentoDaTabela(Intencao rota, String pergunta, ContextoAssistente contexto) {
    if (rota == Intencao.FORA_DE_ESCOPO || !PerguntaTratamentoProduto.parece(pergunta)
        || contexto.fontes().stream()
            .anyMatch(f -> RotaDados.FUNCAO_TRATAMENTO.equals(f.funcao()))) {
      return;
    }
    tratamentoNaPergunta.aplicar(pergunta, contexto);
  }

  /**
   * Atalho fixo da tela (#343), sem nenhuma chamada ao modelo e sem depender do disjuntor: o de
   * legislação responde com o texto curado do {@link AtalhoReformaTributaria}; os de dados chamam a
   * função direto ({@link RotaDados#responderAtalho}) e saem pelo template da #266. A camada
   * {@code ATALHO_FIXO} vai à auditoria; o evento {@code rota} do SSE sai como nas outras.
   */
  private ResultadoOrquestracao responderAtalho(AtalhoFixo atalho, String pergunta,
      ContextoAssistente contexto, Consumer<Intencao> aoDecidirRota) {
    contexto.registrarCamadaClassificacao(CamadaClassificacao.ATALHO_FIXO.name());
    aoDecidirRota.accept(atalho.rota());
    contexto.verificarAtiva();
    ResultadoOrquestracao resultado = atalho.funcao() == null
        ? atalhoReforma.responder(contexto)
        : deRota(Intencao.DADOS, rotaDados.responderAtalho(atalho.funcao(), pergunta, contexto));
    resultado = textosDoBackend.aplicar(resultado, contexto);
    return fundamentoRetencao.aplicar(resultado, pergunta, contexto);
  }

  /**
   * Intenção da pergunta; confiança abaixo do mínimo vira fora de escopo. A camada que decidiu vai
   * ao contexto, para a auditoria (#265).
   */
  private ClassificacaoIntencao classificar(String pergunta, ContextoAssistente contexto) {
    ClassificacaoIntencao classificacao = classificarSemRegistro(pergunta, contexto);
    contexto.registrarCamadaClassificacao(classificacao.camada().name());
    return classificacao;
  }

  private ClassificacaoIntencao classificarSemRegistro(
      String pergunta, ContextoAssistente contexto) {
    if (FiltroInjecao.pareceInjecao(pergunta)) {
      log.info("Ceres: pergunta com forma de prompt injection tratada como fora de escopo");
      return new ClassificacaoIntencao(Intencao.FORA_DE_ESCOPO, 1, null,
          CamadaClassificacao.FILTRO_INJECAO);
    }
    try {
      // Regras e embeddings decidem mesmo com o modelo em pausa; o desempate pelo modelo, nao
      // (ClassificadorEmCamadas lanca AssistenteIndisponivelException).
      ClassificacaoIntencao classificacao = classificador.classificar(pergunta, contexto);
      return classificacao.confianca() < confiancaMinima
          ? new ClassificacaoIntencao(Intencao.FORA_DE_ESCOPO, classificacao.confianca(), null,
              classificacao.camada())
          : classificacao;
    } catch (AssistenteIndisponivelException e) {
      semModelo(contexto, "classificacao");
    }
    return new ClassificacaoIntencao(fallback.classificar(pergunta), 1, null,
        CamadaClassificacao.PALAVRAS_CHAVE);
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
   *   <li>parte descartada não entra com texto do modelo: a de dados entra com a mensagem fixa
   *       da rota; a de lei com os trechos citados, se houver (#258), ou com
   *       {@link #SEM_FUNDAMENTO_PARTE_LEGAL} quando a pergunta tem uma parte legal separada (ou os
   *       dados vieram em fallback); sem parte legal separada e sem trecho, a lei não entra
   *       (#260);</li>
   *   <li>parte em fallback entra com o texto dela (trechos verbatim ou atalhos); fallback de lei
   *       sem trecho segue a regra da lei descartada;</li>
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
    String parteLei = null;
    if (lei.aceita()) {
      parteLei = (semDados ? LIGACAO_SEM_DADOS : LIGACAO_REGRA) + lei.texto();
    } else if (leiSoTrechos || (lei.emFallback()
        && (!lei.citacoes().isEmpty() || dados.emFallback()))) {
      // Fallback de lei sem trecho so entra com os dados tambem em fallback: ao lado de uma
      // resposta de dados do modelo, "estou sem o modelo" contradiria o texto.
      parteLei = lei.texto();
    } else if (Boolean.TRUE.equals(contexto.perguntaLeiReescrita()) || dados.emFallback()) {
      // Lei sem trecho nenhum (#260), mas com uma parte legal de verdade: o classificador separou
      // a pergunta de lei, ou os dados vieram em fallback (o texto das telas nao responde a lei).
      // A recusa fala so da parte legal, para nao soar como "isso nao tem base legal".
      parteLei = SEM_FUNDAMENTO_PARTE_LEGAL;
    }
    // Sem parte legal separada e sem trecho (#260): nada da lei entra. Colada na resposta de
    // dados, "nao encontrei fundamento legal" soava como "o que voce perguntou nao tem base
    // legal", sobre o que muitas vezes nem era pergunta de lei (a classificacao e da #265). O
    // desfecho de cada parte segue no contexto e na auditoria.
    String texto = parteLei == null ? parteDados : parteDados + "\n\n" + parteLei;
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
    // A mesma frase com o recorte e o filtro reais da rota de dados (#274): "no periodo
    // consultado" nao conta como recorte.
    String frases = contexto.fontesVazias().stream()
        .map(fonte -> VerificadorNegativaDados.fraseFixa(fonte, contexto.hoje()))
        .distinct()
        .collect(Collectors.joining(" "));
    return frases.isEmpty() ? SEM_DADOS : frases;
  }
}
