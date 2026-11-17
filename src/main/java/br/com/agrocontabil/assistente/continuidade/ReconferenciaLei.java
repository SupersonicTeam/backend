package br.com.agrocontabil.assistente.continuidade;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.DataSanitizer;
import br.com.agrocontabil.assistente.DetalheLei;
import br.com.agrocontabil.assistente.FontePergunta;
import br.com.agrocontabil.assistente.GuardaRegimeEspecifico;
import br.com.agrocontabil.assistente.LinhaGlossario;
import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.ResultadoRota;
import br.com.agrocontabil.assistente.RotaLegislacao;
import br.com.agrocontabil.assistente.ValidadorNumeros;
import br.com.agrocontabil.assistente.VerificadorAfirmacaoDados;
import br.com.agrocontabil.assistente.llm.DisponibilidadeModelo;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.orquestracao.AtalhoReformaTributaria;
import br.com.agrocontabil.assistente.orquestracao.CitacoesFixas;
import br.com.agrocontabil.assistente.orquestracao.FiltroInjecao;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.assistente.orquestracao.RegrasClassificacao;
import br.com.agrocontabil.assistente.orquestracao.TrechosDasCompras;
import br.com.agrocontabil.assistente.rag.BuscaLegislacaoService;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.dto.TurnoConversa;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.DesfechoInteracao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * Reconferência de lei da continuidade (#499): "tem certeza?" depois de uma resposta de lei. A
 * mensagem do produtor nunca vai ao modelo (a dúvida o empurraria a ceder); o modelo confere a
 * resposta anterior com os trechos e só sai a confirmação ou a correção que passa nas
 * conferências. O resto vira o texto de apoio, que aponta os dispositivos sem recuar. Nenhum
 * caminho devolve {@code TRECHOS_SEM_CONFIRMACAO} nem {@code SEM_FUNDAMENTO}.
 *
 * <p>Por que "corrijo" não troca uma frase certa por uma errada: a concordância é uma segunda
 * amostra do mesmo prompt, então vem depois da âncora, da citação literal, do número novo só do
 * trecho marcado, do número antigo sem apoio e da diferença conferível, e só vale na frase que
 * cita o mesmo trecho. Cada resposta é reconferida uma vez ({@code lei.k}).
 */
@Component
public class ReconferenciaLei {

  /** Confirmação. */
  public static final String CONFIRMA = "Conferi de novo a resposta anterior com o texto da lei, "
      + "e ela se confirma.";

  /** Fundamento de cada confirmação ou correção. */
  public static final String O_QUE_DIZ = "O que diz a lei (%s): “%s”";

  /** Uma correção. */
  public static final String CORRIJO_UM = "Conferi de novo a resposta anterior com o texto da lei "
      + "e corrijo um ponto:";

  /** Duas correções. */
  public static final String CORRIJO_DOIS = "Conferi de novo a resposta anterior com o texto da "
      + "lei e corrijo dois pontos:";

  /** Item de correção. */
  public static final String CORRECAO = "- Onde eu disse “%s”, o certo é: %s. " + O_QUE_DIZ;

  /** Fim de toda saída da reconferência. */
  public static final String ENQUADRAMENTO = "O enquadramento do seu caso é com o seu contador.";

  /** Apoio com dispositivos. */
  public static final String APOIO = "A resposta anterior se baseia em %s. O texto de cada "
      + "dispositivo está abaixo, com o link.";

  /** Apoio sem dispositivo resolvido. */
  public static final String APOIO_SEM_DISPOSITIVO = "A resposta anterior se baseia nos "
      + "dispositivos citados nela.";

  /** Resposta que já passou por uma reconferência. */
  public static final String JA_RECONFERIDA = "Essa resposta já passou por uma nova conferência "
      + "com o texto da lei. Ela se baseia em %s, com o texto abaixo.";

  /** Atalho de lei. */
  public static final String ATALHO = "Essa explicação é um texto revisado pela equipe do "
      + "AgroConIA e se baseia nos dispositivos citados abaixo, com o link.";

  /** Resposta anterior só com os trechos. */
  public static final String SO_TRECHOS = "A resposta anterior trouxe os trechos de lei que "
      + "tratam do assunto, sem afirmar uma regra sobre o seu caso. Eles estão abaixo para "
      + "consulta.";

  /** Resposta anterior sem confirmação. */
  public static final String SEM_CONFIRMACAO = "A resposta anterior não afirmou uma regra: ela "
      + "trouxe os trechos de lei mais próximos da pergunta, que estão abaixo para consulta.";

  /** Resposta anterior sem fundamento. */
  public static final String SEM_FUNDAMENTO = "A resposta anterior não encontrou fundamento na "
      + "base de legislação que eu consulto.";

  static final int MAXIMO_TRECHOS = 8;
  static final int ORCAMENTO_TRECHOS = 24_000;
  static final int MAXIMO_FRASE = 400;
  static final int PALAVRAS_MINIMAS_CITACAO = 6;
  static final int PALAVRAS_MAXIMAS_CITACAO = 40;
  static final int PALAVRAS_MINIMAS_ANCORA = 5;

  private static final Logger log = LoggerFactory.getLogger(ReconferenciaLei.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Grupo de referências que a {@code ValidadorCitacao} monta: "(LC 214/2025, art. 137; ...)". */
  private static final Pattern GRUPO = Pattern.compile("\\(([^()]*)\\)");
  private static final Pattern NORMA = Pattern.compile("^(LC|EC|Lei) [\\d./]+$");
  private static final Pattern MARCA = Pattern.compile("\\[\\s*(T\\d+(?:\\s*[,;]\\s*T\\d+)*)\\s*\\]");
  private static final Pattern NUMERO_MARCA = Pattern.compile("T(\\d+)");
  private static final Pattern ANOTACAO = Pattern.compile(
      "\\((?:Reda[cç][aã]o dada|Inclu[ií]d[oa]|Vide|Vig[eê]ncia|Revogad[oa])[^)]*\\)",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern SIM_OU_NAO = Pattern.compile(
      "^[\\s\\p{Punct}\\u2013\\u2014]*(sim(?=[\\s\\p{Punct}\\u2013\\u2014]|$)"
          + "|n[a\\u00e3]o[*_]*\\s*[,.;:!\\u2013\\u2014-])",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern FIM_DE_FRASE = Pattern.compile("(?<=[.!?])\\s+");
  private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:\\.\\p{N}+)*");

  private final ModeloLinguagem modelo;
  private final BuscaLegislacaoService busca;
  private final BuscaTextualLegislacaoService buscaTextual;
  private final ValidadorCitacao validadorCitacao;
  private final ValidadorNumeros validadorNumeros;
  private final VerificadorAfirmacaoDados verificadorDados;
  private final RotaLegislacao rotaLegislacao;
  private final TrechosDasCompras trechosDasCompras;
  private final DisponibilidadeModelo disponibilidade;
  private final int teto;

  /** Constrói a reconferência; o teto é {@code app.assistente.tetos.reconferencia}. */
  public ReconferenciaLei(ModeloLinguagem modelo, BuscaLegislacaoService busca,
      BuscaTextualLegislacaoService buscaTextual, ValidadorCitacao validadorCitacao,
      ValidadorNumeros validadorNumeros, VerificadorAfirmacaoDados verificadorDados,
      RotaLegislacao rotaLegislacao, TrechosDasCompras trechosDasCompras,
      DisponibilidadeModelo disponibilidade, AssistenteProperties propriedades) {
    this.modelo = modelo;
    this.busca = busca;
    this.buscaTextual = buscaTextual;
    this.validadorCitacao = validadorCitacao;
    this.validadorNumeros = validadorNumeros;
    this.verificadorDados = verificadorDados;
    this.rotaLegislacao = rotaLegislacao;
    this.trechosDasCompras = trechosDasCompras;
    this.disponibilidade = disponibilidade;
    this.teto = propriedades.getTetos().getReconferencia();
  }

  /**
   * O resultado.
   *
   * @param rota            texto e citações da parte de lei
   * @param desfecho        desfecho da continuidade
   * @param modeloRespondeu o modelo respondeu (o par novo leva {@code lei.k}); sem ele, o turno é
   *                        o par de origem
   */
  public record Resultado(ResultadoRota rota, DesfechoContinuidade desfecho,
      boolean modeloRespondeu) {}

  /** Um fundamento lido do modelo. */
  record Fundamento(String marca, String citacao) {}

  /** Uma correção lida do modelo. */
  record Correcao(String fraseAnterior, String fraseCorrigida, Fundamento fundamento) {}

  /** O que o modelo recebe. */
  record MensagemReconferencia(String pergunta, String respostaAnterior) {}

  /**
   * Reconfere a resposta de lei do par.
   *
   * @param par      último par ({@code LEGISLACAO} ou {@code MISTO} com lei)
   * @param contexto contexto da pergunta
   * @return a parte de lei
   */
  public Resultado reconferir(ParConferido par, ContextoAssistente contexto) {
    if (DecisaoContinuidade.leiPorTextoFixo(par)) {
      return textoFixo(par, contexto);
    }
    List<String> dispositivos = dispositivosDaFatia(par);
    if (!disponibilidade.disponivel() || dispositivos.isEmpty()) {
      return apoio(par, contexto, dispositivos, DesfechoContinuidade.RECONFERENCIA_SEM_MODELO,
          false);
    }
    boolean misto = par.carga().rota() == Intencao.MISTO;
    String perguntaLei = misto && par.carga().lei().perguntaLei() != null
        ? par.carga().lei().perguntaLei() : par.pergunta();
    contexto.verificarAtiva();
    List<TrechoRecuperado> citados;
    List<TrechoRecuperado> trechos;
    try {
      citados = buscaTextual.buscarDispositivos(dispositivos, contexto.hoje());
      List<TrechoRecuperado> complementares = new ArrayList<>(
          busca.buscar(perguntaLei, contexto.hoje()));
      if (misto) {
        complementares.addAll(trechosDasCompras.buscar(contexto));
      }
      trechos = limitar(RotaLegislacao.juntar(citados, complementares));
    } catch (AssistenteIndisponivelException e) {
      contexto.verificarAtiva();
      return apoio(par, contexto, dispositivos, DesfechoContinuidade.RECONFERENCIA_SEM_MODELO,
          false);
    }
    if (citados.isEmpty()) {
      return apoio(par, contexto, dispositivos, DesfechoContinuidade.RECONFERENCIA_SEM_MODELO,
          false);
    }
    Set<String> marcasCitadas = new HashSet<>();
    Map<String, TrechoRecuperado> porMarca = new LinkedHashMap<>();
    Set<String> idsCitados = new HashSet<>();
    citados.forEach(t -> idsCitados.add(t.id()));
    for (TrechoRecuperado t : trechos) {
      porMarca.put(t.marca(), t);
      if (idsCitados.contains(t.id())) {
        marcasCitadas.add(t.marca());
      }
    }
    String fatia = par.carga().lei().textoModelo().de(par.resposta());
    String marcada = comMarcas(fatia, trechos);
    String json;
    try {
      json = JSON.writeValueAsString(new MensagemReconferencia(
          DataSanitizer.mascarar(perguntaLei), DataSanitizer.mascarar(marcada)));
    } catch (JsonProcessingException e) {
      return apoio(par, contexto, dispositivos, DesfechoContinuidade.RECONFERENCIA_SEM_MODELO,
          false);
    }
    Prompt prompt = new Prompt(List.<Message>of(
        new SystemMessage(PromptsCeres.sistemaReconferenciaLei(contexto.hoje(),
            RotaLegislacao.formatar(trechos))),
        new UserMessage(json)), ModeloLinguagem.comTeto(teto));
    ChatResponse resposta;
    try {
      resposta = contexto.chamar(modelo, prompt);
    } catch (AssistenteIndisponivelException e) {
      contexto.verificarAtiva();
      return apoio(par, contexto, dispositivos, DesfechoContinuidade.RECONFERENCIA_SEM_MODELO,
          false);
    }
    if (ModeloLinguagem.cortadaPeloTeto(resposta, teto)) {
      return apoio(par, contexto, dispositivos,
          DesfechoContinuidade.RECONFERENCIA_DESCARTADA_FORMATO, true);
    }
    Optional<JsonNode> veredito = ler(resposta.getResult().getOutput().getText());
    if (veredito.isEmpty()) {
      return apoio(par, contexto, dispositivos,
          DesfechoContinuidade.RECONFERENCIA_DESCARTADA_FORMATO, true);
    }
    String tipo = veredito.get().path("veredito").asText("");
    Conferencia conferencia = new Conferencia(par, perguntaLei, fatia, marcada, trechos,
        porMarca, marcasCitadas, misto, contexto);
    return switch (tipo) {
      case "CONFIRMO" -> conferencia.confirmo(veredito.get(), dispositivos);
      case "CORRIJO" -> conferencia.corrijo(veredito.get(), dispositivos);
      case "NAO_SUSTENTA" -> apoio(par, contexto, dispositivos,
          DesfechoContinuidade.RECONFERENCIA_NAO_SUSTENTA, true);
      default -> apoio(par, contexto, dispositivos,
          DesfechoContinuidade.RECONFERENCIA_DESCARTADA_FORMATO, true);
    };
  }

  /** As conferências de uma resposta do modelo. */
  private final class Conferencia {

    private final ParConferido par;
    private final String perguntaLei;
    private final String fatia;
    private final String marcada;
    private final List<TrechoRecuperado> trechos;
    private final Map<String, TrechoRecuperado> porMarca;
    private final Set<String> marcasCitadas;
    private final boolean misto;
    private final ContextoAssistente contexto;

    Conferencia(ParConferido par, String perguntaLei, String fatia, String marcada,
        List<TrechoRecuperado> trechos, Map<String, TrechoRecuperado> porMarca,
        Set<String> marcasCitadas, boolean misto, ContextoAssistente contexto) {
      this.par = par;
      this.perguntaLei = perguntaLei;
      this.fatia = fatia;
      this.marcada = marcada;
      this.trechos = trechos;
      this.porMarca = porMarca;
      this.marcasCitadas = marcasCitadas;
      this.misto = misto;
      this.contexto = contexto;
    }

    Resultado confirmo(JsonNode no, List<String> dispositivos) {
      JsonNode lista = no.path("fundamentos");
      if (!lista.isArray() || lista.isEmpty() || lista.size() > 3) {
        return descartar(dispositivos, DesfechoContinuidade.RECONFERENCIA_DESCARTADA_FORMATO);
      }
      List<String> linhas = new ArrayList<>();
      List<CitacaoLegal> citacoes = new ArrayList<>();
      List<String> pedacos = new ArrayList<>();
      for (JsonNode item : lista) {
        Optional<Fundamento> fundamento = fundamento(item);
        if (fundamento.isEmpty()) {
          return descartar(dispositivos, DesfechoContinuidade.RECONFERENCIA_DESCARTADA_FORMATO);
        }
        Fundamento f = fundamento.get();
        if (!marcasCitadas.contains(f.marca())) {
          return descartar(dispositivos, DesfechoContinuidade.RECONFERENCIA_DESCARTADA_CITACAO);
        }
        TrechoRecuperado trecho = porMarca.get(f.marca());
        Optional<String> pedaco = pedacoLiteral(f.citacao(), trecho.texto());
        if (pedaco.isEmpty()) {
          return descartar(dispositivos, DesfechoContinuidade.RECONFERENCIA_DESCARTADA_CITACAO);
        }
        pedacos.add(pedaco.get());
        linhas.add(O_QUE_DIZ.formatted(trecho.comoCitacao().referencia(), pedaco.get()));
        citacoes.add(trecho.comoCitacao());
      }
      if (!numerosDaFatiaNosTrechos() || !numerosDaFatiaNasCitacoes(pedacos)) {
        return descartar(dispositivos, DesfechoContinuidade.RECONFERENCIA_DESCARTADA_NUMERO);
      }
      String texto = CONFIRMA + "\n\n" + String.join("\n", linhas) + "\n\n" + ENQUADRAMENTO;
      return saida(texto, citacoes, DesfechoContinuidade.RECONFERENCIA_CONFIRMOU);
    }

    Resultado corrijo(JsonNode no, List<String> dispositivos) {
      JsonNode lista = no.path("correcoes");
      if (!lista.isArray() || lista.isEmpty() || lista.size() > 2) {
        return descartar(dispositivos, DesfechoContinuidade.RECONFERENCIA_DESCARTADA_FORMATO);
      }
      List<String> itens = new ArrayList<>();
      List<CitacaoLegal> citacoes = new ArrayList<>();
      for (JsonNode item : lista) {
        String anterior = texto(item.get("fraseAnterior"));
        String corrigida = texto(item.get("fraseCorrigida"));
        Optional<Fundamento> fundamento = fundamento(item.get("fundamento"));
        if (anterior == null || corrigida == null || fundamento.isEmpty()
            || anterior.length() > MAXIMO_FRASE || corrigida.length() > MAXIMO_FRASE
            || FiltroInjecao.pareceInjecao(anterior) || FiltroInjecao.pareceInjecao(corrigida)) {
          return descartar(dispositivos, DesfechoContinuidade.RECONFERENCIA_DESCARTADA_FORMATO);
        }
        Fundamento f = fundamento.get();
        TrechoRecuperado trecho = porMarca.get(f.marca());
        if (trecho == null) {
          return descartar(dispositivos, DesfechoContinuidade.RECONFERENCIA_DESCARTADA_CITACAO);
        }
        // a. ancora
        Optional<String> original = ancora(anterior, fatia);
        if (original.isEmpty()) {
          return descartar(dispositivos, DesfechoContinuidade.RECONFERENCIA_DESCARTADA_ANCORA);
        }
        // citacao literal do fundamento
        Optional<String> pedaco = pedacoLiteral(f.citacao(), trecho.texto());
        if (pedaco.isEmpty()) {
          return descartar(dispositivos, DesfechoContinuidade.RECONFERENCIA_DESCARTADA_CITACAO);
        }
        // b. citacao valida na frase, com a marca do fundamento
        ValidadorCitacao.Resultado validacao = validadorCitacao.validar(corrigida, trechos);
        if (!validacao.valida() || !marcas(corrigida).contains(f.marca())) {
          return descartar(dispositivos, DesfechoContinuidade.RECONFERENCIA_DESCARTADA_CITACAO);
        }
        // c. numero da frase corrigida so dos trechos marcados nela
        List<String> fontes = marcas(corrigida).stream().map(porMarca::get)
            .filter(java.util.Objects::nonNull).map(t -> semAnotacoes(t.texto())).toList();
        String corrigidaSemMarcas = RotaLegislacao.semMarcas(corrigida).strip();
        if (!validadorNumeros.verificar(corrigidaSemMarcas, fontes).rastreavel()) {
          return descartar(dispositivos, DesfechoContinuidade.RECONFERENCIA_DESCARTADA_NUMERO);
        }
        // d. dados do produtor
        if (verificadorDados.afirmaSobreDados(
            RotaLegislacao.semSimOuNaoInicial(corrigidaSemMarcas))) {
          return descartar(dispositivos, DesfechoContinuidade.RECONFERENCIA_DESCARTADA_DADOS);
        }
        // e. regime especifico
        if (GuardaRegimeEspecifico.barra(perguntaLei, validacao.citacoes())) {
          return descartar(dispositivos, DesfechoContinuidade.RECONFERENCIA_DESCARTADA_REGIME);
        }
        // f. diferenca conferivel
        Set<BigDecimal> antes = ValidadorNumeros.valoresMencionados(
            RotaLegislacao.semMarcas(anterior));
        Set<BigDecimal> depois = ValidadorNumeros.valoresMencionados(corrigidaSemMarcas);
        Set<BigDecimal> naCitacao = ValidadorNumeros.valoresMencionados(pedaco.get());
        Set<BigDecimal> novos = new HashSet<>(depois);
        novos.removeAll(antes);
        novos.retainAll(naCitacao);
        Set<BigDecimal> antigos = new HashSet<>(antes);
        antigos.removeAll(depois);
        boolean mudaNumero = !novos.isEmpty() && !antigos.isEmpty();
        String polaridadeAntes = polaridade(anterior);
        String polaridadeDepois = polaridade(corrigida);
        boolean mudaPolaridade = !misto && polaridadeAntes != null && polaridadeDepois != null
            && !polaridadeAntes.equals(polaridadeDepois);
        if (!mudaNumero && !mudaPolaridade) {
          return descartar(dispositivos, DesfechoContinuidade.RECONFERENCIA_DESCARTADA_DIFERENCA);
        }
        // g. numero antigo com apoio literal no trecho que a frase anterior citava
        if (mudaNumero && antigoComApoio(anterior, antigos)) {
          return descartar(dispositivos,
              DesfechoContinuidade.RECONFERENCIA_DESCARTADA_APOIO_LITERAL);
        }
        // h. concordancia
        if (!concorda(trecho.id(), novos, antigos, mudaPolaridade ? polaridadeDepois : null)) {
          return descartar(dispositivos,
              DesfechoContinuidade.RECONFERENCIA_DESCARTADA_CONCORDANCIA);
        }
        String comReferencias = validacao.texto().strip().replaceAll("[.\\s]+$", "");
        itens.add(CORRECAO.formatted(original.get(), comReferencias,
            trecho.comoCitacao().referencia(), pedaco.get()));
        citacoes.addAll(validacao.citacoes());
      }
      String abertura = itens.size() == 1 ? CORRIJO_UM : CORRIJO_DOIS;
      String texto = abertura + "\n" + String.join("\n", itens) + "\n\n" + ENQUADRAMENTO;
      return saida(texto, citacoes, DesfechoContinuidade.RECONFERENCIA_CORRIGIU);
    }

    private Resultado saida(String texto, List<CitacaoLegal> daSaida,
        DesfechoContinuidade desfecho) {
      List<CitacaoLegal> anteriores = new ArrayList<>();
      for (TrechoRecuperado t : trechos) {
        if (marcasCitadas.contains(t.marca())) {
          anteriores.add(t.comoCitacao());
        }
      }
      List<CitacaoLegal> citacoes = CitacoesFixas.juntar(daSaida, anteriores);
      registrarParNovo(par, contexto, citacoes);
      log.info("Ceres: {}", desfecho);
      return new Resultado(new ResultadoRota(LinhaGlossario.acrescentadaA(texto),
          DesfechoInteracao.RESPONDIDA, citacoes), desfecho, true);
    }

    private Resultado descartar(List<String> dispositivos, DesfechoContinuidade motivo) {
      return apoio(par, contexto, dispositivos, motivo, true);
    }

    /** (b) do CONFIRMO: cada número de cada frase está no trecho que ela cita. */
    private boolean numerosDaFatiaNosTrechos() {
      for (String frase : frases(marcada)) {
        Set<BigDecimal> numeros = ValidadorNumeros.valoresMencionados(semGrupos(
            RotaLegislacao.semMarcas(frase)));
        if (numeros.isEmpty()) {
          continue;
        }
        Set<BigDecimal> doTrecho = new HashSet<>();
        for (String marca : marcas(frase)) {
          TrechoRecuperado t = porMarca.get(marca);
          if (t != null) {
            doTrecho.addAll(ValidadorNumeros.valoresMencionados(semAnotacoes(t.texto())));
          }
        }
        if (!doTrecho.containsAll(numeros)) {
          return false;
        }
      }
      return true;
    }

    /** (c) do CONFIRMO: números da fatia nas citações; sem número, 2 palavras de conteúdo. */
    private boolean numerosDaFatiaNasCitacoes(List<String> pedacos) {
      Set<BigDecimal> daFatia = ValidadorNumeros.valoresMencionados(semGrupos(
          RotaLegislacao.semMarcas(marcada)));
      if (!daFatia.isEmpty()) {
        Set<BigDecimal> nasCitacoes = new HashSet<>();
        pedacos.forEach(p -> nasCitacoes.addAll(ValidadorNumeros.valoresMencionados(p)));
        return nasCitacoes.containsAll(daFatia);
      }
      List<String> conteudoFatia = TextoContinuidade.palavras(TextoContinuidade.normalizar(
          RotaLegislacao.semMarcas(marcada))).stream().filter(TextoContinuidade::conteudo).toList();
      for (String pedaco : pedacos) {
        long comuns = TextoContinuidade.palavras(TextoContinuidade.normalizar(pedaco)).stream()
            .filter(TextoContinuidade::conteudo).distinct()
            .filter(p -> TextoContinuidade.contemPorPrefixo(conteudoFatia, p)).count();
        if (comuns < 2) {
          return false;
        }
      }
      return true;
    }

    /** (g) o número antigo está no trecho que a frase anterior citava. */
    private boolean antigoComApoio(String anterior, Set<BigDecimal> antigos) {
      Set<String> marcasAnterior = new HashSet<>(marcas(anterior));
      if (marcasAnterior.isEmpty()) {
        String alvo = TextoContinuidade.normalizar(RotaLegislacao.semMarcas(anterior));
        for (String frase : frases(marcada)) {
          if (TextoContinuidade.normalizar(RotaLegislacao.semMarcas(frase)).contains(alvo)) {
            marcasAnterior.addAll(marcas(frase));
          }
        }
      }
      for (String marca : marcasAnterior) {
        TrechoRecuperado t = porMarca.get(marca);
        if (t != null) {
          Set<BigDecimal> doTrecho = ValidadorNumeros.valoresMencionados(semAnotacoes(t.texto()));
          for (BigDecimal antigo : antigos) {
            if (doTrecho.contains(antigo)) {
              return true;
            }
          }
        }
      }
      return false;
    }

    /**
     * (h) a rota de lei normal, sobre a mesma pergunta e sem a resposta anterior nem a dúvida, é
     * aceita e a frase dela que cita o mesmo trecho tem o ponto novo e não o antigo.
     */
    private boolean concorda(String idTrecho, Set<BigDecimal> novos, Set<BigDecimal> antigos,
        String polaridadeNova) {
      FontePergunta fonte = par.carga().reescrita()
          ? FontePergunta.dosArgumentos() : FontePergunta.texto(perguntaLei);
      ResultadoRota rota;
      try {
        rota = misto
            ? rotaLegislacao.responderRegraGeral(perguntaLei, contexto,
                trechosDasCompras.buscar(contexto), par.carga().lei().perguntaLei() != null, fonte)
            : rotaLegislacao.responder(perguntaLei, contexto, fonte);
      } catch (AssistenteIndisponivelException e) {
        contexto.verificarAtiva();
        return false;
      }
      DetalheLei detalhe = rota.detalheLei();
      if (!rota.aceita() || detalhe == null) {
        return false;
      }
      if (polaridadeNova != null && !polaridadeNova.equals(polaridade(detalhe.textoComMarcas()))) {
        return false;
      }
      Set<String> marcasDoTrecho = new HashSet<>();
      for (TrechoRecuperado t : detalhe.trechos()) {
        if (t.id().equals(idTrecho)) {
          marcasDoTrecho.add(t.marca());
        }
      }
      if (marcasDoTrecho.isEmpty()) {
        return false;
      }
      if (novos.isEmpty()) {
        return polaridadeNova != null;
      }
      for (String frase : frases(detalhe.textoComMarcas())) {
        if (marcas(frase).stream().anyMatch(marcasDoTrecho::contains)) {
          Set<BigDecimal> numeros = ValidadorNumeros.valoresMencionados(
              RotaLegislacao.semMarcas(frase));
          boolean temNovo = numeros.stream().anyMatch(novos::contains);
          boolean temAntigo = numeros.stream().anyMatch(antigos::contains);
          if (temNovo && !temAntigo) {
            return true;
          }
        }
      }
      return false;
    }
  }

  // --- textos fixos e apoio ---

  /** Os textos fixos de lei, sem modelo: o turno é o par de origem. */
  Resultado textoFixo(ParConferido par, ContextoAssistente contexto) {
    LeiAssinada lei = par.carga().lei();
    List<String> dispositivos = lei == null ? List.of() : dispositivosDaFatia(par);
    String texto;
    List<String> paraCitar = dispositivos;
    if (lei == null) {
      return apoio(par, contexto, List.of(), DesfechoContinuidade.RECONFERENCIA_TEXTO_FIXO,
          false);
    } else if (lei.atalho() != null) {
      texto = ATALHO;
      paraCitar = AtalhoReformaTributaria.dispositivosFixos();
    } else if (lei.classe() == LeiAssinada.Classe.SEM_FUNDAMENTO) {
      texto = SEM_FUNDAMENTO;
      paraCitar = List.of();
    } else if (lei.classe() == LeiAssinada.Classe.SEM_CONFIRMACAO) {
      texto = SEM_CONFIRMACAO;
    } else if (lei.textoModelo() == null) {
      texto = SO_TRECHOS;
    } else if (lei.reconferida()) {
      List<CitacaoLegal> citacoes = citacoes(dispositivos, contexto);
      texto = citacoes.isEmpty() ? APOIO_SEM_DISPOSITIVO
          : JA_RECONFERIDA.formatted(referencias(citacoes));
      return fixo(par, contexto, texto, citacoes);
    } else {
      return apoio(par, contexto, dispositivos, DesfechoContinuidade.RECONFERENCIA_TEXTO_FIXO,
          false);
    }
    return fixo(par, contexto, texto, citacoes(paraCitar, contexto));
  }

  private Resultado fixo(ParConferido par, ContextoAssistente contexto, String texto,
      List<CitacaoLegal> citacoes) {
    devolverOrigem(par, contexto);
    log.info("Ceres: {}", DesfechoContinuidade.RECONFERENCIA_TEXTO_FIXO);
    return new Resultado(new ResultadoRota(texto + " " + ENQUADRAMENTO,
        DesfechoInteracao.RESPONDIDA, citacoes), DesfechoContinuidade.RECONFERENCIA_TEXTO_FIXO,
        false);
  }

  /**
   * O apoio: aponta os dispositivos da resposta anterior, sem recuar. Com o modelo tendo
   * respondido, o par novo leva {@code lei.k}; sem ele, o turno é o par de origem.
   */
  Resultado apoio(ParConferido par, ContextoAssistente contexto, List<String> dispositivos,
      DesfechoContinuidade motivo, boolean modeloRespondeu) {
    List<CitacaoLegal> citacoes = citacoes(dispositivos, contexto);
    String texto = (citacoes.isEmpty() ? APOIO_SEM_DISPOSITIVO
        : APOIO.formatted(referencias(citacoes))) + " " + ENQUADRAMENTO;
    if (modeloRespondeu) {
      registrarParNovo(par, contexto, citacoes);
    } else {
      devolverOrigem(par, contexto);
    }
    log.info("Ceres: {}", motivo);
    return new Resultado(new ResultadoRota(texto, DesfechoInteracao.RECONFERENCIA_DESCARTADA,
        citacoes), motivo, modeloRespondeu);
  }

  private static void devolverOrigem(ParConferido par, ContextoAssistente contexto) {
    if (par.carga().rota() == Intencao.LEGISLACAO) {
      contexto.registroTurno().devolverOrigem(
          new TurnoConversa(par.pergunta(), par.resposta(), par.assinatura()));
    } else {
      registrarParNovo(par, contexto, List.of());
    }
  }

  /** O par novo de uma reconferência: validade herdada, {@code lei.k} e as citações em {@code c}. */
  private static void registrarParNovo(ParConferido par, ContextoAssistente contexto,
      List<CitacaoLegal> citacoes) {
    RegistroTurno registro = contexto.registroTurno();
    registro.herdarValidade(par.carga().exp());
    registro.marcarReconferida();
    registro.registrarLei(LeiAssinada.Classe.CONFIRMADA, null,
        citacoes.stream().map(c -> c.norma() + "|" + c.dispositivo()).toList());
  }

  private List<CitacaoLegal> citacoes(List<String> dispositivos, ContextoAssistente contexto) {
    return dispositivos.isEmpty() ? List.of()
        : CitacoesFixas.buscar(buscaTextual, dispositivos, contexto, "Reconferencia de lei");
  }

  // --- utilitarios (pacote, para os testes) ---

  /**
   * Os dispositivos que a resposta anterior citou: os grupos de referência da fatia {@code lei.r}
   * (autenticada pelo MAC) ou, sem ela, {@code lei.c}.
   */
  static List<String> dispositivosDaFatia(ParConferido par) {
    LeiAssinada lei = par.carga().lei();
    if (lei == null) {
      return List.of();
    }
    if (lei.textoModelo() == null) {
      return lei.citacoes() == null ? List.of() : lei.citacoes();
    }
    List<String> dispositivos = new ArrayList<>();
    Matcher grupos = GRUPO.matcher(lei.textoModelo().de(par.resposta()));
    while (grupos.find()) {
      for (String chave : referenciasDoGrupo(grupos.group(1))) {
        if (!dispositivos.contains(chave)) {
          dispositivos.add(chave);
        }
      }
    }
    return dispositivos;
  }

  /** "LC 214/2025, art. 137; LC 214/2025, art. 138" → {@code norma|dispositivo}. */
  static List<String> referenciasDoGrupo(String grupo) {
    List<String> chaves = new ArrayList<>();
    for (String item : grupo.split(";")) {
      String referencia = item.strip();
      int virgula = referencia.indexOf(", ");
      if (virgula <= 0) {
        return List.of();
      }
      String norma = referencia.substring(0, virgula).strip();
      String dispositivo = referencia.substring(virgula + 2).strip();
      if (!NORMA.matcher(norma).matches() || dispositivo.isEmpty()) {
        return List.of();
      }
      chaves.add(norma + "|" + dispositivo);
    }
    return chaves;
  }

  /** A fatia com cada grupo de referência trocado pela marca do trecho; o que não resolve fica. */
  static String comMarcas(String fatia, List<TrechoRecuperado> trechos) {
    Map<String, String> marcaPorChave = new HashMap<>();
    for (TrechoRecuperado t : trechos) {
      marcaPorChave.putIfAbsent(t.norma() + "|" + t.dispositivo(), t.marca());
    }
    StringBuilder saida = new StringBuilder();
    Matcher grupos = GRUPO.matcher(fatia);
    while (grupos.find()) {
      List<String> chaves = referenciasDoGrupo(grupos.group(1));
      List<String> marcas = chaves.stream().map(marcaPorChave::get).toList();
      String troca = chaves.isEmpty() || marcas.contains(null)
          ? grupos.group() : "[" + String.join(", ", marcas) + "]";
      grupos.appendReplacement(saida, Matcher.quoteReplacement(troca));
    }
    grupos.appendTail(saida);
    return saida.toString();
  }

  /** Até {@value #MAXIMO_TRECHOS} trechos e uns {@value #ORCAMENTO_TRECHOS} caracteres. */
  static List<TrechoRecuperado> limitar(List<TrechoRecuperado> trechos) {
    List<TrechoRecuperado> limitados = new ArrayList<>();
    int total = 0;
    for (TrechoRecuperado t : trechos) {
      if (limitados.size() == MAXIMO_TRECHOS
          || (!limitados.isEmpty() && total + t.texto().length() > ORCAMENTO_TRECHOS)) {
        break;
      }
      limitados.add(t);
      total += t.texto().length();
    }
    return limitados;
  }

  /**
   * O pedaço literal do trecho que a citação copia: de 6 a 40 palavras, contínuo (NFC, sem caixa,
   * espaços reduzidos), fora das anotações entre parênteses; devolve o pedaço tirado do trecho,
   * com o acento e a caixa originais.
   */
  static Optional<String> pedacoLiteral(String citacao, String trecho) {
    if (citacao == null || trecho == null) {
      return Optional.empty();
    }
    String[] palavras = Normalizer.normalize(citacao, Normalizer.Form.NFC).strip().split("\\s+");
    if (palavras.length < PALAVRAS_MINIMAS_CITACAO || palavras.length > PALAVRAS_MAXIMAS_CITACAO
        || FiltroInjecao.pareceInjecao(citacao)) {
      return Optional.empty();
    }
    StringBuilder regex = new StringBuilder("(?<![\\p{L}\\p{N}])");
    for (int i = 0; i < palavras.length; i++) {
      if (i > 0) {
        regex.append("\\s+");
      }
      regex.append(Pattern.quote(palavras[i]));
    }
    regex.append("(?![\\p{L}\\p{N}])");
    String nfc = Normalizer.normalize(trecho, Normalizer.Form.NFC);
    String semAnotacoes = bloquearAnotacoes(nfc);
    Matcher m = Pattern.compile(regex.toString(),
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(semAnotacoes);
    return m.find() ? Optional.of(nfc.substring(m.start(), m.end()).replaceAll("\\s+", " "))
        : Optional.empty();
  }

  /** As anotações viram um caractere que nenhuma palavra casa, com o mesmo tamanho. */
  private static String bloquearAnotacoes(String texto) {
    StringBuilder sb = new StringBuilder(texto);
    Matcher m = ANOTACAO.matcher(texto);
    while (m.find()) {
      for (int i = m.start(); i < m.end(); i++) {
        sb.setCharAt(i, '\u0001');
      }
    }
    return sb.toString();
  }

  static String semAnotacoes(String texto) {
    return ANOTACAO.matcher(texto).replaceAll(" ");
  }

  /** Os grupos de referência que sobraram (não resolvidos) saem da conta de números. */
  private static String semGrupos(String texto) {
    return GRUPO.matcher(texto).replaceAll(" ");
  }

  /**
   * A âncora: a frase anterior, normalizada e sem marcas, é um pedaço contínuo de 5 palavras ou
   * mais da fatia; devolve o pedaço original da resposta entregue (sem as referências).
   */
  static Optional<String> ancora(String fraseAnterior, String fatia) {
    List<String> alvo = TextoContinuidade.palavras(
        RegrasClassificacao.normalizar(RotaLegislacao.semMarcas(fraseAnterior)));
    if (alvo.size() < PALAVRAS_MINIMAS_ANCORA) {
      return Optional.empty();
    }
    String semReferencias = semReferencias(fatia);
    List<int[]> posicoes = new ArrayList<>();
    List<String> normalizados = new ArrayList<>();
    Matcher token = TOKEN.matcher(semReferencias);
    while (token.find()) {
      String normal = RegrasClassificacao.normalizar(token.group());
      for (String parte : TextoContinuidade.palavras(normal)) {
        normalizados.add(parte);
        posicoes.add(new int[] {token.start(), token.end()});
      }
    }
    for (int i = 0; i + alvo.size() <= normalizados.size(); i++) {
      if (normalizados.subList(i, i + alvo.size()).equals(alvo)) {
        int inicio = posicoes.get(i)[0];
        int fim = posicoes.get(i + alvo.size() - 1)[1];
        return Optional.of(semReferencias.substring(inicio, fim).replaceAll("\\s+", " "));
      }
    }
    return Optional.empty();
  }

  /** Os grupos de referência viram espaços do mesmo tamanho (as posições ficam). */
  private static String semReferencias(String fatia) {
    StringBuilder sb = new StringBuilder(fatia);
    Matcher grupos = GRUPO.matcher(fatia);
    while (grupos.find()) {
      if (!referenciasDoGrupo(grupos.group(1)).isEmpty()) {
        for (int i = grupos.start(); i < grupos.end(); i++) {
          sb.setCharAt(i, ' ');
        }
      }
    }
    return sb.toString();
  }

  /** As frases do texto marcado; marca solta no começo de uma frase volta à anterior. */
  static List<String> frases(String texto) {
    List<String> frases = new ArrayList<>();
    for (String parte : FIM_DE_FRASE.split(texto.strip())) {
      Matcher inicio = Pattern.compile("^(\\s*" + MARCA.pattern() + ")+").matcher(parte);
      if (inicio.find() && !frases.isEmpty()) {
        int ultima = frases.size() - 1;
        frases.set(ultima, frases.get(ultima) + " " + inicio.group().strip());
        parte = parte.substring(inicio.end());
      }
      if (!parte.isBlank()) {
        frases.add(parte.strip());
      }
    }
    return frases;
  }

  /** As marcas de um texto, na ordem. */
  static List<String> marcas(String texto) {
    List<String> marcas = new ArrayList<>();
    Matcher m = MARCA.matcher(texto);
    while (m.find()) {
      Matcher numero = NUMERO_MARCA.matcher(m.group(1));
      while (numero.find()) {
        marcas.add("T" + numero.group(1));
      }
    }
    return marcas;
  }

  /** "SIM" ou "NAO" no começo da frase, ou nulo. */
  static String polaridade(String texto) {
    if (texto == null) {
      return null;
    }
    Matcher m = SIM_OU_NAO.matcher(texto);
    if (!m.find()) {
      return null;
    }
    return m.group(1).toLowerCase(Locale.ROOT).startsWith("s") ? "SIM" : "NAO";
  }

  private static String referencias(List<CitacaoLegal> citacoes) {
    List<String> refs = citacoes.stream().map(CitacaoLegal::referencia).distinct().toList();
    if (refs.size() == 1) {
      return refs.get(0);
    }
    return String.join(", ", refs.subList(0, refs.size() - 1)) + " e " + refs.get(refs.size() - 1);
  }

  private static Optional<JsonNode> ler(String texto) {
    if (texto == null) {
      return Optional.empty();
    }
    int inicio = texto.indexOf('{');
    int fim = texto.lastIndexOf('}');
    if (inicio < 0 || fim <= inicio) {
      return Optional.empty();
    }
    try {
      JsonNode no = JSON.readTree(texto.substring(inicio, fim + 1));
      return no != null && no.isObject() ? Optional.of(no) : Optional.empty();
    } catch (JsonProcessingException | RuntimeException e) {
      log.info("Ceres: reconferencia fora do formato ({})", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private static Optional<Fundamento> fundamento(JsonNode no) {
    if (no == null || !no.isObject()) {
      return Optional.empty();
    }
    String marca = texto(no.get("marca"));
    String citacao = texto(no.get("citacao"));
    if (marca == null || citacao == null || !marca.matches("T\\d+")
        || citacao.length() > MAXIMO_FRASE * 2 || FiltroInjecao.pareceInjecao(citacao)) {
      return Optional.empty();
    }
    return Optional.of(new Fundamento(marca, citacao));
  }

  private static String texto(JsonNode no) {
    return no != null && no.isTextual() && !no.asText().isBlank() ? no.asText().strip() : null;
  }
}
