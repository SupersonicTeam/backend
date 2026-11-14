package br.com.agrocontabil.assistente.explicacao;

import br.com.agrocontabil.assistente.GlossarioProdutor;
import br.com.agrocontabil.assistente.ValidadorNumeros;
import br.com.agrocontabil.assistente.VerificadorNegativaDados;
import br.com.agrocontabil.common.NormalizacaoBusca;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.DesfechoExplicacao;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Confere a explicação do modelo antes de ela chegar ao produtor (#268, com o que era da #415).
 * <strong>Qualquer falha descarta a explicação</strong>, e a resposta do template sai sozinha: ela
 * já é completa e correta sem a explicação.
 *
 * <p>O texto do modelo sobre lei já passou nos validadores com sentido errado (#347). Por isso
 * esta conferência é, de propósito, mais estrita que o necessário, no espírito do
 * {@code VerificadorAfirmacaoDados}: o custo de um falso positivo é o produtor não ver a
 * explicação; o de um falso negativo, uma afirmação errada com a cara do sistema.
 *
 * <p>As conferências, nesta ordem (vai à auditoria a primeira que falhar):
 * <ol>
 *   <li><strong>tamanho</strong>: um parágrafo, sem lista nem título, de
 *       {@code app.assistente.explicacao.min-frases} a {@code max-frases} frases e de
 *       {@code min-caracteres} a {@code max-caracteres} caracteres;</li>
 *   <li><strong>vazamento</strong>: trecho da instrução de sistema, rótulo da mensagem ou nome
 *       interno de função;</li>
 *   <li><strong>números</strong>: todo número, data ou percentual tem de estar no texto do template
 *       ({@link ValidadorNumeros}, com o template como única fonte): nenhum número novo, nenhum
 *       número do template alterado; o cardinal por extenso ("três guias") também, e a grandeza
 *       ou conta por extenso ("mil", "metade") descarta;</li>
 *   <li><strong>sigla</strong> do glossário sem explicação na explicação nem no template, que o
 *       produtor vê logo acima;</li>
 *   <li><strong>conclusão legal sem fonte</strong>: direito, isenção, "não precisa pagar",
 *       obrigação, multa, aproveitamento de crédito, menção a lei ou artigo. As definições do
 *       glossário não contam (a do crédito fala em "você pode descontar"): são texto revisado por
 *       humano.</li>
 * </ol>
 *
 * <p>Função pura sobre o texto: nada vai ao log além do motivo (o texto pode trazer dado do
 * produtor).
 */
@Component
public class VerificadorFidelidade {

  private static final Logger log = LoggerFactory.getLogger(VerificadorFidelidade.class);

  /** Palavras seguidas da instrução de sistema que, repetidas na explicação, são vazamento. */
  static final int PALAVRAS_DO_VAZAMENTO = 8;

  /** Fim de frase: ponto, exclamação, interrogação ou reticências, seguido de espaço. */
  private static final String FINS_DE_FRASE = ".!?…";

  private static final Pattern LETRA = Pattern.compile("\\p{L}");

  private static final Pattern PALAVRA = Pattern.compile("\\p{L}+");

  /** Cardinais por extenso, já sem acento; "um" e "uma" ficam de fora (são artigo). */
  private static final Map<String, Integer> CARDINAIS = Map.ofEntries(
      Map.entry("dois", 2), Map.entry("duas", 2), Map.entry("tres", 3), Map.entry("quatro", 4),
      Map.entry("cinco", 5), Map.entry("seis", 6), Map.entry("sete", 7), Map.entry("oito", 8),
      Map.entry("nove", 9), Map.entry("dez", 10), Map.entry("onze", 11), Map.entry("doze", 12),
      Map.entry("treze", 13), Map.entry("catorze", 14), Map.entry("quatorze", 14),
      Map.entry("quinze", 15), Map.entry("dezesseis", 16), Map.entry("dezessete", 17),
      Map.entry("dezoito", 18), Map.entry("dezenove", 19), Map.entry("vinte", 20),
      Map.entry("trinta", 30), Map.entry("quarenta", 40), Map.entry("cinquenta", 50),
      Map.entry("sessenta", 60), Map.entry("setenta", 70), Map.entry("oitenta", 80),
      Map.entry("noventa", 90), Map.entry("cem", 100));

  /** Grandeza ou conta por extenso: valor que não dá para conferir com o template. */
  private static final Pattern GRANDEZA_POR_EXTENSO = Pattern.compile("\\b(cento|duzent\\w*|"
      + "trezent\\w*|quatrocent\\w*|quinhent\\w*|seiscent\\w*|setecent\\w*|oitocent\\w*|"
      + "novecent\\w*|mil|milhao|milhoes|bilhao|bilhoes|dobro|triplo|metade)\\b");

  /** Item de lista, título ou ênfase em markdown no começo de uma linha. */
  private static final Pattern MARCACAO =
      Pattern.compile("(?m)^\\s*(?:[-*•#>]|\\d{1,2}[.)])\\s|\\*\\*|__");

  /** Identificador em camelCase, como o nome das funções de dados ({@code consultarApuracao}). */
  private static final Pattern CAMEL_CASE = Pattern.compile("\\b[a-z]+(?:[A-Z][a-z0-9]*)+\\b");

  /** Falas sobre o próprio prompt ou o modelo, já sem acento e em minúsculas. */
  private static final Pattern META = Pattern.compile(
      "\\b(prompt|instrucoes do sistema|instrucao do sistema|modelo de linguagem|"
          + "sou uma ia|inteligencia artificial)\\b");

  /** Regra de conclusão legal, com o nome que vai ao log (nunca o texto). */
  private record Regra(String nome, Pattern padrao) {}

  /** Sobre o texto sem acento, em minúsculas e sem as definições do glossário. */
  private static final List<Regra> CONCLUSOES_LEGAIS = List.of(
      new Regra("direito", Pattern.compile("\\bdireitos?\\b")),
      new Regra("isencao", Pattern.compile(
          "\\b(isent[oa]s?|isencao|isencoes|imune|imunes|imunidade|nao\\s+tributad\\w*)\\b")),
      // "Aparece como nao paga" (situacao da guia) passa; "voce nao paga imposto", nao.
      new Regra("nao-paga", Pattern.compile("\\bnao\\s+(precisa|precisara|precisarao|vai\\s+"
          + "precisar|tem\\s+que|tera\\s+que|deve|devera|e\\s+preciso)\\s+(de\\s+)?"
          + "(pagar|recolher|declarar|emitir)\\b|\\bvoce\\s+nao\\s+(paga|pagara|recolhe|"
          + "recolhera)\\b|\\bnao\\s+(paga|pagara|pagarao|pagam|recolhe|recolhera)\\s+((o|a|os|as)"
          + "\\s+)?(impostos?|tributos?|ibs|cbs|funrural|contribuic\\w*|nada)\\b|\\bnao\\s+("
          + "incide|incidem|incidira|ha\\s+incidencia)\\b|\\bdispensad[oa]s?\\b|\\bdispensa\\b")),
      new Regra("obrigacao", Pattern.compile("\\bobrigad[oa]s?\\b|\\bobrigatori\\w*|\\b(deve|devem|"
          + "devera|deverao)\\s+(pagar|recolher|declarar|emitir)\\b")),
      new Regra("penalidade", Pattern.compile("\\b(multas?|juros|penalidades?|autuac\\w*)\\b")),
      // "Pode usar a tela de notas" passa; "pode usar esse crédito", nao.
      new Regra("aproveitamento", Pattern.compile("\\bpode(ra|m|rao)?\\s+(abater|descontar|"
          + "compensar|aproveitar|deduzir|recuperar|creditar)\\b|\\bpode(ra|m|rao)?\\s+usar\\s+"
          + "(\\w+\\s+){0,2}creditos?\\b|\\bpedir\\s+de\\s+volta\\b")),
      new Regra("beneficio", Pattern.compile("\\b(beneficios?\\s+fisca\\w*|aliquota\\s+(zero|"
          + "reduzida)|reducao\\s+de\\s+aliquota|regime\\s+(especial|diferenciado))\\b")),
      new Regra("norma", Pattern.compile("\\b(lei|leis|legislacao|legal|legalmente|juridic\\w*|"
          + "art|artigos?|paragrafos?|incisos?|alineas?|constituicao|constitucional|decretos?|"
          + "instrucao\\s+normativa|lc\\s*\\d+|ec\\s*\\d+)\\b")));

  private final ValidadorNumeros validadorNumeros;
  private final GlossarioProdutor glossario;
  private final AssistenteProperties.Explicacao limites;
  private final Set<String> trechosDoSistema;
  private final Set<String> nomesDoGlossario;

  /** Constrói o verificador com os limites de {@code app.assistente.explicacao.*}. */
  public VerificadorFidelidade(ValidadorNumeros validadorNumeros, GlossarioProdutor glossario,
      AssistenteProperties propriedades) {
    this.validadorNumeros = validadorNumeros;
    this.glossario = glossario;
    this.limites = propriedades.getExplicacao();
    this.trechosDoSistema = sequencias(NormalizacaoBusca.normalizar(PromptExplicacao.SISTEMA));
    Set<String> nomes = new HashSet<>();
    glossario.termos().forEach(t -> nomes.add(t.termo()));
    this.nomesDoGlossario = Set.copyOf(nomes);
  }

  /**
   * Confere a explicação contra o texto do template.
   *
   * @param explicacao texto do modelo
   * @param template   texto do template entregue ao produtor (a única fonte de números)
   * @return {@link DesfechoExplicacao#EXPLICACAO_ACEITA} ou o primeiro motivo de descarte
   */
  public DesfechoExplicacao verificar(String explicacao, String template) {
    DesfechoExplicacao desfecho = motivo(explicacao, template == null ? "" : template);
    if (desfecho != DesfechoExplicacao.EXPLICACAO_ACEITA) {
      log.info("Ceres: explicacao descartada ({})", desfecho);
    }
    return desfecho;
  }

  /**
   * O texto que vai ao produtor: um parágrafo, com os espaços colapsados. Só depois de aceito.
   *
   * @param explicacao texto aceito pelo {@link #verificar}
   * @return o mesmo texto, num parágrafo só
   */
  public static String paragrafo(String explicacao) {
    return explicacao == null ? "" : explicacao.strip().replaceAll("\\s+", " ");
  }

  private DesfechoExplicacao motivo(String explicacao, String template) {
    if (!dentroDoTamanho(explicacao)) {
      return DesfechoExplicacao.EXPLICACAO_DESCARTADA_TAMANHO;
    }
    String texto = paragrafo(explicacao);
    String normalizado = NormalizacaoBusca.normalizar(texto);
    if (vazaPrompt(texto, normalizado)) {
      return DesfechoExplicacao.EXPLICACAO_DESCARTADA_VAZAMENTO;
    }
    if (!validadorNumeros.verificar(texto, List.of(template), List.of()).rastreavel()
        || !porExtensoNoTemplate(normalizado, template)) {
      return DesfechoExplicacao.EXPLICACAO_DESCARTADA_NUMERO;
    }
    String templateNormalizado = NormalizacaoBusca.normalizar(template);
    if (glossario.siglasSemExplicacao(texto).stream()
        .anyMatch(t -> !t.explicadoEm(templateNormalizado))) {
      return DesfechoExplicacao.EXPLICACAO_DESCARTADA_SIGLA;
    }
    String semDefinicoes = semDefinicoesDoGlossario(normalizado);
    for (Regra regra : CONCLUSOES_LEGAIS) {
      if (regra.padrao().matcher(semDefinicoes).find()) {
        log.info("Ceres: explicacao com conclusao legal sem fonte (regra {})", regra.nome());
        return DesfechoExplicacao.EXPLICACAO_DESCARTADA_CONCLUSAO_LEGAL;
      }
    }
    return DesfechoExplicacao.EXPLICACAO_ACEITA;
  }

  private boolean dentroDoTamanho(String explicacao) {
    if (explicacao == null || explicacao.isBlank() || MARCACAO.matcher(explicacao).find()
        || explicacao.strip().contains("\n\n")) {
      return false;
    }
    String texto = paragrafo(explicacao);
    if (texto.length() < limites.getMinCaracteres()
        || texto.length() > limites.getMaxCaracteres()) {
      return false;
    }
    int frases = frases(texto);
    return frases >= limites.getMinFrases() && frases <= limites.getMaxFrases();
  }

  /**
   * Frases do parágrafo: termina em ponto, exclamação, interrogação ou reticências seguidos de
   * espaço (ou no fim), menos o ponto de abreviação ({@code VerificadorNegativaDados.ABREVIACOES}:
   * "ex.", "Sr.", "art."). Só conta trecho com letra.
   */
  static int frases(String texto) {
    int frases = 0;
    int inicio = 0;
    for (int i = 0; i < texto.length(); i++) {
      char c = texto.charAt(i);
      boolean fim = FINS_DE_FRASE.indexOf(c) >= 0
          && (i + 1 == texto.length() || Character.isWhitespace(texto.charAt(i + 1)));
      if (fim && !(c == '.' && abreviacao(texto, inicio, i))) {
        if (LETRA.matcher(texto.substring(inicio, i + 1)).find()) {
          frases++;
        }
        inicio = i + 1;
      }
    }
    if (inicio < texto.length() && LETRA.matcher(texto.substring(inicio)).find()) {
      frases++;
    }
    return frases;
  }

  private static boolean abreviacao(String texto, int inicio, int ponto) {
    int j = ponto;
    while (j > inicio && Character.isLetter(texto.charAt(j - 1))) {
      j--;
    }
    return VerificadorNegativaDados.ABREVIACOES.contains(
        texto.substring(j, ponto).toLowerCase(Locale.ROOT));
  }

  /**
   * Número por extenso (#268, achado do review): o {@link ValidadorNumeros} só lê dígitos e
   * percentual por extenso, então "três guias" diante de "2 guias" passaria. Cardinal de dois a
   * cem tem de estar entre os valores do template; grandeza ("mil", "milhão") e conta ("dobro",
   * "metade") não dá para conferir e descarta. "Um" e "uma" ficam de fora: são artigo.
   */
  private static boolean porExtensoNoTemplate(String normalizado, String template) {
    if (GRANDEZA_POR_EXTENSO.matcher(normalizado).find()) {
      return false;
    }
    Set<BigDecimal> doTemplate = ValidadorNumeros.valoresMencionados(template);
    Matcher palavra = PALAVRA.matcher(normalizado);
    while (palavra.find()) {
      Integer valor = CARDINAIS.get(palavra.group());
      if (valor != null
          && !doTemplate.contains(BigDecimal.valueOf(valor).stripTrailingZeros())) {
        return false;
      }
    }
    return true;
  }

  private boolean vazaPrompt(String texto, String normalizado) {
    for (String rotulo : PromptExplicacao.ROTULOS) {
      if (normalizado.contains(NormalizacaoBusca.normalizar(rotulo))) {
        return true;
      }
    }
    if (META.matcher(normalizado).find()) {
      return true;
    }
    Matcher camel = CAMEL_CASE.matcher(texto);
    while (camel.find()) {
      if (!nomesDoGlossario.contains(camel.group())) {
        return true;
      }
    }
    return sequencias(normalizado).stream().anyMatch(trechosDoSistema::contains);
  }

  /** O texto sem as definições do glossário (normalizadas), que são revisadas por humano. */
  private String semDefinicoesDoGlossario(String normalizado) {
    String resto = normalizado;
    for (GlossarioProdutor.Termo termo : glossario.termos()) {
      resto = resto.replace(termo.explicacaoNormalizada(), " ");
    }
    return resto;
  }

  /** Sequências de {@value #PALAVRAS_DO_VAZAMENTO} palavras seguidas, só letras e dígitos. */
  private static Set<String> sequencias(String normalizado) {
    String[] palavras = normalizado.replaceAll("[^\\p{L}\\p{N}]+", " ").strip().split(" ");
    Set<String> sequencias = new HashSet<>();
    for (int i = 0; i + PALAVRAS_DO_VAZAMENTO <= palavras.length; i++) {
      sequencias.add(String.join(" ",
          Arrays.copyOfRange(palavras, i, i + PALAVRAS_DO_VAZAMENTO)));
    }
    return sequencias;
  }
}
