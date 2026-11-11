package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.FontePergunta;
import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.ResultadoRota;
import br.com.agrocontabil.assistente.RotaDados;
import br.com.agrocontabil.assistente.RotaLegislacao;
import br.com.agrocontabil.assistente.VerificadorNegativaDados;
import br.com.agrocontabil.assistente.continuidade.Autocorrecao;
import br.com.agrocontabil.assistente.continuidade.ChamadaAssinada;
import br.com.agrocontabil.assistente.continuidade.ConferenciaConsulta;
import br.com.agrocontabil.assistente.continuidade.DecisaoContinuidade;
import br.com.agrocontabil.assistente.continuidade.DecisaoContinuidade.Decisao;
import br.com.agrocontabil.assistente.continuidade.DesfechoContinuidade;
import br.com.agrocontabil.assistente.continuidade.GatilhoReescrita;
import br.com.agrocontabil.assistente.continuidade.HashDados;
import br.com.agrocontabil.assistente.continuidade.LeiAssinada;
import br.com.agrocontabil.assistente.continuidade.MensagemContinuidade;
import br.com.agrocontabil.assistente.continuidade.ParConferido;
import br.com.agrocontabil.assistente.continuidade.ReconferenciaLei;
import br.com.agrocontabil.assistente.continuidade.ReescritaContinuidade;
import br.com.agrocontabil.assistente.continuidade.RegistroTurno;
import br.com.agrocontabil.assistente.fallback.ModoFallback;
import br.com.agrocontabil.assistente.fallback.TelaAtalho;
import br.com.agrocontabil.assistente.llm.DisponibilidadeModelo;
import br.com.agrocontabil.assistente.orquestracao.AtalhosFixos.AtalhoFixo;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final double confiancaMinima;
  private final ReescritaContinuidade reescrita;
  private final ReconferenciaLei reconferencia;

  /** Constrói o orquestrador sem a continuidade (testes da pergunta de turno único). */
  public OrquestradorService(ClassificadorIntencao classificador, RotaDados rotaDados,
      RotaLegislacao rotaLegislacao, TrechosDasCompras trechosDasCompras,
      FundamentoRetencao fundamentoRetencao, TextosDoBackend textosDoBackend,
      AtalhoReformaTributaria atalhoReforma, ModoFallback fallback,
      DisponibilidadeModelo disponibilidade, AssistenteProperties propriedades) {
    this(classificador, rotaDados, rotaLegislacao, trechosDasCompras, fundamentoRetencao,
        textosDoBackend, atalhoReforma, fallback, disponibilidade, propriedades, null, null);
  }

  /** Constrói o orquestrador com a continuidade da conversa (#499). */
  @Autowired
  public OrquestradorService(ClassificadorIntencao classificador, RotaDados rotaDados,
      RotaLegislacao rotaLegislacao, TrechosDasCompras trechosDasCompras,
      FundamentoRetencao fundamentoRetencao, TextosDoBackend textosDoBackend,
      AtalhoReformaTributaria atalhoReforma, ModoFallback fallback,
      DisponibilidadeModelo disponibilidade, AssistenteProperties propriedades,
      ReescritaContinuidade reescrita, ReconferenciaLei reconferencia) {
    this.reescrita = reescrita;
    this.reconferencia = reconferencia;
    this.atalhoReforma = atalhoReforma;
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
    return responder(pergunta, contexto, aoDecidirRota, Decisao.normal(), false);
  }

  /**
   * Responde com a decisão da continuidade (#499), tomada na thread da requisição antes da
   * reserva da cota: o mesmo objeto que decidiu quanto reservar decide o caminho.
   *
   * @param mensagem      mensagem do usuário
   * @param contexto      contexto da pergunta
   * @param aoDecidirRota avisado assim que a rota é decidida
   * @param decisao       decisão da continuidade ({@link Decisao#normal()} sem ela)
   * @param continuidade  continuidade ligada (o SEGUIMENTO usa o texto novo)
   * @return resposta validada
   */
  public ResultadoOrquestracao responder(String mensagem, ContextoAssistente contexto,
      Consumer<Intencao> aoDecidirRota, Decisao decisao, boolean continuidade) {
    // Atalho da tela (#343): texto exato, antes do classificador e sem o modelo decidir nada. Uma
    // frase fixa da tela nunca tem forma de injecao, entao a ordem com o FiltroInjecao nao importa.
    Optional<AtalhoFixo> atalho = AtalhosFixos.reconhecer(mensagem);
    if (atalho.isPresent()) {
      return responderAtalho(atalho.get(), mensagem, contexto, aoDecidirRota);
    }
    // Conversa leve (#493): saudacao, agradecimento, despedida... sem modelo e sem credito. Lista
    // fechada sem forma de injecao: roda antes do FiltroInjecao, como o atalho. Com historico
    // valido, o SEGUIMENTO e a saudacao ambigua seguem a continuidade (#499).
    if (decisao.caminho() == DecisaoContinuidade.Caminho.CONVERSA_LEVE
        || decisao.historico().vazio()) {
      Optional<ConversaLeve.Resposta> conversa = ConversaLeve.reconhecer(mensagem, continuidade);
      if (conversa.isPresent()) {
        return responderConversaLeve(conversa.get(), contexto, aoDecidirRota);
      }
    }
    if (decisao.desfecho() != null) {
      log.info("Ceres: {}", decisao.desfecho());
    }
    boolean comContinuidade = reescrita != null && reconferencia != null;
    if (comContinuidade && decisao.caminho() == DecisaoContinuidade.Caminho.CONFIRMACAO
        && decisao.par().isPresent()) {
      return confirmacao(decisao.par().get(), decisao.tipo(), contexto, aoDecidirRota);
    }
    if (comContinuidade && decisao.caminho() == DecisaoContinuidade.Caminho.REESCRITA
        && decisao.par().isPresent()) {
      return comReescrita(mensagem, decisao, contexto, aoDecidirRota);
    }
    return fluxo(mensagem, mensagem, contexto, aoDecidirRota, FontePergunta.DA_PERGUNTA,
        FontePergunta.DA_PERGUNTA);
  }

  /**
   * A pergunta de sempre, sobre a pergunta considerada. Com a continuidade, a considerada pode ter
   * saído da reescrita: ela é o que as rotas entendem, e a fonte de número é a da continuidade.
   */
  private ResultadoOrquestracao fluxo(String pergunta, String mensagemNova,
      ContextoAssistente contexto, Consumer<Intencao> aoDecidirRota, FontePergunta fonteDados,
      FontePergunta fonteLei) {
    ClassificacaoIntencao classificacao = classificar(pergunta, contexto);
    Intencao rota = classificacao.intencao();
    aoDecidirRota.accept(rota);
    contexto.verificarAtiva();

    ResultadoOrquestracao resultado = switch (rota) {
      case DADOS -> deRota(rota, dados(pergunta, contexto, fonteDados));
      case LEGISLACAO -> deRota(rota, lei(pergunta, contexto, fonteLei));
      case MISTO -> misto(pergunta, classificacao.perguntaLei(), contexto, fonteDados, fonteLei,
          mensagemNova);
      // Classificacao cortada pelo teto (#270): mesma resposta fixa, desfecho proprio na auditoria.
      // Recusa gentil, ou firme para injecao e pedido sobre outra pessoa (#494).
      case FORA_DE_ESCOPO -> new ResultadoOrquestracao(Intencao.FORA_DE_ESCOPO,
          RecusaForaDeEscopo.texto(classificacao, pergunta), contexto.classificacaoCortada()
              ? DesfechoInteracao.RESPOSTA_CORTADA : DesfechoInteracao.FORA_DE_ESCOPO,
          List.of(), false);
    };
    // Tratamento do produto pela tabela oficial (#283): texto e citacao do dado, nao do modelo.
    resultado = textosDoBackend.aplicar(resultado, contexto);
    // Retencao (RN009): as duas regras e as citacoes vem do backend, nao da memoria do modelo.
    return fundamentoRetencao.aplicar(resultado, pergunta, contexto);
  }

  // --- continuidade (#499) ---

  /**
   * Caminho da confirmação: "tem certeza?", contestação pura ou reafirmação. Segue a rota do par,
   * sem classificar, com a pergunta dele como considerada. A mensagem do produtor não vai a modelo
   * nenhum.
   */
  private ResultadoOrquestracao confirmacao(ParConferido par, MensagemContinuidade.Tipo tipo,
      ContextoAssistente contexto, Consumer<Intencao> aoDecidirRota) {
    Intencao rota = par.carga().rota();
    String pergunta = par.pergunta();
    contexto.registrarCamadaClassificacao(rota == Intencao.DADOS
        ? CamadaClassificacao.CONFIRMACAO.name() : CamadaClassificacao.RECONFERENCIA_LEI.name());
    aoDecidirRota.accept(rota);
    contexto.verificarAtiva();
    RegistroTurno registro = contexto.registroTurno();
    registro.herdarValidade(par.carga().exp());
    if (par.carga().reescrita()) {
      registro.marcarReescrita();
    }
    ResultadoOrquestracao resultado;
    if (rota == Intencao.LEGISLACAO) {
      ReconferenciaLei.Resultado lei = reconferencia.reconferir(par, contexto);
      resultado = new ResultadoOrquestracao(Intencao.LEGISLACAO, lei.rota().texto(),
          lei.rota().desfecho(), lei.rota().citacoes(), false);
      return resultado.comPerguntaConsiderada(pergunta);
    }
    DadosDaConfirmacao dados = dadosDaConfirmacao(par, tipo, contexto);
    if (rota == Intencao.DADOS) {
      resultado = deRota(Intencao.DADOS, dados.resultado());
    } else {
      contexto.registrarDesfechoDados(dados.resultado().desfecho());
      ReconferenciaLei.Resultado lei = reconferencia.reconferir(par, contexto);
      contexto.registrarDesfechoLegislacao(lei.rota().desfecho(), false);
      resultado = combinarComReconferencia(dados.resultado(), lei.rota(), contexto);
    }
    if (dados.semAbertura()) {
      return resultado.comPerguntaConsiderada(pergunta);
    }
    resultado = textosDoBackend.aplicar(resultado, contexto);
    resultado = fundamentoRetencao.aplicar(resultado, pergunta, contexto);
    // A abertura entra depois de todos os validadores e das camadas do backend.
    return (dados.abertura() == null ? resultado : resultado.comAbertura(dados.abertura()))
        .comPerguntaConsiderada(pergunta);
  }

  /**
   * A parte de dados do caminho da confirmação e a abertura dela (#499, "Autocorreção").
   *
   * @param resultado  resultado da rota
   * @param abertura   abertura do backend, ou nula
   * @param semAbertura a reexecução falhou: o texto fixo sai sozinho, sem as camadas do backend
   */
  private record DadosDaConfirmacao(ResultadoRota resultado, String abertura,
      boolean semAbertura) {}

  private DadosDaConfirmacao dadosDaConfirmacao(ParConferido par, MensagemContinuidade.Tipo tipo,
      ContextoAssistente contexto) {
    List<ChamadaAssinada> anterior = par.carga().consulta();
    String hashAnterior = par.carga().dados();
    if (anterior != null && DecisaoContinuidade.consultaBate(par)) {
      // Reexecucao, custo zero: a mesma consulta, sem modelo.
      ResultadoRota refeita = rotaDados.reexecutar(anterior, par.pergunta(), contexto);
      if (Autocorrecao.FALHA_REEXECUCAO.equals(refeita.texto())) {
        log.info("Ceres: {}", DesfechoContinuidade.REEXECUCAO_FALHOU);
        return new DadosDaConfirmacao(refeita, null, true);
      }
      registrarDados(refeita, contexto);
      String abertura = aberturaDaReexecucao(anterior, hashAnterior, tipo, refeita, contexto);
      return new DadosDaConfirmacao(refeita, abertura, false);
    }
    // Consulta que nao bate (ou par sem consulta): o modelo escolhe de novo sobre a pergunta do
    // par. Fonte de numero: a pergunta so sem reescrita; senao, os argumentos executados agora.
    FontePergunta fonte = par.carga().reescrita()
        ? FontePergunta.dosArgumentos() : FontePergunta.texto(par.pergunta());
    log.info("Ceres: {}", DesfechoContinuidade.CONFIRMACAO_COM_MODELO);
    ResultadoRota nova = dados(par.pergunta(), contexto, fonte);
    if (!nova.aceita()) {
      return new DadosDaConfirmacao(nova, null, false);
    }
    List<ChamadaAssinada> executada = contexto.registroTurno().consulta();
    String hashNovo = contexto.registroTurno().hashDados();
    if (anterior == null || executada == null) {
      return new DadosDaConfirmacao(nova, Autocorrecao.CONFERI, false);
    }
    String descricao = Autocorrecao.descrever(executada);
    boolean mesmaConsulta = ChamadaAssinada.multiconjunto(executada)
        .equals(ChamadaAssinada.multiconjunto(anterior));
    boolean mesmoResultado = hashNovo != null && hashNovo.equals(hashAnterior);
    String abertura;
    if (mesmaConsulta) {
      abertura = aberturaDaReexecucao(anterior, hashAnterior, tipo, nova, contexto);
    } else if (!mesmoResultado && ConferenciaConsulta.bate(par.pergunta(), executada,
        contexto.hoje())) {
      log.info("Ceres: {}", DesfechoContinuidade.CORRIGIDA);
      abertura = Autocorrecao.CORRIJO.formatted(descricao);
    } else if (mesmoResultado) {
      abertura = Autocorrecao.CONFERI_IGUAL;
    } else {
      log.info("Ceres: {}", DesfechoContinuidade.REFEITA);
      abertura = Autocorrecao.REFIZ.formatted(descricao);
    }
    return new DadosDaConfirmacao(nova, abertura, false);
  }

  private static String aberturaDaReexecucao(List<ChamadaAssinada> consulta, String hashAnterior,
      MensagemContinuidade.Tipo tipo, ResultadoRota resultado, ContextoAssistente contexto) {
    if (!resultado.aceita()) {
      return null;
    }
    String descricao = Autocorrecao.descrever(consulta);
    String hashNovo = contexto.registroTurno().hashDados();
    if (hashNovo != null && hashNovo.equals(hashAnterior)) {
      log.info("Ceres: {}", DesfechoContinuidade.RESULTADO_IGUAL);
      String abertura = Autocorrecao.REPETI_IGUAL.formatted(descricao);
      return tipo.contestacao() ? abertura + " " + Autocorrecao.DICA_CONTESTACAO : abertura;
    }
    log.info("Ceres: {}", DesfechoContinuidade.RESULTADO_MUDOU);
    return Autocorrecao.REPETI_MUDOU.formatted(descricao);
  }

  /**
   * O MISTO do caminho da confirmação: a parte de dados como no {@link #combinar} e a lei da
   * reconferência sempre depois do {@link #LIGACAO_REGRA}, nunca o
   * {@link #SEM_FUNDAMENTO_PARTE_LEGAL}.
   */
  private static ResultadoOrquestracao combinarComReconferencia(ResultadoRota dados,
      ResultadoRota lei, ContextoAssistente contexto) {
    boolean semDados = dados.aceita() && contexto.nenhumDadoEncontrado();
    String parteDados = semDados ? fraseSemDados(contexto) : dados.texto();
    String parteLei = LIGACAO_REGRA + lei.texto();
    contexto.registroTurno().registrarParteLei(parteLei);
    DesfechoInteracao desfecho = dados.emFallback() ? DesfechoInteracao.MODO_FALLBACK
        : dados.aceita() || lei.aceita() ? DesfechoInteracao.RESPONDIDA : lei.desfecho();
    return new ResultadoOrquestracao(Intencao.MISTO, parteDados + "\n\n" + parteLei, desfecho,
        lei.citacoes(), dados.aceita(), dados.emFallback(), dados.atalhos());
  }

  /**
   * Com reescrita: a mensagem nova vira a pergunta completa (ou fica, se uma guarda barrar).
   * Reescrita igual à pergunta do último par ("repete isso") vai ao caminho da confirmação; igual
   * a um atalho, ao atalho.
   */
  private ResultadoOrquestracao comReescrita(String mensagem, Decisao decisao,
      ContextoAssistente contexto, Consumer<Intencao> aoDecidirRota) {
    ParConferido par = decisao.par().orElseThrow();
    MensagemContinuidade.Tipo tipo = decisao.tipo();
    Optional<GatilhoReescrita.Criterio> criterio = GatilhoReescrita.criterio(mensagem, tipo);
    ReescritaContinuidade.Resultado reescrito;
    if (criterio.isEmpty() || !disponibilidade.disponivel()) {
      reescrito = new ReescritaContinuidade.Resultado(mensagem,
          DesfechoContinuidade.CONTINUIDADE_SEM_MODELO);
    } else {
      reescrito = reescrita.reescrever(mensagem, decisao.historico(), criterio.get(), tipo,
          contexto);
    }
    log.info("Ceres: {}", reescrito.desfecho());
    if (!reescrito.aplicada()) {
      return fluxo(mensagem, mensagem, contexto, aoDecidirRota, FontePergunta.DA_PERGUNTA,
          FontePergunta.DA_PERGUNTA);
    }
    String considerada = reescrito.pergunta();
    contexto.registroTurno().marcarReescrita();
    Optional<AtalhoFixo> atalho = AtalhosFixos.reconhecer(considerada);
    if (atalho.isPresent()) {
      return responderAtalho(atalho.get(), considerada, contexto, aoDecidirRota)
          .comPerguntaConsiderada(considerada);
    }
    if (RegrasClassificacao.normalizar(considerada)
        .equals(RegrasClassificacao.normalizar(par.pergunta()))) {
      return confirmacao(par, MensagemContinuidade.Tipo.CONFIRMACAO, contexto, aoDecidirRota);
    }
    // Fonte de numero (#499): so a mensagem nova; na lei de uma contestacao, so os trechos.
    FontePergunta fonteDados = FontePergunta.texto(mensagem);
    FontePergunta fonteLei = tipo == MensagemContinuidade.Tipo.CONTESTACAO_CONTEUDO
        ? FontePergunta.dosArgumentos() : FontePergunta.texto(mensagem);
    ResultadoOrquestracao resultado = fluxo(considerada, mensagem, contexto, aoDecidirRota,
        fonteDados, fonteLei);
    if (tipo == MensagemContinuidade.Tipo.CONTESTACAO_CONTEUDO && resultado.exibirFontes()
        && contexto.registroTurno().consulta() != null) {
      // Contestacao com conteudo novo: comparacao neutra, nunca "corrijo".
      boolean mesmo = contexto.registroTurno().hashDados() != null
          && contexto.registroTurno().hashDados().equals(par.carga().dados());
      resultado = resultado.comAbertura(mesmo ? Autocorrecao.CONFERI_IGUAL
          : Autocorrecao.REFIZ_COM_O_QUE_DISSE.formatted(
              Autocorrecao.descrever(contexto.registroTurno().consulta())));
    }
    return resultado.comPerguntaConsiderada(considerada);
  }

  /**
   * O que a parte de dados deixa para a carga do turno: com ela aceita, o texto de dados para o
   * hash (o da rota mais os textos do backend); sem ela, nada de consulta.
   */
  private static void registrarDados(ResultadoRota dados, ContextoAssistente contexto) {
    RegistroTurno registro = contexto.registroTurno();
    if (!dados.aceita()) {
      registro.descartarConsulta();
      return;
    }
    List<String> partes = new ArrayList<>();
    partes.add(dados.texto());
    contexto.textosDoBackend().forEach(t -> partes.add(t.texto()));
    registro.registrarTextoDados(HashDados.normalizar(partes));
  }

  /** O que a parte de lei deixa para a carga do turno: a classe, o texto do modelo e as citações. */
  private static void registrarLei(ResultadoRota lei, ContextoAssistente contexto) {
    LeiAssinada.Classe classe;
    String textoModelo = null;
    if (lei.aceita()) {
      classe = LeiAssinada.Classe.CONFIRMADA;
      textoModelo = lei.detalheLei() == null ? null : lei.detalheLei().textoEntregue();
    } else if (RotaLegislacao.SO_TRECHOS.equals(lei.texto())) {
      classe = LeiAssinada.Classe.CONFIRMADA;
    } else if (lei.citacoes().isEmpty()) {
      classe = LeiAssinada.Classe.SEM_FUNDAMENTO;
    } else {
      classe = LeiAssinada.Classe.SEM_CONFIRMACAO;
    }
    contexto.registroTurno().registrarLei(classe, textoModelo,
        lei.citacoes().stream().map(OrquestradorService::chave).toList());
  }

  private static String chave(CitacaoLegal citacao) {
    return citacao.norma() + "|" + citacao.dispositivo();
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
    ResultadoOrquestracao resultado;
    if (atalho.funcao() == null) {
      resultado = atalhoReforma.responder(contexto);
      // Carga do turno (#499): a reconferencia de um atalho de lei e o texto fixo dele.
      contexto.registroTurno().registrarLei(LeiAssinada.Classe.CONFIRMADA, null,
          resultado.citacoes().stream().map(OrquestradorService::chave).toList());
      contexto.registroTurno().registrarAtalhoLei(atalho.name());
    } else {
      ResultadoRota dados = rotaDados.responderAtalho(atalho.funcao(), pergunta, contexto);
      registrarDados(dados, contexto);
      resultado = deRota(Intencao.DADOS, dados);
    }
    resultado = textosDoBackend.aplicar(resultado, contexto);
    return fundamentoRetencao.aplicar(resultado, pergunta, contexto);
  }

  /**
   * Conversa leve (#493): texto fixo do backend, sem modelo, sem o disjuntor e sem as camadas de
   * texto do backend (não há dado nem citação). Rota {@code FORA_DE_ESCOPO}, como o contrato já
   * define para o texto fixo de apresentação; camada e desfecho {@code CONVERSA_LEVE} na auditoria.
   */
  private static ResultadoOrquestracao responderConversaLeve(ConversaLeve.Resposta resposta,
      ContextoAssistente contexto, Consumer<Intencao> aoDecidirRota) {
    contexto.registrarCamadaClassificacao(CamadaClassificacao.CONVERSA_LEVE.name());
    aoDecidirRota.accept(Intencao.FORA_DE_ESCOPO);
    contexto.verificarAtiva();
    return new ResultadoOrquestracao(Intencao.FORA_DE_ESCOPO, resposta.texto(),
        DesfechoInteracao.CONVERSA_LEVE, List.of(), false);
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
  private ResultadoOrquestracao misto(String pergunta, String reescrita,
      ContextoAssistente contexto, FontePergunta fonteDados, FontePergunta fonteLei,
      String mensagemNova) {
    ResultadoRota dados = dados(pergunta, contexto, fonteDados);
    contexto.registrarDesfechoDados(dados.desfecho());
    // Com a continuidade, os digitos da pergunta de lei so valem se estao na mensagem nova.
    PerguntaLei perguntaLei = PerguntaLei.escolher(pergunta, reescrita, mensagemNova);
    if (reescrita != null && !perguntaLei.reescrita()) {
      log.info("Ceres: pergunta de lei do classificador descartada; vai a pergunta inteira");
    }
    ResultadoRota lei = leiRegraGeral(perguntaLei, contexto, fonteLei);
    contexto.registrarDesfechoLegislacao(lei.desfecho(), perguntaLei.reescrita());
    if (perguntaLei.reescrita()) {
      contexto.registroTurno().registrarPerguntaLei(perguntaLei.texto());
    }
    return combinar(dados, lei, contexto);
  }

  private ResultadoRota dados(String pergunta, ContextoAssistente contexto,
      FontePergunta fonte) {
    ResultadoRota resultado = null;
    if (disponibilidade.disponivel()) {
      try {
        resultado = rotaDados.responder(pergunta, contexto, fonte);
      } catch (AssistenteIndisponivelException e) {
        semModelo(contexto, "dados");
      }
    }
    if (resultado == null) {
      contexto.registroTurno().descartarConsulta();
      return fallback.parteDados(pergunta);
    }
    registrarDados(resultado, contexto);
    return resultado;
  }

  private ResultadoRota lei(String pergunta, ContextoAssistente contexto, FontePergunta fonte) {
    if (disponibilidade.disponivel()) {
      try {
        ResultadoRota resultado = rotaLegislacao.responder(pergunta, contexto, fonte);
        registrarLei(resultado, contexto);
        return resultado;
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
  private ResultadoRota leiRegraGeral(PerguntaLei pergunta, ContextoAssistente contexto,
      FontePergunta fonte) {
    if (disponibilidade.disponivel()) {
      try {
        ResultadoRota resultado = rotaLegislacao.responderRegraGeral(pergunta.texto(), contexto,
            trechosDasCompras.buscar(contexto), pergunta.reescrita(), fonte);
        registrarLei(resultado, contexto);
        return resultado;
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
    // O corte do turno (#499) divide o MISTO em duas partes de ate 3000 caracteres.
    contexto.registroTurno().registrarParteLei(parteLei);
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
