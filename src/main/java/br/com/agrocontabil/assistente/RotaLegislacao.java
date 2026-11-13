package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.fidelidade.ConferenciaFidelidadeLei;
import br.com.agrocontabil.assistente.fidelidade.JuizFidelidadeLei;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.rag.BuscaLegislacaoService;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.DesfechoFidelidadeLei;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Rota de legislação da Ceres — RAG com citação obrigatória (issue #156).
 *
 * <ol>
 *   <li>recupera os trechos vigentes na data da pergunta ({@link BuscaLegislacaoService}); sem
 *       trecho acima do limiar, responde "não encontrei fundamento legal" <strong>sem chamar o
 *       modelo</strong>;</li>
 *   <li>o modelo responde só com base nos trechos, marcados {@code [T1]}…{@code [Tn]}, sem
 *       funções e sem escrever referência legal;</li>
 *   <li>o {@link ValidadorCitacao} exige marca existente e troca cada marca pela referência montada
 *       dos metadados — citação inventada derruba a resposta;</li>
 *   <li>o {@link ValidadorNumeros} confere que percentuais, prazos e números de artigo da resposta
 *       estão nos trechos (ou na pergunta): um "redução de 70%" que a lei não diz não passa;</li>
 *   <li>a {@link GuardaRegimeEspecifico} descarta a resposta que cita só regime específico (LC 214,
 *       arts. 172 a 307) para pergunta que não cita o setor (#269);</li>
 *   <li>a resposta aceita ganha a linha "O que isso quer dizer" ({@link LinhaGlossario}) com a
 *       definição dos termos do glossário que ela usa sem explicar, montada pelo backend (#269).</li>
 * </ol>
 *
 * <p><strong>Fidelidade (#269, flag {@code app.assistente.fidelidade-lei.enabled}, desligada por
 * padrão):</strong> ligada, o modelo responde em JSON, cada frase com a passagem do trecho que a
 * sustenta, e a {@link ConferenciaFidelidadeLei} confere em código, antes do passo 3, a cópia, o
 * número, a negação e a cobertura de cada frase, e põe a citação no nível do dispositivo em que a
 * passagem está. Reprovada, o texto do modelo sai e ficam os trechos que ele marcou, sem afirmar que
 * respondem (desfecho {@code FIDELIDADE_REPROVADA}). Desligada, nada muda.
 *
 * <p><strong>Juiz (#269, camada 2, {@code app.assistente.fidelidade-lei.juiz.enabled}, desligado
 * por padrão, só com a camada 1 ligada):</strong> depois de todas as conferências em código, uma
 * chamada ao modelo ({@link JuizFidelidadeLei}) julga se as cópias sustentam cada frase. Qualquer
 * frase sem {@code SUSTENTA}, ou a falha do juiz, reprova a resposta pelo mesmo caminho da camada 1.
 *
 * <p><strong>Resgate (#269, {@code app.assistente.fidelidade-lei.juiz.resgate.enabled}, desligado
 * por padrão, só com o juiz):</strong> a frase que a camada 1 reprovaria por número (e, com
 * {@code resgate.polaridade} e {@code resgate.cobertura}, por polaridade e por cobertura) segue
 * para as conferências seguintes e para o juiz, na mesma chamada do veto. Só o {@code SUSTENTA} de todas as frases a aceita
 * ({@code APROVADA_RESGATE_JUIZ}); veto, falha ou corte do juiz mantêm o descarte com o motivo da
 * camada 1. Formato, evidência ausente e cópia não literal não têm resgate.
 */
@Component
public class RotaLegislacao {

  private static final Logger log = LoggerFactory.getLogger(RotaLegislacao.class);

  /** Raciocínio que alguns modelos (qwen3) escrevem no texto quando o thinking não é separado. */
  private static final Pattern RACIOCINIO = Pattern.compile("(?s)<think>.*?</think>");

  /**
   * "Sim" ou "Não" no começo da resposta, como o {@code VerificadorAfirmacaoDados} reconhece:
   * "Sim" como palavra inteira, "Não" só com pontuação ("Não incide" é regra, não resposta). Aceita
   * o negrito do Markdown em volta ("**Sim**,") e travessão, que o modelo usa.
   */
  private static final Pattern SIM_OU_NAO_INICIAL = Pattern.compile(
      "^[\\s\\p{Punct}\\u2013\\u2014]*"
          + "(?:sim(?=[\\s\\p{Punct}\\u2013\\u2014]|$)|n[a\\u00e3]o[*_]*\\s*[,.;:!\\u2013\\u2014-])"
          + "[\\s\\p{Punct}\\u2013\\u2014]*",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  /**
   * Texto da resposta de lei sem o texto do modelo, só com as citações validadas: no MISTO quando o
   * modelo falou dos dados do produtor (issue #170), e em qualquer rota quando a resposta tinha
   * número fora dos trechos ou marca inventada ao lado de marcas certas (issue #258). Sem
   * dígitos, para não virar fonte de número.
   */
  public static final String SO_TRECHOS =
      "A regra legal que trata do assunto está nos trechos de lei citados.";

  /**
   * Resposta de lei descartada (número fora dos trechos ou marca inventada) com trechos marcados
   * certo (issue #258). <strong>Não afirma</strong> que os trechos respondem: numa pergunta de
   * premissa falsa ("qual artigo isenta o celular?") o descarte é o sinal de que eles não
   * respondem, e o {@link #SO_TRECHOS} diria o contrário. Sem dígitos, para não virar fonte.
   */
  public static final String TRECHOS_SEM_CONFIRMACAO =
      "Não consegui confirmar uma resposta com a legislação. Estes são os trechos de lei mais "
          + "próximos da pergunta, para consulta.";

  /**
   * Trechos mostrados quando o modelo não marca nenhum válido (#343): os primeiros da busca, já
   * ordenados do mais para o menos próximo.
   */
  static final int TRECHOS_RELACIONADOS = 3;

  private final ModeloLinguagem modelo;
  private final BuscaLegislacaoService busca;
  private final ValidadorCitacao validadorCitacao;
  private final ValidadorNumeros validadorNumeros;
  private final VerificadorAfirmacaoDados verificadorDados;
  private final int tetoSaida;
  /** Nula = conferência de fidelidade desligada (flag desligada ou construtor sem ela). */
  private final ConferenciaFidelidadeLei conferencia;
  /** Nulo = juiz desligado (chave, camada 1 desligada ou construtor sem ele). */
  private final JuizFidelidadeLei juiz;
  private final double coberturaMinima;
  /** Motivos da camada 1 que vão ao juiz em vez de reprovar (#269); vazio = sem resgate. */
  private final Set<DesfechoFidelidadeLei> resgataveis;

  /**
   * Constrói a rota sem a conferência de fidelidade (#269): a rota de lei de antes da flag. O teto de
   * tokens da resposta vem de {@code app.assistente.tetos} (#270).
   */
  public RotaLegislacao(ModeloLinguagem modelo, BuscaLegislacaoService busca,
      ValidadorCitacao validadorCitacao, ValidadorNumeros validadorNumeros,
      VerificadorAfirmacaoDados verificadorDados, AssistenteProperties propriedades) {
    this(modelo, busca, validadorCitacao, validadorNumeros, verificadorDados, propriedades, null,
        null);
  }

  /**
   * Constrói a rota; a conferência de fidelidade só roda com
   * {@code app.assistente.fidelidade-lei.enabled}, e o juiz só com ela e
   * {@code app.assistente.fidelidade-lei.juiz.enabled} (#269).
   */
  @Autowired
  public RotaLegislacao(ModeloLinguagem modelo, BuscaLegislacaoService busca,
      ValidadorCitacao validadorCitacao, ValidadorNumeros validadorNumeros,
      VerificadorAfirmacaoDados verificadorDados, AssistenteProperties propriedades,
      ConferenciaFidelidadeLei conferencia, JuizFidelidadeLei juiz) {
    this.modelo = modelo;
    this.busca = busca;
    this.validadorCitacao = validadorCitacao;
    this.validadorNumeros = validadorNumeros;
    this.verificadorDados = verificadorDados;
    this.tetoSaida = propriedades.getTetos().getLegislacao();
    AssistenteProperties.FidelidadeLei fidelidade = propriedades.getFidelidadeLei();
    this.conferencia = fidelidade.isEnabled() ? conferencia : null;
    this.juiz = this.conferencia != null && propriedades.juizDaLeiAtivo() ? juiz : null;
    this.coberturaMinima = this.juiz != null
        ? fidelidade.getCoberturaMinimaComJuiz()
        : fidelidade.getCoberturaMinima();
    this.resgataveis = this.juiz != null && propriedades.resgateDaLeiAtivo()
        ? resgataveis(fidelidade.getJuiz().getResgate())
        : Set.of();
  }

  private static Set<DesfechoFidelidadeLei> resgataveis(
      AssistenteProperties.FidelidadeLei.Juiz.Resgate resgate) {
    Set<DesfechoFidelidadeLei> motivos = EnumSet.of(DesfechoFidelidadeLei.REPROVADA_NUMERO);
    if (resgate.isPolaridade()) {
      motivos.add(DesfechoFidelidadeLei.REPROVADA_POLARIDADE);
    }
    if (resgate.isCobertura()) {
      motivos.add(DesfechoFidelidadeLei.REPROVADA_COBERTURA);
    }
    return Set.copyOf(motivos);
  }

  /**
   * Responde uma pergunta sobre a legislação.
   *
   * @param pergunta pergunta do usuário
   * @param contexto contexto da pergunta (data de referência, auditoria)
   * @return resposta com citações validadas; {@link #TRECHOS_SEM_CONFIRMACAO} quando o texto do
   *         modelo é descartado com trechos recuperados — com os trechos que ele marcou certo
   *         (marca inventada ao lado ou número sem fonte, #258) ou, sem marca válida ou com
   *         {@code NAO_ENCONTREI}, com os mais próximos da busca (#343); sem trecho, a mensagem
   *         fixa de "sem fundamento legal"
   * @throws br.com.agrocontabil.exception.AssistenteIndisponivelException se a busca ou o modelo
   *         falharem
   */
  public ResultadoRota responder(String pergunta, ContextoAssistente contexto) {
    return responder(pergunta, contexto, false, List.of());
  }

  /**
   * Parte de legislação do MISTO (issue #170): só a regra geral, sem falar dos dados do produtor.
   * Se o texto do modelo se referir ao usuário ou ao que ele tem, comprou ou vendeu, o texto é
   * trocado por {@link #SO_TRECHOS} e as citações validadas ficam — a regra continua acessível,
   * a afirmação sobre dados que esta rota não viu, não.
   *
   * <p>{@code complementares} são trechos achados por outro caminho — os itens de anexo com o NCM
   * das compras do produtor — e entram depois dos da busca por similaridade, sem repetição e
   * renumerados ({@code [T1]}…{@code [Tn]} seguem a ordem que o modelo vê).
   *
   * @param pergunta       pergunta do usuário
   * @param contexto       contexto da pergunta
   * @param complementares trechos extras (podem ser vazios)
   * @return regra geral com citações, ou só as citações
   */
  public ResultadoRota responderRegraGeral(String pergunta, ContextoAssistente contexto,
      List<TrechoRecuperado> complementares) {
    return responderRegraGeral(pergunta, contexto, complementares, false);
  }

  /**
   * Parte de legislação do MISTO, sabendo se a pergunta é a legal separada pelo classificador.
   *
   * <p>Com a pergunta separada ("fertilizante tem redução?"), o modelo responde à regra geral, e
   * o "Sim," ou "Não," do começo responde a ela, não ao caso do produtor: a palavra sai e o resto
   * passa pelo {@link VerificadorAfirmacaoDados} normalmente. Com a pergunta inteira, o "Sim"
   * responderia ao caso dele, e o texto continua descartado (issue #170).
   *
   * @param pergunta         pergunta que a parte de lei recebe
   * @param contexto         contexto da pergunta
   * @param complementares   trechos extras (podem ser vazios)
   * @param perguntaSeparada {@code true} se {@code pergunta} é a parte legal separada
   * @return regra geral com citações, ou só as citações
   */
  public ResultadoRota responderRegraGeral(String pergunta, ContextoAssistente contexto,
      List<TrechoRecuperado> complementares, boolean perguntaSeparada) {
    return responder(pergunta, contexto, true, perguntaSeparada, complementares);
  }

  private ResultadoRota responder(String pergunta, ContextoAssistente contexto,
      boolean soRegraGeral, List<TrechoRecuperado> complementares) {
    return responder(pergunta, contexto, soRegraGeral, false, complementares);
  }

  private ResultadoRota responder(String pergunta, ContextoAssistente contexto,
      boolean soRegraGeral, boolean perguntaSeparada, List<TrechoRecuperado> complementares) {
    contexto.verificarAtiva();
    List<TrechoRecuperado> trechos =
        juntar(busca.buscar(pergunta, contexto.hoje()), complementares);
    if (trechos.isEmpty()) {
      return ResultadoRota.descartada(
          ValidadorCitacao.SEM_FUNDAMENTO, DesfechoInteracao.SEM_FUNDAMENTO_LEGAL);
    }

    String perguntaAoModelo = DataSanitizer.mascarar(pergunta);
    String sistema = prompt(soRegraGeral, contexto, trechos);
    Prompt prompt = new Prompt(List.<Message>of(
        new SystemMessage(sistema), new UserMessage(perguntaAoModelo)),
        ModeloLinguagem.comTeto(tetoSaida));
    ChatResponse resposta = contexto.chamar(modelo, prompt);
    if (ModeloLinguagem.cortadaPeloTeto(resposta, tetoSaida)) {
      // Cortada no teto (#270): a frase pela metade passaria no ValidadorCitacao se a marca veio
      // antes do corte. O texto sai; os trechos mais proximos ficam, sem afirmar que respondem.
      log.warn("Ceres: resposta de legislacao cortada pelo teto de {} tokens", tetoSaida);
      return trechosRelacionados(trechos, contexto, soRegraGeral, perguntaSeparada,
          DesfechoInteracao.RESPOSTA_CORTADA);
    }
    String texto = limpar(resposta.getResult().getOutput().getText());

    if (texto.contains(PromptsCeres.NAO_ENCONTREI)
        && !(conferencia != null && ConferenciaFidelidadeLei.temFrases(texto))) {
      // O modelo disse que os trechos nao respondem: o texto dele sai, com ou sem marca. Os mais
      // proximos ficam para consulta (#343), sem afirmar que respondem. Com a conferencia de
      // fidelidade (#269) e frases no formato ao lado, o NAO_ENCONTREI e so a parte que os trechos
      // nao respondem: este ramo nao roda, e as frases seguem para a conferencia.
      log.info("Ceres: modelo nao encontrou a resposta nos trechos; trechos relacionados");
      return trechosRelacionados(trechos, contexto, soRegraGeral, perguntaSeparada);
    }
    // Com a conferencia de fidelidade (#269) a citacao pode apontar um ancestral (o caput) que a
    // busca nao trouxe: ele entra depois dos trechos recebidos, com a marca seguinte.
    List<TrechoRecuperado> citaveis = trechos;
    ConferenciaFidelidadeLei.Resultado conferida = null;
    if (conferencia != null) {
      ConferenciaFidelidadeLei.Resultado fidelidade =
          conferencia.conferir(texto, trechos, coberturaMinima, resgataveis);
      conferida = fidelidade;
      contexto.registrarFidelidadeLei(fidelidade.desfecho());
      if (fidelidade.resgatavel()) {
        // Resgate (#269): a frase reprovada em codigo segue, e so o juiz a aceita. Se outra
        // conferencia em codigo a descartar antes dele, vale o desfecho dela.
        log.info("Ceres: resposta de legislacao com frase a resgatar pelo juiz ({})",
            fidelidade.desfecho());
      } else if (!fidelidade.aprovada()) {
        log.info("Ceres: resposta de legislacao reprovada na conferencia de fidelidade ({})",
            fidelidade.desfecho());
        return fidelidadeReprovada(fidelidade, trechos, contexto, soRegraGeral, perguntaSeparada);
      }
      texto = fidelidade.texto();
      citaveis = fidelidade.trechos();
    }
    ValidadorCitacao.Resultado citacao = validadorCitacao.validar(texto, citaveis);
    if (!citacao.valida()) {
      // Sem marca ou com marca inventada. Com marca inventada e alguma certa, os trechos certos
      // sao os mais proximos (#258); sem nenhuma certa, os mais proximos da busca (#343). Nos dois
      // casos sem o texto e sem afirmar que respondem.
      ValidadorCitacao.Resultado validas = validadorCitacao.marcasValidas(texto, citaveis);
      if (validas.citacoes().isEmpty()) {
        log.info("Ceres: resposta de legislacao sem citacao valida; trechos relacionados");
        return trechosRelacionados(trechos, contexto, soRegraGeral, perguntaSeparada);
      }
      log.info("Ceres: resposta de legislacao com marca inexistente; so os trechos validos");
      contexto.registrarTrechosCitados(validas.idsCitados());
      return new ResultadoRota(
          TRECHOS_SEM_CONFIRMACAO, DesfechoInteracao.CITACAO_INVALIDA, validas.citacoes());
    }

    List<String> fontes = new ArrayList<>();
    trechos.forEach(t -> fontes.add(t.texto()));
    fontes.add(perguntaAoModelo);
    // A resposta original (com as marcas) e a que o modelo escreveu; as referencias montadas pelo
    // backend nao entram na conferencia.
    ValidadorNumeros.Resultado numeros =
        validadorNumeros.verificar(semMarcas(texto), fontes, List.of(contexto.hoje()));
    if (!numeros.rastreavel()) {
      // A citacao e valida: o texto sai (numero sem fonte), os trechos ficam (#258), sem afirmar
      // que respondem. Antes o produtor lia "nao encontrei fundamento legal" sobre um assunto com
      // artigos recuperados.
      log.warn("Ceres: resposta de legislacao descartada com {} numero(s) fora dos trechos; so "
          + "os trechos citados", numeros.quantidadeSemFonte());
      contexto.registrarTrechosCitados(citacao.idsCitados());
      return new ResultadoRota(
          TRECHOS_SEM_CONFIRMACAO, DesfechoInteracao.NUMERO_SEM_FONTE, citacao.citacoes());
    }

    if (GuardaRegimeEspecifico.barra(pergunta, citacao.citacoes())) {
      // Todas as citacoes sao de regime especifico (LC 214, arts. 172 a 307) e a pergunta nao cita
      // o setor: o modelo respondeu a regra geral com a de um regime (#269).
      log.info("Ceres: resposta de lei so com regime especifico e a pergunta nao cita o setor");
      return descartePorRegimeEspecifico(trechos, contexto, soRegraGeral, perguntaSeparada);
    }
    // distinct: com a fidelidade (#269), o ancestral que a busca nao trouxe leva o id do trecho em
    // que a copia foi achada, e os dois podem ser citados. Sem ela os ids ja sao distintos.
    List<String> idsCitados = citacao.idsCitados().stream().distinct().toList();
    String regra = soRegraGeral && perguntaSeparada
        ? semSimOuNaoInicial(citacao.texto())
        : citacao.texto();
    if (soRegraGeral && verificadorDados.afirmaSobreDados(regra)) {
      log.info("Ceres: parte de legislacao do MISTO falou dos dados do produtor; so trechos");
      contexto.registrarTrechosCitados(idsCitados);
      return new ResultadoRota(
          SO_TRECHOS, DesfechoInteracao.AFIRMACAO_SOBRE_DADOS, citacao.citacoes());
    }
    if (juiz != null && conferida != null) {
      // Camada 2 (#269): depois de todas as conferencias em codigo, para nao gastar a chamada numa
      // resposta que elas ja descartariam. So reprova, nunca reescreve. Os trechos citados sao
      // registrados depois dele: reprovada, ficam so os que o modelo marcou.
      DesfechoFidelidadeLei veredito = juiz.julgar(conferida.frases(), contexto);
      if (conferida.resgatavel()) {
        // Resgate (#269): so o SUSTENTA de todas as frases aceita; senao (veto, falha, corte)
        // fica o motivo da camada 1, ja registrado.
        if (veredito != null) {
          log.info("Ceres: resgate negado pelo juiz da fidelidade ({}); fica {}", veredito,
              conferida.desfecho());
          return fidelidadeReprovada(conferida, trechos, contexto, soRegraGeral, perguntaSeparada);
        }
        contexto.registrarFidelidadeLei(DesfechoFidelidadeLei.APROVADA_RESGATE_JUIZ);
      } else if (veredito != null) {
        log.info("Ceres: resposta de legislacao reprovada pelo juiz da fidelidade ({})", veredito);
        contexto.registrarFidelidadeLei(veredito);
        return fidelidadeReprovada(conferida, trechos, contexto, soRegraGeral, perguntaSeparada);
      }
    }
    contexto.registrarTrechosCitados(idsCitados);
    // Depois de todas as conferencias (#269): a linha do glossario e do backend, sem numero, e nao
    // pode ser lida como parte do que o modelo afirmou.
    return ResultadoRota.aceita(LinhaGlossario.acrescentadaA(regra), citacao.citacoes());
  }

  /** O prompt de sistema: o de sempre, ou o com evidência por frase se a conferência está ligada. */
  private String prompt(boolean soRegraGeral, ContextoAssistente contexto,
      List<TrechoRecuperado> trechos) {
    if (conferencia != null) {
      return soRegraGeral
          ? PromptsCeres.sistemaLegislacaoRegraGeralComEvidencia(contexto.hoje(), formatar(trechos))
          : PromptsCeres.sistemaLegislacaoComEvidencia(contexto.hoje(), formatar(trechos));
    }
    return soRegraGeral
        ? PromptsCeres.sistemaLegislacaoRegraGeral(contexto.hoje(), formatar(trechos))
        : PromptsCeres.sistemaLegislacao(contexto.hoje(), formatar(trechos));
  }

  /**
   * Resposta reprovada na conferência de fidelidade (#269): o caminho da lei descartada de sempre
   * (como o número sem fonte, #258). O texto do modelo sai inteiro; ficam os trechos que ele marcou
   * nas evidências, sem afirmar que respondem, também no MISTO com a pergunta inteira (como o número
   * sem fonte). Sem nenhuma marca existente (formato inválido), o caminho do {@code NAO_ENCONTREI}:
   * os mais próximos da busca (#343), e no MISTO sem parte legal separada, nada da lei (#260).
   */
  private static ResultadoRota fidelidadeReprovada(ConferenciaFidelidadeLei.Resultado fidelidade,
      List<TrechoRecuperado> trechos, ContextoAssistente contexto, boolean soRegraGeral,
      boolean perguntaSeparada) {
    Map<String, TrechoRecuperado> porMarca = new LinkedHashMap<>();
    trechos.forEach(t -> porMarca.put(t.marca(), t));
    List<TrechoRecuperado> marcados = fidelidade.marcasCitadas().stream()
        .map(porMarca::get)
        .filter(Objects::nonNull)
        .toList();
    if (marcados.isEmpty()) {
      return trechosRelacionados(trechos, contexto, soRegraGeral, perguntaSeparada,
          DesfechoInteracao.FIDELIDADE_REPROVADA);
    }
    contexto.registrarTrechosCitados(marcados.stream().map(TrechoRecuperado::id).toList());
    return new ResultadoRota(TRECHOS_SEM_CONFIRMACAO, DesfechoInteracao.FIDELIDADE_REPROVADA,
        marcados.stream().map(TrechoRecuperado::comoCitacao).toList());
  }

  /**
   * Resposta de lei descartada com trechos recuperados, sem nenhuma marca válida do modelo (ou com
   * o {@code NAO_ENCONTREI}): {@link #TRECHOS_SEM_CONFIRMACAO} com os {@link #TRECHOS_RELACIONADOS}
   * mais próximos da busca, na ordem dela, em vez de "não encontrei fundamento legal" sobre um
   * assunto com artigos na base (#343). A frase não afirma que eles respondem: numa pergunta de
   * premissa falsa o descarte continua sendo o sinal de que não respondem, e o desfecho segue
   * {@code CITACAO_INVALIDA} (recusa na avaliação, como antes).
   *
   * <p>No MISTO com a pergunta inteira (o classificador não separou uma parte legal), a lei
   * descartada não entra (#260): três artigos ao lado de uma resposta de dados seriam ruído.
   */
  private static ResultadoRota trechosRelacionados(List<TrechoRecuperado> trechos,
      ContextoAssistente contexto, boolean soRegraGeral, boolean perguntaSeparada) {
    return trechosRelacionados(trechos, contexto, soRegraGeral, perguntaSeparada,
        DesfechoInteracao.CITACAO_INVALIDA);
  }

  private static ResultadoRota trechosRelacionados(List<TrechoRecuperado> trechos,
      ContextoAssistente contexto, boolean soRegraGeral, boolean perguntaSeparada,
      DesfechoInteracao desfecho) {
    if (soRegraGeral && !perguntaSeparada) {
      return ResultadoRota.descartada(ValidadorCitacao.SEM_FUNDAMENTO, desfecho);
    }
    List<TrechoRecuperado> relacionados =
        trechos.subList(0, Math.min(TRECHOS_RELACIONADOS, trechos.size()));
    contexto.registrarTrechosCitados(relacionados.stream().map(TrechoRecuperado::id).toList());
    contexto.registrarTrechosRelacionados();
    return new ResultadoRota(TRECHOS_SEM_CONFIRMACAO, desfecho,
        relacionados.stream().map(TrechoRecuperado::comoCitacao).toList());
  }

  /**
   * Resposta barrada pela {@link GuardaRegimeEspecifico}: sai o texto do modelo e os trechos de
   * regime específico (mostrar combustível ou imóvel como "o mais próximo" de uma pergunta geral
   * confundiria). Ficam, sem afirmar que respondem, os até {@link #TRECHOS_RELACIONADOS} trechos mais
   * próximos <em>fora</em> do regime específico; se não sobra nenhum, ou se é o MISTO sem parte legal
   * separada (#260), a frase de "não encontrei fundamento legal": é verdade que a regra geral não
   * veio, e é melhor que uma resposta afirmativa errada.
   */
  private static ResultadoRota descartePorRegimeEspecifico(List<TrechoRecuperado> trechos,
      ContextoAssistente contexto, boolean soRegraGeral, boolean perguntaSeparada) {
    List<TrechoRecuperado> gerais = trechos.stream()
        .filter(t -> !GuardaRegimeEspecifico.emRegimeEspecifico(t.norma(), t.dispositivo()))
        .limit(TRECHOS_RELACIONADOS)
        .toList();
    if (gerais.isEmpty() || (soRegraGeral && !perguntaSeparada)) {
      return ResultadoRota.descartada(
          ValidadorCitacao.SEM_FUNDAMENTO, DesfechoInteracao.REGIME_ESPECIFICO_SEM_SETOR);
    }
    contexto.registrarTrechosCitados(gerais.stream().map(TrechoRecuperado::id).toList());
    contexto.registrarTrechosRelacionados();
    return new ResultadoRota(TRECHOS_SEM_CONFIRMACAO, DesfechoInteracao.REGIME_ESPECIFICO_SEM_SETOR,
        gerais.stream().map(TrechoRecuperado::comoCitacao).toList());
  }

  /**
   * Tira o "Sim," ou "Não," do começo e põe a frase seguinte em maiúscula. Se nada sobrar, devolve o
   * texto como veio, e o verificador o descarta.
   */
  static String semSimOuNaoInicial(String texto) {
    java.util.regex.Matcher m = SIM_OU_NAO_INICIAL.matcher(texto);
    if (!m.find()) {
      return texto;
    }
    String resto = texto.substring(m.end()).strip();
    if (resto.isEmpty()) {
      return texto;
    }
    return Character.toUpperCase(resto.charAt(0)) + resto.substring(1);
  }

  /**
   * Trechos da busca seguidos dos complementares, sem repetir id, com as marcas refeitas na ordem
   * final — a marca é a posição na lista que o {@link ValidadorCitacao} recebe.
   */
  static List<TrechoRecuperado> juntar(
      List<TrechoRecuperado> principais, List<TrechoRecuperado> complementares) {
    Map<String, TrechoRecuperado> porId = new LinkedHashMap<>();
    for (TrechoRecuperado t : principais) {
      porId.putIfAbsent(t.id(), t);
    }
    for (TrechoRecuperado t : complementares) {
      porId.putIfAbsent(t.id(), t);
    }
    List<TrechoRecuperado> juntos = new ArrayList<>();
    for (TrechoRecuperado t : porId.values()) {
      juntos.add(new TrechoRecuperado("T" + (juntos.size() + 1), t.id(), t.norma(),
          t.dispositivo(), t.url(), t.texto(), t.similaridade()));
    }
    return juntos;
  }

  /** Trechos como o modelo os vê: só a marca e o texto, sem a referência legal. */
  static String formatar(List<TrechoRecuperado> trechos) {
    StringBuilder sb = new StringBuilder();
    for (TrechoRecuperado t : trechos) {
      sb.append('[').append(t.marca()).append("] ").append(t.texto()).append("\n\n");
    }
    return sb.toString().strip();
  }

  private static String semMarcas(String texto) {
    return texto.replaceAll("\\[\\s*T\\d+(?:\\s*[,;]\\s*T\\d+)*\\s*\\]", " ");
  }

  private static String limpar(String texto) {
    return texto == null ? "" : RACIOCINIO.matcher(texto).replaceAll("").strip();
  }
}
