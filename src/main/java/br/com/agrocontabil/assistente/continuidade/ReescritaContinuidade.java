package br.com.agrocontabil.assistente.continuidade;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.DataSanitizer;
import br.com.agrocontabil.assistente.PeriodoDaPergunta;
import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.ValidadorNumeros;
import br.com.agrocontabil.assistente.continuidade.CargaTurno.Faixa;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.orquestracao.FiltroInjecao;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * Reescrita da mensagem nova com o histórico (#499): "e algum crédito?" vira a pergunta completa.
 * O modelo só reescreve; as guardas, nesta ordem, fazem valer a mensagem original em qualquer
 * dúvida: formato, injeção, terceiro, número, período, conteúdo e âncora do referente.
 *
 * <p>O que vai ao provedor: as perguntas e as respostas inteiras dos pares (decisão do Murillo,
 * 2026-11-10), cada texto pelo {@link DataSanitizer}, salvo os pares cuja resposta tem cara de
 * injeção; nunca o produtor, a assinatura nem a carga. Nada do texto vai a log.
 */
@Component
public class ReescritaContinuidade {

  /** Orçamento de caracteres do JSON da mensagem: acima disso sai o par mais antigo inteiro. */
  static final int ORCAMENTO = 20_000;

  /** Tamanho máximo da pergunta reescrita. */
  static final int TAMANHO_MAXIMO = 300;

  private static final Logger log = LoggerFactory.getLogger(ReescritaContinuidade.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Pattern CARA_DE_RESPOSTA = Pattern.compile(
      "^(sim|nao|claro|certo|desculp\\w*|ola|oi|tenho certeza|conferi|corrijo|repeti|refiz"
          + "|como (assistente|ceres))\\b");
  private static final Pattern SEGUNDA_PESSOA = Pattern.compile("\\b(voce|vc|seu|sua|seus|suas)\\b");
  private static final Pattern LINK = Pattern.compile(
      "https?://|www\\.|@|\\b[\\w-]+\\.(com|net|org|br)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern MARCADOR = Pattern.compile(
      "\\[(CPF|CNPJ|EMAIL|TELEFONE|CHAVE|CEP)\\]");
  private static final Pattern NOTA_OU_SERIE = Pattern.compile("\\b(nota|serie) (\\d+)\\b");
  private static final List<Pattern> PERIODOS = List.of(
      Pattern.compile("\\b(janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro"
          + "|outubro|novembro|dezembro)\\b"),
      Pattern.compile("\\b(mes|ano|semana|semestre|trimestre|safra) (passad\\w*|retrasad\\w*"
          + "|anterior|seguinte|que vem)\\b"),
      Pattern.compile("\\b(este|esse|deste|desse|neste|nesse|proxim\\w*|ultim\\w*) (mes|ano"
          + "|semana|semestre|trimestre|safra)\\b"),
      Pattern.compile("\\b(hoje|ontem|amanha)\\b"));

  /** Radicais genéricos da pergunta, que não identificam um item na âncora do ordinal. */
  private static final List<String> GENERICAS = List.of("nota", "guia", "pedid", "devol",
      "rejeit", "cancel", "autoriz", "vend", "compr", "apura", "credit", "venc", "pend", "situa",
      "foram", "tenh", "minha", "meus", "mostr", "lista");

  private final ModeloLinguagem modelo;
  private final ValidadorNumeros validadorNumeros;
  private final int teto;

  /** Constrói a reescrita; o teto é {@code app.assistente.tetos.reescrita}. */
  public ReescritaContinuidade(ModeloLinguagem modelo, ValidadorNumeros validadorNumeros,
      AssistenteProperties propriedades) {
    this.modelo = modelo;
    this.validadorNumeros = validadorNumeros;
    this.teto = propriedades.getTetos().getReescrita();
  }

  /**
   * O resultado da reescrita.
   *
   * @param pergunta a pergunta considerada (a reescrita aplicada, ou a mensagem)
   * @param desfecho o que aconteceu
   */
  public record Resultado(String pergunta, DesfechoContinuidade desfecho) {

    /** A reescrita mudou a pergunta. */
    public boolean aplicada() {
      return desfecho == DesfechoContinuidade.CONTINUIDADE_APLICADA;
    }

    /** Sem o texto. */
    @Override
    public String toString() {
      return "Resultado[" + desfecho + "]";
    }
  }

  /** Um par como vai ao prompt: nunca o {@code TurnoConversa} (sem assinatura nem carga). */
  record ParPrompt(String pergunta, String resposta) {}

  /** A mensagem ao modelo. */
  record MensagemReescrita(List<ParPrompt> historico, String mensagemNova) {}

  /**
   * Reescreve a mensagem nova, com as guardas.
   *
   * @param mensagem  mensagem nova
   * @param historico histórico conferido
   * @param criterio  critério do gatilho
   * @param tipo      o que a mensagem faz com a resposta anterior
   * @param contexto  contexto da pergunta
   * @return a pergunta considerada e o desfecho
   */
  public Resultado reescrever(String mensagem, HistoricoConferido historico,
      GatilhoReescrita.Criterio criterio, MensagemContinuidade.Tipo tipo,
      ContextoAssistente contexto) {
    String json;
    try {
      json = JSON.writeValueAsString(mensagemAoModelo(mensagem, historico));
    } catch (JsonProcessingException e) {
      return new Resultado(mensagem, DesfechoContinuidade.CONTINUIDADE_SEM_MODELO);
    }
    Prompt prompt = new Prompt(List.<Message>of(
        new SystemMessage(PromptsCeres.REESCRITA_CONTINUIDADE), new UserMessage(json)),
        ModeloLinguagem.comTeto(teto));
    ChatResponse resposta;
    try {
      resposta = contexto.chamar(modelo, prompt);
    } catch (AssistenteIndisponivelException e) {
      // Interrupcao (tempo esgotado, cliente que saiu) nao vira "sem modelo": a pergunta acabou.
      contexto.verificarAtiva();
      return new Resultado(mensagem, DesfechoContinuidade.CONTINUIDADE_SEM_MODELO);
    }
    if (ModeloLinguagem.cortadaPeloTeto(resposta, teto)) {
      return new Resultado(mensagem, DesfechoContinuidade.CONTINUIDADE_SEM_MODELO);
    }
    Optional<String> lida = ler(resposta.getResult().getOutput().getText());
    if (lida.isEmpty()) {
      return new Resultado(mensagem, DesfechoContinuidade.DESCARTADA_FORMATO);
    }
    DesfechoContinuidade guarda = conferir(lida.get(), mensagem, historico, criterio, tipo,
        contexto.hoje());
    if (guarda != DesfechoContinuidade.CONTINUIDADE_APLICADA) {
      return new Resultado(mensagem, guarda);
    }
    return TextoContinuidade.normalizar(lida.get()).equals(TextoContinuidade.normalizar(mensagem))
        ? new Resultado(mensagem, DesfechoContinuidade.CONTINUIDADE_MANTIDA)
        : new Resultado(lida.get().strip(), DesfechoContinuidade.CONTINUIDADE_APLICADA);
  }

  /**
   * A mensagem ao modelo: os pares (menos os fora da reescrita), mascarados, com a resposta
   * inteira; quebra de linha da pergunta vira espaço. Acima do orçamento sai o par mais antigo
   * inteiro, nunca um pedaço de resposta.
   */
  static MensagemReescrita mensagemAoModelo(String mensagem, HistoricoConferido historico) {
    List<ParPrompt> pares = new ArrayList<>();
    for (ParConferido par : historico.pares()) {
      if (!par.foraDaReescrita()) {
        pares.add(new ParPrompt(
            DataSanitizer.mascarar(par.pergunta()).replace('\n', ' ').replace('\r', ' '),
            DataSanitizer.mascarar(par.resposta())));
      }
    }
    String nova = DataSanitizer.mascarar(mensagem).replace('\n', ' ').replace('\r', ' ');
    while (pares.size() > 1 && tamanho(pares, nova) > ORCAMENTO) {
      pares.remove(0);
    }
    return new MensagemReescrita(pares, nova);
  }

  private static int tamanho(List<ParPrompt> pares, String nova) {
    int total = nova.length() + 40;
    for (ParPrompt p : pares) {
      total += p.pergunta().length() + p.resposta().length() + 40;
    }
    return total;
  }

  /** O {@code pergunta} do JSON entre a primeira chave e a última. */
  static Optional<String> ler(String texto) {
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
      JsonNode pergunta = no == null ? null : no.get("pergunta");
      return pergunta != null && pergunta.isTextual() && !pergunta.asText().isBlank()
          ? Optional.of(pergunta.asText()) : Optional.empty();
    } catch (JsonProcessingException | RuntimeException e) {
      // So o fato: a JsonParseException traz o token lido.
      log.info("Ceres: reescrita fora do formato ({})", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /**
   * As guardas, nesta ordem; qualquer falha faz valer a mensagem original.
   *
   * @return {@code CONTINUIDADE_APLICADA} se todas passam, ou o desfecho da primeira que barra
   */
  DesfechoContinuidade conferir(String reescrita, String mensagem, HistoricoConferido historico,
      GatilhoReescrita.Criterio criterio, MensagemContinuidade.Tipo tipo, LocalDate hoje) {
    String normal = TextoContinuidade.normalizar(reescrita);
    String normalMensagem = TextoContinuidade.normalizar(mensagem);
    List<String> perguntas = historico.pares().stream()
        .map(p -> TextoContinuidade.normalizar(p.pergunta())).toList();
    if (!formato(reescrita, normal, mensagem, normalMensagem, historico, perguntas)) {
      return DesfechoContinuidade.DESCARTADA_FORMATO;
    }
    if (FiltroInjecao.pareceInjecao(reescrita)) {
      return DesfechoContinuidade.DESCARTADA_INJECAO;
    }
    if (terceiro(normal, normalMensagem, perguntas, historico)) {
      return DesfechoContinuidade.DESCARTADA_TERCEIRO;
    }
    boolean depoisDeLei = historico.ultimo().map(p -> p.carga().lei() != null).orElse(false);
    if (!numeros(reescrita, mensagem, historico, tipo.contestacao() && depoisDeLei)) {
      return DesfechoContinuidade.DESCARTADA_NUMERO;
    }
    if (!periodos(reescrita, normal, mensagem, normalMensagem, perguntas, hoje)) {
      return DesfechoContinuidade.DESCARTADA_PERIODO;
    }
    if (!conteudo(normal, normalMensagem)) {
      return DesfechoContinuidade.DESCARTADA_CONTEUDO;
    }
    if (!ancora(normal, criterio, historico)) {
      return DesfechoContinuidade.DESCARTADA_ANCORA;
    }
    return DesfechoContinuidade.CONTINUIDADE_APLICADA;
  }

  static boolean formato(String reescrita, String normal, String mensagem, String normalMensagem,
      HistoricoConferido historico, List<String> perguntas) {
    if (reescrita.isBlank() || reescrita.length() > TAMANHO_MAXIMO
        || CARA_DE_RESPOSTA.matcher(normal).find()) {
      return false;
    }
    if (SEGUNDA_PESSOA.matcher(normal).find() && !SEGUNDA_PESSOA.matcher(normalMensagem).find()) {
      return false;
    }
    if (LINK.matcher(reescrita).find()) {
      return false;
    }
    Set<String> marcadoresMensagem = marcadores(DataSanitizer.mascarar(mensagem));
    if (!marcadoresMensagem.containsAll(marcadores(DataSanitizer.mascarar(reescrita)))) {
      return false;
    }
    return !copiaDeResposta(normal, normalMensagem, perguntas, historico);
  }

  /** 8 ou mais palavras seguidas de uma resposta que não estão na mensagem nem nas perguntas. */
  static boolean copiaDeResposta(String normal, String normalMensagem, List<String> perguntas,
      HistoricoConferido historico) {
    List<String> palavras = TextoContinuidade.palavras(normal);
    for (int i = 0; i + 8 <= palavras.size(); i++) {
      String sequencia = " " + String.join(" ", palavras.subList(i, i + 8)) + " ";
      boolean emResposta = historico.pares().stream().anyMatch(p ->
          (" " + TextoContinuidade.normalizar(p.resposta()) + " ").contains(sequencia));
      boolean emPerguntas = (" " + normalMensagem + " ").contains(sequencia)
          || perguntas.stream().anyMatch(q -> (" " + q + " ").contains(sequencia));
      if (emResposta && !emPerguntas) {
        return true;
      }
    }
    return false;
  }

  /**
   * Palavra de 4 letras ou mais (ou número) da reescrita que aparece num texto livre de terceiro
   * e não aparece na mensagem, nas perguntas nem fora dos textos livres das respostas. Com
   * {@code t: "*"}, tudo que só existe nas respostas, salvo "nota/série N".
   */
  static boolean terceiro(String normal, String normalMensagem, List<String> perguntas,
      HistoricoConferido historico) {
    Set<String> conhecidas = new HashSet<>(TextoContinuidade.palavras(normalMensagem));
    perguntas.forEach(q -> conhecidas.addAll(TextoContinuidade.palavras(q)));
    Set<String> foraDeTerceiros = new HashSet<>();
    Set<String> deTerceiros = new HashSet<>();
    for (ParConferido par : historico.pares()) {
      String resposta = par.resposta();
      CargaTurno carga = par.carga();
      if (carga.livresTodos()) {
        deTerceiros.addAll(TextoContinuidade.palavras(TextoContinuidade.normalizar(resposta)));
        continue;
      }
      List<Faixa> faixas = carga.livres() == null ? List.of() : carga.livres();
      StringBuilder fora = new StringBuilder();
      int de = 0;
      for (Faixa f : faixas) {
        deTerceiros.addAll(TextoContinuidade.palavras(TextoContinuidade.normalizar(f.de(resposta))));
        fora.append(resposta, de, f.inicio()).append(' ');
        de = f.fim();
      }
      fora.append(resposta.substring(Math.min(de, resposta.length())));
      foraDeTerceiros.addAll(TextoContinuidade.palavras(
          TextoContinuidade.normalizar(fora.toString())));
    }
    String semNotas = NOTA_OU_SERIE.matcher(normal).replaceAll(" ");
    for (String palavra : TextoContinuidade.palavras(semNotas.strip())) {
      boolean relevante = palavra.length() >= 4 || TextoContinuidade.numero(palavra);
      if (relevante && deTerceiros.contains(palavra) && !conhecidas.contains(palavra)
          && !foraDeTerceiros.contains(palavra)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Todo número da reescrita está na mensagem nova ou nas perguntas, exceto "nota"/"série" com o
   * número de uma resposta, fora dos textos livres. Na contestação depois de lei, número que só
   * está na mensagem nova também barra.
   */
  boolean numeros(String reescrita, String mensagem, HistoricoConferido historico,
      boolean contestacaoDeLei) {
    String normal = TextoContinuidade.normalizar(reescrita);
    Set<String> numerosDeNota = new HashSet<>();
    for (ParConferido par : historico.pares()) {
      Matcher m = NOTA_OU_SERIE.matcher(foraDosLivres(par));
      while (m.find()) {
        numerosDeNota.add(m.group(2));
      }
    }
    StringBuilder semNotas = new StringBuilder();
    Matcher m = NOTA_OU_SERIE.matcher(normal);
    int de = 0;
    while (m.find()) {
      if (numerosDeNota.contains(m.group(2))) {
        semNotas.append(normal, de, m.start()).append(m.group(1)).append(' ');
        de = m.end();
      }
    }
    semNotas.append(normal.substring(de));
    List<String> fontes = new ArrayList<>();
    if (!contestacaoDeLei) {
      fontes.add(mensagem);
    }
    historico.pares().forEach(p -> fontes.add(p.pergunta()));
    String texto = de == 0 ? reescrita : semNotas.toString();
    return validadorNumeros.verificar(texto, fontes).rastreavel();
  }

  /** A resposta normalizada sem os textos livres de terceiro. */
  static String foraDosLivres(ParConferido par) {
    CargaTurno carga = par.carga();
    if (carga.livresTodos()) {
      return "";
    }
    String resposta = par.resposta();
    if (carga.livres() == null) {
      return TextoContinuidade.normalizar(resposta);
    }
    StringBuilder fora = new StringBuilder();
    int de = 0;
    for (Faixa f : carga.livres()) {
      fora.append(resposta, de, f.inicio()).append(' ');
      de = f.fim();
    }
    fora.append(resposta.substring(Math.min(de, resposta.length())));
    return TextoContinuidade.normalizar(fora.toString());
  }

  /**
   * Cada expressão de período da reescrita está na mensagem nova ou nas perguntas; com período
   * próprio na mensagem nova, os dois resolvem igual.
   */
  static boolean periodos(String reescrita, String normal, String mensagem, String normalMensagem,
      List<String> perguntas, LocalDate hoje) {
    for (Pattern padrao : PERIODOS) {
      Matcher m = padrao.matcher(normal);
      while (m.find()) {
        String expressao = m.group();
        boolean conhecida = (" " + normalMensagem + " ").contains(" " + expressao + " ")
            || perguntas.stream().anyMatch(q -> (" " + q + " ").contains(" " + expressao + " "));
        if (!conhecida) {
          return false;
        }
      }
    }
    Optional<PeriodoDaPergunta.Periodo> daMensagem = PeriodoDaPergunta.resolver(mensagem, hoje);
    return daMensagem.isEmpty()
        || daMensagem.equals(PeriodoDaPergunta.resolver(reescrita, hoje));
  }

  /** As palavras de conteúdo da mensagem nova estão na reescrita (prefixo de 5 letras). */
  static boolean conteudo(String normal, String normalMensagem) {
    List<String> reescrita = TextoContinuidade.palavras(normal);
    for (String palavra : TextoContinuidade.palavras(normalMensagem)) {
      if (TextoContinuidade.conteudo(palavra) && !TextoContinuidade.numero(palavra)
          && !TextoContinuidade.contemPorPrefixo(reescrita, palavra)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Âncora do referente: no ordinal, a reescrita tem um "nota N"/"série N" da última resposta,
   * fora dos textos livres, ou um produto da pergunta ou da consulta do par; no SEGUIMENTO, ao
   * menos uma palavra de conteúdo da pergunta do par.
   */
  static boolean ancora(String normal, GatilhoReescrita.Criterio criterio,
      HistoricoConferido historico) {
    Optional<ParConferido> ultimo = historico.ultimo();
    if (ultimo.isEmpty()) {
      return true;
    }
    ParConferido par = ultimo.get();
    List<String> palavras = TextoContinuidade.palavras(normal);
    List<String> pergunta = TextoContinuidade.palavras(TextoContinuidade.normalizar(par.pergunta()));
    if (criterio == GatilhoReescrita.Criterio.ORDINAL) {
      Matcher notas = NOTA_OU_SERIE.matcher(foraDosLivres(par));
      while (notas.find()) {
        if ((" " + normal + " ").contains(" " + notas.group(1) + " " + notas.group(2) + " ")) {
          return true;
        }
      }
      // Produto: palavra de conteudo da pergunta que nao e generica (nota, guia, devolucao...) ou
      // um argumento de produto da consulta. Palavra generica casaria qualquer reescrita.
      Set<String> produtos = new HashSet<>();
      pergunta.stream().filter(TextoContinuidade::conteudo)
          .filter(p -> GENERICAS.stream().noneMatch(p::startsWith))
          .forEach(produtos::add);
      if (par.carga().consulta() != null) {
        for (ChamadaAssinada c : par.carga().consulta()) {
          for (String nome : List.of("descricaoOuNcm", "produtoOuNcm")) {
            String valor = c.argumento(nome);
            if (valor != null) {
              produtos.addAll(TextoContinuidade.palavras(TextoContinuidade.normalizar(valor)));
            }
          }
        }
      }
      return produtos.stream().anyMatch(p -> p.length() >= 3
          && TextoContinuidade.contemPorPrefixo(palavras, p));
    }
    if (criterio == GatilhoReescrita.Criterio.SEGUIMENTO) {
      return pergunta.stream().filter(TextoContinuidade::conteudo)
          .anyMatch(p -> TextoContinuidade.contemPorPrefixo(palavras, p));
    }
    return true;
  }

  private static Set<String> marcadores(String texto) {
    Set<String> achados = new HashSet<>();
    Matcher m = MARCADOR.matcher(texto);
    while (m.find()) {
      achados.add(m.group());
    }
    return achados;
  }
}
