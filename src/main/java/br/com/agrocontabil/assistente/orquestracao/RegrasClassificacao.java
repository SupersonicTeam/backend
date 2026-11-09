package br.com.agrocontabil.assistente.orquestracao;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Primeira camada da classificação (issue #265): regras determinísticas, só para o inequívoco.
 *
 * <p><strong>A regra só decide com sinal de um lado só.</strong> Termo de lei sem marcador de
 * dados do produtor → {@link Intencao#LEGISLACAO}; marcador de dados sem termo de lei →
 * {@link Intencao#DADOS}. Sinais dos dois lados (candidato a MISTO), nenhum sinal ou um bloqueio
 * deixam a pergunta para as camadas seguintes (embeddings e, na dúvida, o modelo): assim a regra
 * nunca erra sem rede de segurança. A precisão de cada camada é medida na avaliação (#264), e a
 * regra que errar qualquer caso que decide é removida ou restringida.
 *
 * <ul>
 *   <li><strong>Marcador de dados</strong> é o produtor falando dos dados dele ("minhas notas",
 *       "quanto vendi", "a nota 107", "tenho alguma guia"). Pronome solto não conta: "meu vizinho
 *       paga Funrural?" e "minha dúvida é sobre o split payment" não falam dos dados dele.</li>
 *   <li><strong>Lei + produto específico</strong> ("arroz paga IBS?") não é decidido aqui: o
 *       tratamento de um produto sai da tabela oficial pela rota de dados (#283), e a regra geral
 *       ("hortícolas têm alíquota zero?") pela de lei — fronteira para as embeddings.</li>
 *   <li><strong>Bloqueio:</strong> pedido criativo, de recomendação ou de cotação ("escreva uma
 *       música sobre o IBS", "indique um contador para o meu Funrural") nunca é decidido pela
 *       regra, por mais que cite um termo de lei.</li>
 *   <li><strong>Caso hipotético</strong> com tema tributário e sem dado real do produtor ("se eu
 *       vender milho, o Funrural vence no mês seguinte?") é LEGISLACAO; o tratamento de um produto
 *       ("se eu vender arroz, a alíquota é zero?") segue para as camadas seguintes (#452).</li>
 *   <li><strong>Guia do produtor a pagar num período próximo</strong> ("tem guia de Funrural pra
 *       pagar no mês que vem?") é DADOS, mesmo com o nome do tributo; com regra da obrigação
 *       ("todo mês", "posso parcelar", "mês seguinte à venda"), não decide (#452).</li>
 * </ul>
 */
public final class RegrasClassificacao {

  private static final Pattern ACENTOS = Pattern.compile("\\p{M}+");

  /**
   * Termos de lei inequívocos <strong>da reforma e do Funrural</strong>. Palavra de lei genérica
   * ("lei", "artigo", "alíquota", "redução", "isenção", "fato gerador") não conta sozinha: "qual a
   * alíquota do ICMS?", "há isenção de IPVA?" e "qual a lei de Murphy?" não são da base da Ceres, e
   * a regra as mandaria ao RAG com confiança 1, sem o desempate pelo modelo.
   */
  private static final Pattern TERMO_LEI = Pattern.compile("\\b(ibs|cbs|split payment|funrural"
      + "|senar|credito presumido|anexo [ivxl]+|lc 214|lei complementar 214|ec 132"
      + "|emenda constitucional 132|regime regular|cesta basica|reforma tributaria"
      + "|imposto seletivo|lei 8[ .]?212|lei 9[ .]?528|insumos? agropecuarios?)\\b");

  /**
   * Palavra de lei genérica: não decide LEGISLACAO, mas impede a regra de decidir DADOS — "quanto
   * vendi de café e café tem redução?" é candidato a MISTO.
   */
  private static final Pattern LEI_GENERICA = Pattern.compile("\\b(lei|legislacao|artigo|art"
      + "|aliquotas?|reducao|reduzidas?|isencao|isent[oa]s?|imunidade|imunes?|fato gerador"
      + "|contribuintes?|regra|permite|pode)\\b");

  /**
   * Quantia ou período do próprio produtor ("quanto de IBS eu devo?", "o saldo de CBS de agosto
   * de 2026"): com termo de lei, pode ser dado dele ou MISTO — a regra não decide LEGISLACAO.
   */
  private static final Pattern QUANTIA_PESSOAL = Pattern.compile("\\b(quanto|quanta|devo"
      + "|paguei|retido|retida|ficou|saldo|apuracao|(janeiro|fevereiro|marco|abril|maio|junho"
      + "|julho|agosto|setembro|outubro|novembro|dezembro) de \\d{4})\\b");

  /** Insumo, imposto ou documento do produtor: a âncora dos verbos de gasto e pagamento. */
  private static final String OBJETO_DADOS = "(insumos?|defensivos?|fertilizantes?|adubos?"
      + "|sementes?|diesel|oleo diesel|racao|guias?|impostos?|notas?)";

  /**
   * O produtor falando dos dados dele no sistema, sempre ancorado no domínio: possessivo ou verbo
   * do produtor junto de nota, guia, venda, compra, crédito ou apuração. Mês com ano, "que devo",
   * "quanto tenho" e "tenho algum" soltos não contam: "o preço do dólar em março de 2026", "quanto
   * eu devo ao banco" e "tenho algum direito trabalhista" iriam à rota de dados com confiança 1.
   */
  private static final Pattern MARCADOR_DADOS = Pattern.compile(String.join("|",
      "\\bminh[ao]s? (\\d+ )?(ultim[ao]s? )?(notas?|vendas?|compras?|guias?|apuracao|apuracoes"
          + "|creditos?|alertas?|pendencias?|retencoes|debitos?|proxima guia)\\b",
      "\\bmeus? (\\d+ )?(ultim[ao]s? )?(creditos?|debitos?|alertas?|vencimentos?|pedidos?)\\b",
      "\\bquanto (eu )?(vendi|comprei|faturei)\\b",
      "\\bquanto (eu )?(gastei|paguei|recebi) (com|de|em) (o |a |os |as )?" + OBJETO_DADOS
          + "\\b",
      "\\bo que eu (comprei|vendi)\\b",
      "\\bnotas? (n[or]?\\s?)?\\d{2,9}\\b",
      "\\btenho (algum |alguma )?(guias?|notas?|creditos?|debitos?|alertas?|pendencias?)\\b",
      "\\btenho impostos? (a|para) pagar\\b",
      "\\b(quais impostos|que impostos|quais guias|guias? que) vence[m]?\\b",
      "\\bo que vence (este|esse|neste|nesse) mes\\b",
      "\\bquantas (notas|guias)\\b",
      "\\bquantas (vendas|compras) (eu )?(fiz|tive|emiti)\\b"));

  /** Produto específico: com termo de lei, a fronteira dados (#283) × lei fica para depois. */
  private static final Pattern PRODUTO = Pattern.compile("\\b(soja|milho|arroz|feijao|feijoes"
      + "|cafe|leite|trigo|algodao|ovos?|tomates?|bois?|bovinos?|gado|adubos?|fertilizantes?"
      + "|sementes?|defensivos?|glifosato|frutas?|horticolas?|hortalicas?|carnes?|suinos?"
      + "|frangos?|cana|laranjas?|uvas?|mandioca|batatas?|cebolas?|alho|racao|calcario"
      + "|ureia|sorgo|cevada|aveia|amendoim|girassol|eucalipto|madeira|mel|peixes?)\\b");

  /** Pedido que não é consulta: criação, tradução, recomendação, cotação, conversa. */
  private static final Pattern BLOQUEIO = Pattern.compile("\\b(escrev[ae]r?|poemas?|musicas?"
      + "|piadas?|historias?|receitas? de|traduz[ai]r?|traducao|indiqu?e|indicar|recomend[ae]r?"
      + "|melhor(es)?|cotacao|cotacoes|previsao do tempo|futebol|campeonato)\\b");

  /**
   * Caso hipotético ("se eu vender soja hoje...", "suponha que...", "em tese"): o produtor pergunta
   * a regra de uma situação, não os dados dele (#452, rodada 2). Com tema tributário e sem dado
   * real do produtor, é LEGISLACAO: um período citado no caso ("vence no mês seguinte") não pode
   * puxar uma parte de dados, que cairia num mês que a pergunta não pediu.
   */
  private static final Pattern HIPOTESE = Pattern.compile("\\b(?:se|caso) (?:eu |o produtor "
      + "|um produtor |a gente |nos |ele )?(?:vend|compr|emit|export|fatur|receb|pag|colh|entreg"
      + "|tiver|tivesse|fizer|fosse|for\\b)\\w*"
      + "|\\b(?:suponha|supondo|hipotetic\\w*|imagine|imaginando|digamos que|em tese)\\b");

  /** O que já aconteceu nos dados do produtor ("vendi", "paguei"): não é hipótese. */
  private static final Pattern FATO_DO_PRODUTOR = Pattern.compile("\\b(vendi|comprei|paguei"
      + "|emiti|recebi|faturei|colhi|entreguei|exportei|tive|fiz)\\b");

  /** Tema tributário do caso hipotético. */
  private static final Pattern TEMA_TRIBUTARIO = Pattern.compile("\\b(impostos?|tributos?"
      + "|tribut\\w*|guias?|venc\\w*|prazos?|recolh\\w*|retenc\\w*|retid[oa]s?|creditos?"
      + "|debitos?|nota fiscal|notas fiscais|darfs?)\\b");

  /**
   * Tratamento de um produto ("se eu vender arroz, a alíquota é zero?"): fica para as camadas
   * seguintes, que podem mandar à tabela oficial pela rota de dados (#283).
   */
  private static final Pattern TRATAMENTO = Pattern.compile("\\b(aliquotas?|reducao|reduzid\\w*"
      + "|isencao|isent\\w*|zero|zerad\\w*|tributad\\w*)\\b");

  /**
   * O produtor perguntando se tem guia a pagar num período próximo ("tem guia de Funrural pra
   * pagar no mês que vem?") (#452, rodada 2): é dado dele, mesmo com o nome do tributo, que é só
   * o tipo da guia.
   */
  private static final Pattern GUIA_DO_PRODUTOR = Pattern.compile("\\b(?:tem|tenho|ha|existe"
      + "|vou ter|terei)(?: alguma| algum)? (?:guias?|boletos?|darfs?)\\b");

  private static final Pattern PAGAR = Pattern.compile("\\b(?:pagar|vencer|vence|vencem"
      + "|vencendo|quitar|recolher)\\b");

  private static final Pattern PERIODO_PROXIMO = Pattern.compile("\\b(?:mes que vem|proximo mes"
      + "|mes seguinte(?! (?:a|ao|aos|as)\\b)|(?:n?est|n?ess)[ea] (?:mes|semana)"
      + "|mes (?:atual|corrente)|semana que vem|proxima semana|amanha|hoje)\\b");

  /** Regra da obrigação ("todo mês", "posso parcelar", "como pago"): não é só o dado. */
  private static final Pattern REGRA_DA_GUIA = Pattern.compile("\\b(?:todo mes|todos os meses"
      + "|mensal\\w*|sempre|posso|parcel\\w*|como)\\b");

  private RegrasClassificacao() {}

  /**
   * Decide a rota quando o sinal é inequívoco.
   *
   * @param pergunta pergunta do usuário
   * @return {@code LEGISLACAO} ou {@code DADOS}; vazio para as camadas seguintes
   */
  public static Optional<Intencao> decidir(String pergunta) {
    String texto = normalizar(pergunta);
    if (texto.isEmpty() || BLOQUEIO.matcher(texto).find()) {
      return Optional.empty();
    }
    boolean lei = TERMO_LEI.matcher(texto).find();
    boolean dados = MARCADOR_DADOS.matcher(texto).find();
    if (HIPOTESE.matcher(texto).find() && !dados && !FATO_DO_PRODUTOR.matcher(texto).find()
        && (lei || TEMA_TRIBUTARIO.matcher(texto).find())
        && !(PRODUTO.matcher(texto).find() && TRATAMENTO.matcher(texto).find())) {
      return Optional.of(Intencao.LEGISLACAO);
    }
    if (GUIA_DO_PRODUTOR.matcher(texto).find() && PAGAR.matcher(texto).find()
        && PERIODO_PROXIMO.matcher(texto).find() && !LEI_GENERICA.matcher(texto).find()
        && !REGRA_DA_GUIA.matcher(texto).find()) {
      return Optional.of(Intencao.DADOS);
    }
    if (lei && !dados) {
      return PRODUTO.matcher(texto).find() || QUANTIA_PESSOAL.matcher(texto).find()
          ? Optional.empty() : Optional.of(Intencao.LEGISLACAO);
    }
    if (dados && !lei && !LEI_GENERICA.matcher(texto).find()) {
      return Optional.of(Intencao.DADOS);
    }
    return Optional.empty();
  }

  /** Sem acento, minúsculas, pontuação vira espaço (exceto ponto entre dígitos). */
  static String normalizar(String pergunta) {
    if (pergunta == null) {
      return "";
    }
    String semAcento = ACENTOS.matcher(Normalizer.normalize(pergunta, Normalizer.Form.NFD))
        .replaceAll("").toLowerCase(Locale.ROOT);
    return semAcento.replaceAll("(?<!\\d)[.]|[.](?!\\d)", " ")
        .replaceAll("[^a-z0-9. ]+", " ")
        .replaceAll("\\s+", " ")
        .strip();
  }
}
