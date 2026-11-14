package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.GlossarioProdutor;
import br.com.agrocontabil.assistente.LinhaGlossario;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Atalho da pergunta de definição (#269): "o que é o IBS?", a pergunta mais básica que um produtor
 * faz, respondida pelo glossário e pelo dispositivo que institui o termo, <strong>sem o
 * modelo</strong> e sem a busca por similaridade.
 *
 * <p><strong>Por que existe.</strong> Na VPS, em 2026-11-13, "o que é o ibs?" saiu como "Não
 * encontrei fundamento legal para isso" e "oq é o imposto IBS?" trouxe o art. 69, § 2º, II (base de
 * cálculo da importação). O dispositivo que institui o IBS está na base — a busca semântica é que
 * não o traz para uma pergunta curta de definição, em que a pergunta casa mal com o texto jurídico
 * e há centenas de trechos citando o termo (o problema da #200). Como o {@link
 * AtalhoReformaTributaria} (#347), a saída é o backend responder o que ele sabe responder certo.
 *
 * <p><strong>Molde da {@link ConversaLeve}:</strong> lista fechada, mensagem inteira,
 * determinístico. A mensagem só é pergunta de definição se, depois de normalizada
 * ({@link RegrasClassificacao}), começar por uma {@link #FORMULAS fórmula} de definição e o que
 * sobrar, sem as palavras {@link #IGNORAVEIS}, for <strong>exatamente</strong> um termo do
 * {@link #DISPOSITIVOS mapa curado} — mesmo número de palavras, não só contê-lo.
 *
 * <p>É essa disciplina que separa a definição da pergunta de lei e da de dados, sem precisar de
 * lista de proibições: "o que é a alíquota do IBS?" (quer o número), "o que é o NCM da soja?"
 * (quer o tratamento do produto), "o que é a minha apuração?" (quer o dado) e "o que é insumo
 * agropecuário para a lei?" (quer a regra) têm palavra a mais e seguem o fluxo normal. A
 * {@code RegrasClassificacao.LEGISLACAO_GENERICA} não serviria de guarda: ela lista "alíquota",
 * "isenção", "imunidade", "fato gerador" e "contribuinte", que são termos deste mapa.
 *
 * <p><strong>Só termo com dispositivo conferido.</strong> O mapa tem o dispositivo que institui ou
 * define cada termo, conferido na base de legislação em 2026-11-13; a citação sai pelo
 * {@link CitacoesFixas}, por rótulo exato. Termo do glossário fora do mapa (NF-e, NCM, SEFAZ, guia:
 * conceitos do sistema, não da lei; isenção e diferimento, sem dispositivo que os defina em geral)
 * <strong>não</strong> é atalho e segue para a rota de lei, como antes. Sem isso, uma resposta de
 * legislação sem citação contaria como "sem citação válida" na régua da avaliação.
 */
@Component
public class AtalhoDefinicao {

  /**
   * Termo do glossário → {@code norma|dispositivo} que o institui ou define, conferido vigente na
   * base em 2026-11-13. A ordem é a do glossário.
   */
  static final Map<String, String> DISPOSITIVOS = Map.ofEntries(
      // Art. 1º da LC 214: "Ficam instituídos: I - o Imposto sobre Bens e Serviços (IBS)...".
      Map.entry("IBS", "LC 214/2025|art. 1º, inciso I"),
      Map.entry("CBS", "LC 214/2025|art. 1º, inciso II"),
      // "O período de apuração... o saldo... diferença entre os valores" (art. 45).
      Map.entry("apuração", "LC 214/2025|art. 45"),
      Map.entry("crédito", "LC 214/2025|art. 47"),
      Map.entry("split payment", "LC 214/2025|art. 31"),
      Map.entry("Funrural", "Lei 8.212/1991|art. 25, inciso I"),
      Map.entry("alíquota", "LC 214/2025|art. 14"),
      Map.entry("base de cálculo", "LC 214/2025|art. 12"),
      Map.entry("fato gerador", "LC 214/2025|art. 10"),
      Map.entry("contribuinte", "LC 214/2025|art. 21"),
      Map.entry("crédito presumido", "LC 214/2025|art. 168"),
      // "São imunes ao IBS e à CBS as exportações de bens e de serviços" (art. 8º).
      Map.entry("imunidade", "LC 214/2025|art. 8º"),
      // O regime regular é o que dá direito ao crédito (art. 47), o que a definição diz.
      Map.entry("regime regular", "LC 214/2025|art. 47"),
      Map.entry("não cumulatividade",
          "EC 132/2023|art. 1º (redação do art. 156-A, § 1º, inciso VIII)"));

  /**
   * Aberturas de pergunta de definição, normalizadas, da mais longa para a mais curta (a busca
   * tenta nessa ordem). Lista fechada: forma nova entra aqui.
   */
  static final List<String> FORMULAS = List.of(
      "me explique o que e", "me explica o que e", "qual o significado de", "o que quer dizer",
      "que quer dizer", "o que vem a ser", "me explica o que sao", "o que significa",
      "que significa", "significado de", "o que seria", "o que sao", "oq sao", "o q sao",
      "que sao", "o que e", "oq e", "o q e", "que e", "defina", "define").stream()
      .sorted(Comparator.comparingInt((String f) -> f.split(" ").length)
          .thenComparingInt(String::length).reversed())
      .toList();

  /**
   * Palavras que não mudam a pergunta e não contam entre a fórmula e o termo ("o que é <b>o
   * imposto</b> IBS?"). Possessivo e demonstrativo de pessoa ("minha", "meu") ficam de fora de
   * propósito: são o que distingue "o que é a minha apuração?" (dado) de "o que é apuração?".
   */
  static final Set<String> IGNORAVEIS = Set.of("o", "a", "os", "as", "um", "uma", "esse", "essa",
      "este", "esta", "isso", "imposto", "tributo", "sigla", "termo", "palavra", "exatamente",
      "afinal", "mesmo");

  /**
   * O padrão de cada termo do mapa, ignorando a caixa: a mensagem chega normalizada em minúsculas
   * e o padrão de uma sigla no glossário é escrito como ela aparece no texto da Ceres
   * ({@code \bIBS\b}), que não casaria "o que é o ibs?". As formas que o padrão aceita (plural, o
   * {@code (?!\s+presumid)} do crédito) continuam valendo. Compilado uma vez.
   */
  private static final Map<String, Pattern> PADROES_SEM_CAIXA = termosComDispositivo().stream()
      .collect(Collectors.toUnmodifiableMap(GlossarioProdutor.Termo::termo,
          t -> Pattern.compile(t.padrao().pattern(),
              Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)));

  /** Fecho que só entra quando a citação veio da base: senão não há "fonte abaixo". */
  static final String COM_FONTE = "Esse é o sentido do termo em palavras simples; a lei que trata"
      + " dele está na fonte abaixo.";

  private final BuscaTextualLegislacaoService busca;

  /** Constrói o atalho sobre a busca por dispositivo exato. */
  public AtalhoDefinicao(BuscaTextualLegislacaoService busca) {
    this.busca = busca;
  }

  /**
   * {@code true} se a pergunta é uma definição que este atalho responde: sem nenhuma chamada ao
   * modelo, então a cota reserva só a leitura da mensagem (#511).
   *
   * @param pergunta pergunta do usuário
   * @return se a pergunta cai no atalho
   */
  public static boolean ehDefinicao(String pergunta) {
    return reconhecer(pergunta).isPresent();
  }

  /**
   * O termo do glossário que a pergunta pede por definição.
   *
   * @param pergunta pergunta do usuário
   * @return o termo, ou vazio (a pergunta segue o fluxo normal)
   */
  static Optional<GlossarioProdutor.Termo> reconhecer(String pergunta) {
    if (pergunta == null || pergunta.isBlank()) {
      return Optional.empty();
    }
    String normalizada = RegrasClassificacao.normalizar(pergunta);
    for (String formula : FORMULAS) {
      String resto = depoisDaFormula(normalizada, formula);
      if (resto != null) {
        Optional<GlossarioProdutor.Termo> termo = termoExato(resto);
        if (termo.isPresent()) {
          return termo;
        }
      }
    }
    return Optional.empty();
  }

  /**
   * A resposta do atalho: a definição do glossário, o fecho (só com citação) e a linha "O que isso
   * quer dizer" dos termos que a definição usa (a do IBS fala do ICMS e do ISS). Sem a base de
   * legislação, a citação sai vazia e o fecho não entra.
   *
   * <p>Só para termo que o {@link #reconhecer} devolveu: o atalho existe para os termos com
   * dispositivo curado, e um termo de fora não tem o que citar. O método é do pacote, como o
   * {@code OrquestradorService} que o chama, e a conferência deixa isso explícito em vez de
   * estourar num {@code List.of(null)} se um caminho novo o chamar errado.
   *
   * @param termo    termo reconhecido, do {@link #DISPOSITIVOS}
   * @param contexto contexto da pergunta ("hoje" da vigência, trechos da auditoria)
   * @return resposta de legislação aceita, sem fontes de dados
   * @throws IllegalArgumentException se o termo não tem dispositivo curado
   */
  ResultadoOrquestracao responder(GlossarioProdutor.Termo termo, ContextoAssistente contexto) {
    String dispositivo = DISPOSITIVOS.get(termo.termo());
    if (dispositivo == null) {
      throw new IllegalArgumentException(
          "Termo sem dispositivo curado no atalho de definicao: " + termo.termo());
    }
    List<CitacaoLegal> citacoes = CitacoesFixas.buscar(busca,
        List.of(dispositivo), contexto, "Atalho de definicao");
    String texto = definicao(termo) + (citacoes.isEmpty() ? "" : "\n\n" + COM_FONTE);
    return new ResultadoOrquestracao(Intencao.LEGISLACAO,
        LinhaGlossario.acrescentadaA(texto), DesfechoInteracao.DEFINICAO_DO_GLOSSARIO,
        citacoes, false);
  }

  /** "IBS (imposto sobre bens e serviços): definição." ou "crédito: definição." */
  static String definicao(GlossarioProdutor.Termo termo) {
    String rotulo = termo.nome() == null
        ? termo.termo()
        : termo.termo() + " (" + termo.nome() + ")";
    return rotulo + ": " + termo.explicacao() + ".";
  }

  /**
   * O que vem depois da fórmula no começo da mensagem, sem as palavras ignoráveis; nulo se a
   * mensagem não começa por ela.
   */
  private static String depoisDaFormula(String normalizada, String formula) {
    if (!normalizada.equals(formula) && !normalizada.startsWith(formula + " ")) {
      return null;
    }
    String resto = normalizada.substring(formula.length()).strip();
    StringBuilder semIgnoraveis = new StringBuilder();
    for (String palavra : resto.isEmpty() ? new String[0] : resto.split(" ")) {
      if (!IGNORAVEIS.contains(palavra)) {
        semIgnoraveis.append(semIgnoraveis.isEmpty() ? "" : " ").append(palavra);
      }
    }
    return semIgnoraveis.toString();
  }

  /**
   * O termo do mapa que o resto da mensagem nomeia, e nada mais: o padrão do glossário o acha e o
   * resto tem o mesmo número de palavras que ele. Assim "creditos" vale por "crédito" e "credito
   * presumido" vai para o termo próprio, mas "aliquota do ibs" não vale por "alíquota".
   */
  private static Optional<GlossarioProdutor.Termo> termoExato(String resto) {
    if (resto.isEmpty()) {
      return Optional.empty();
    }
    int palavras = resto.split(" ").length;
    return termosComDispositivo().stream()
        .filter(t -> palavras == t.termo().split(" ").length
            && PADROES_SEM_CAIXA.get(t.termo()).matcher(resto).find())
        .findFirst();
  }

  /** Os termos do glossário que têm dispositivo curado, na ordem do glossário. */
  static List<GlossarioProdutor.Termo> termosComDispositivo() {
    return GlossarioProdutor.padrao().termos().stream()
        .filter(t -> DISPOSITIVOS.containsKey(t.termo()))
        .toList();
  }

  /**
   * Todo texto que este atalho pode entregar, pelo nome, para os testes de texto fixo (acento,
   * sigla, textos proibidos) — mesmo papel do {@code ConversaLeve.textos()}. A definição de cada
   * termo vem do glossário, que tem os próprios testes; aqui ela entra montada, como sai na
   * resposta.
   *
   * @return nome → texto, na ordem do glossário
   */
  public static Map<String, String> textos() {
    Map<String, String> textos = new LinkedHashMap<>();
    textos.put("COM_FONTE", COM_FONTE);
    termosComDispositivo().forEach(t -> textos.put("definicao " + t.termo(), definicao(t)));
    return textos;
  }

  /** O mapa na ordem do glossário, para o relatório e os testes. */
  static Map<String, String> dispositivosNaOrdemDoGlossario() {
    Map<String, String> ordenado = new LinkedHashMap<>();
    termosComDispositivo().forEach(t -> ordenado.put(t.termo(), DISPOSITIVOS.get(t.termo())));
    return ordenado;
  }
}
