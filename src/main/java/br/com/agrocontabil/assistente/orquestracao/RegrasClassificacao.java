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
 * </ul>
 */
public final class RegrasClassificacao {

  private static final Pattern ACENTOS = Pattern.compile("\\p{M}+");

  /** Termos de lei inequívocos da reforma e do Funrural. */
  private static final Pattern TERMO_LEI = Pattern.compile("\\b(ibs|cbs|split payment|funrural"
      + "|senar|credito presumido|anexo [ivxl]+|fato gerador|aliquotas?|lc 214|lei complementar"
      + " 214|ec 132|emenda constitucional|nao contribuintes?|regime regular|cesta basica"
      + "|reforma tributaria|imposto seletivo|lei 8[ .]?212|lei 9[ .]?528|reducao|reduzidas?"
      + "|isencao|isent[oa]s?|imunidade|imunes?|insumos? agropecuarios?|lei|legislacao|artigo"
      + "|art)\\b");

  /** O produtor falando dos dados dele no sistema. */
  private static final Pattern MARCADOR_DADOS = Pattern.compile(String.join("|",
      "\\bminh[ao]s? (\\d+ )?(ultim[ao]s? )?(notas?|vendas?|compras?|guias?|apuracao|apuracoes"
          + "|creditos?|alertas?|pendencias?|retencoes|debitos?|proxima guia)\\b",
      "\\bmeus? (\\d+ )?(ultim[ao]s? )?(creditos?|debitos?|alertas?|vencimentos?|pedidos?)\\b",
      "\\bquanto (eu )?(paguei|vendi|comprei|gastei|devo|faturei|recebi)\\b",
      "\\bquanto (eu )?tenho\\b",
      "\\b(eu|que) devo\\b",
      "\\b(janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro"
          + "|dezembro) de \\d{4}\\b",
      "\\bo que eu (comprei|vendi|paguei)\\b",
      "\\bnotas? (n[or]?\\s?)?\\d{2,9}\\b",
      "\\btenho (algum|alguma|algo|imposto|guias?|notas?|debitos?)\\b",
      "\\b(o que|quais impostos|que impostos|quais guias|guias? que) vence[m]?\\b",
      "\\bvence[m]? (este|esse|neste|nesse) mes\\b",
      "\\bquantas (notas|vendas|compras|guias)\\b"));

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
    if (lei && !dados) {
      return PRODUTO.matcher(texto).find() ? Optional.empty() : Optional.of(Intencao.LEGISLACAO);
    }
    if (dados && !lei) {
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
