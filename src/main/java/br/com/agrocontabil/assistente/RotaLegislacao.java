package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.rag.BuscaLegislacaoService;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *       estão nos trechos (ou na pergunta): um "redução de 70%" que a lei não diz não passa.</li>
 * </ol>
 */
@Component
public class RotaLegislacao {

  private static final Logger log = LoggerFactory.getLogger(RotaLegislacao.class);

  /** Raciocínio que alguns modelos (qwen3) escrevem no texto quando o thinking não é separado. */
  private static final Pattern RACIOCINIO = Pattern.compile("(?s)<think>.*?</think>");

  /**
   * "Sim" ou "Não" no começo da resposta, como o {@code VerificadorAfirmacaoDados} reconhece:
   * "Sim" com ou sem pontuação, "Não" só com pontuação ("Não incide" é regra, não resposta).
   */
  private static final Pattern SIM_OU_NAO_INICIAL = Pattern.compile(
      "^[\\s\\p{Punct}]*(?:sim(?:\\s*[,.;:!\\u2013\\u2014-]|\\s)|n[a\\u00e3]o\\s*[,.;:!\\u2013\\u2014-])"
          + "[\\s\\p{Punct}]*",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  /**
   * Texto da parte de lei do MISTO quando o modelo falou dos dados do produtor (issue #170). As
   * citações validadas seguem na resposta; sem dígitos, para não virar fonte de número.
   */
  public static final String SO_TRECHOS =
      "A regra legal que trata do assunto esta nos trechos de lei citados.";

  private final ModeloLinguagem modelo;
  private final BuscaLegislacaoService busca;
  private final ValidadorCitacao validadorCitacao;
  private final ValidadorNumeros validadorNumeros;
  private final VerificadorAfirmacaoDados verificadorDados;

  /** Constrói a rota. */
  public RotaLegislacao(ModeloLinguagem modelo, BuscaLegislacaoService busca,
      ValidadorCitacao validadorCitacao, ValidadorNumeros validadorNumeros,
      VerificadorAfirmacaoDados verificadorDados) {
    this.modelo = modelo;
    this.busca = busca;
    this.validadorCitacao = validadorCitacao;
    this.validadorNumeros = validadorNumeros;
    this.verificadorDados = verificadorDados;
  }

  /**
   * Responde uma pergunta sobre a legislação.
   *
   * @param pergunta pergunta do usuário
   * @param contexto contexto da pergunta (data de referência, auditoria)
   * @return resposta com citações validadas, ou mensagem fixa de "sem fundamento legal"
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
    String sistema = soRegraGeral
        ? PromptsCeres.sistemaLegislacaoRegraGeral(contexto.hoje(), formatar(trechos))
        : PromptsCeres.sistemaLegislacao(contexto.hoje(), formatar(trechos));
    Prompt prompt = new Prompt(List.<Message>of(
        new SystemMessage(sistema), new UserMessage(perguntaAoModelo)));
    contexto.verificarAtiva();
    ChatResponse resposta = modelo.chamar(prompt);
    contexto.registrarUso(prompt, resposta);
    String texto = limpar(resposta.getResult().getOutput().getText());

    ValidadorCitacao.Resultado citacao = validadorCitacao.validar(texto, trechos);
    if (!citacao.valida()) {
      // Sem marca (inclusive o NAO_ENCONTREI) ou com marca inventada.
      log.info("Ceres: resposta de legislacao sem citacao valida descartada");
      return ResultadoRota.descartada(
          ValidadorCitacao.SEM_FUNDAMENTO, DesfechoInteracao.CITACAO_INVALIDA);
    }

    List<String> fontes = new ArrayList<>();
    trechos.forEach(t -> fontes.add(t.texto()));
    fontes.add(perguntaAoModelo);
    // A resposta original (com as marcas) e a que o modelo escreveu; as referencias montadas pelo
    // backend nao entram na conferencia.
    ValidadorNumeros.Resultado numeros =
        validadorNumeros.verificar(semMarcas(texto), fontes, List.of(contexto.hoje()));
    if (!numeros.rastreavel()) {
      log.warn("Ceres: resposta de legislacao descartada com {} numero(s) fora dos trechos",
          numeros.quantidadeSemFonte());
      return ResultadoRota.descartada(
          ValidadorCitacao.SEM_FUNDAMENTO, DesfechoInteracao.NUMERO_SEM_FONTE);
    }

    contexto.registrarTrechosCitados(citacao.idsCitados());
    String regra = soRegraGeral && perguntaSeparada
        ? semSimOuNaoInicial(citacao.texto())
        : citacao.texto();
    if (soRegraGeral && verificadorDados.afirmaSobreDados(regra)) {
      log.info("Ceres: parte de legislacao do MISTO falou dos dados do produtor; so trechos");
      return new ResultadoRota(
          SO_TRECHOS, DesfechoInteracao.AFIRMACAO_SOBRE_DADOS, citacao.citacoes());
    }
    return ResultadoRota.aceita(regra, citacao.citacoes());
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
