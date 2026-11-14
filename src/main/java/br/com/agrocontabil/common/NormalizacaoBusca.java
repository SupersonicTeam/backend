package br.com.agrocontabil.common;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Normalização do texto de busca (issue #174): minúsculas, sem acento, espaços colapsados.
 *
 * <p>A mesma regra vale para o que é gravado (a coluna {@code nota_fiscal.busca_normalizada}) e
 * para o termo que o cliente digita, então "AGRÍCOLA" casa com "agricola". Roda em Java nos dois
 * lados, e não no banco, para dar o mesmo resultado no PostgreSQL e no H2 dos testes.
 */
public final class NormalizacaoBusca {

  /** Marcas diacríticas que sobram da decomposição NFD ("á" → "a" + acento agudo). */
  private static final Pattern DIACRITICOS = Pattern.compile("\\p{M}+");

  private static final Pattern ESPACOS = Pattern.compile("\\s+");

  /**
   * Tamanho da coluna {@code nota_fiscal.busca_normalizada}: 600 no changeSet 0037, 8000 desde o
   * 0056 (descrição e NCM dos itens entram no texto, #271).
   */
  public static final int TAMANHO_MAXIMO = 8000;

  /**
   * Versão da regra do texto pesquisável da nota, gravada em {@code nota_fiscal.busca_versao}.
   * Mudou o que entra no texto, suba o número: o {@code BuscaNotasLegadoRunner} recalcula, em
   * segundo plano, toda nota de versão menor. 1 = número, destinatário e natureza (#174);
   * 2 = mais documento do destinatário e descrição e NCM dos itens (#271); 3 = sem o documento do
   * destinatário, que a busca acha por igualdade na própria coluna (#399).
   */
  public static final int VERSAO_REGRA = 3;

  private NormalizacaoBusca() {
  }

  /**
   * Normaliza um texto: sem acento, em minúsculas e com espaços colapsados.
   *
   * <p>Depois de tirar as marcas, recompõe em NFC: escritas como o coreano se decompõem no NFD em
   * letras que não são marcas (uma sílaba vira dois ou três jamo), e sem recompor o texto crescia
   * até três vezes (issue #174, achado do review).
   *
   * @param texto texto livre (pode ser {@code null})
   * @return texto normalizado; vazio para {@code null}
   */
  public static String normalizar(String texto) {
    if (texto == null) {
      return "";
    }
    String semAcento = Normalizer.normalize(
        DIACRITICOS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll(""),
        Normalizer.Form.NFC);
    return ESPACOS.matcher(semAcento.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
  }

  /**
   * Junta e normaliza as partes, ignorando as nulas, cortado no tamanho da coluna.
   *
   * @param partes partes do texto pesquisável
   * @return partes normalizadas separadas por espaço, até {@link #TAMANHO_MAXIMO} caracteres
   */
  public static String juntar(Object... partes) {
    String junto = normalizar(Stream.of(partes).filter(Objects::nonNull).map(String::valueOf)
        .collect(Collectors.joining(" ")));
    return junto.length() > TAMANHO_MAXIMO ? junto.substring(0, TAMANHO_MAXIMO) : junto;
  }

  /** Item da nota como entra no texto pesquisável. */
  public record ItemBusca(String descricao, String ncm) {}

  /**
   * Texto pesquisável da nota (#271) e quantos itens não couberam nele.
   *
   * @param texto           texto normalizado, até {@link #TAMANHO_MAXIMO} caracteres
   * @param itensForaDaBusca itens distintos que não couberam (a busca não os acha)
   */
  public record TextoNota(String texto, int itensForaDaBusca) {}

  /**
   * Monta o texto pesquisável da nota: número, nome do destinatário e natureza e, depois,
   * "descrição NCM" de cada item. Regra única da gravação ({@code NotaFiscal}) e do backfill
   * ({@code BuscaNotasLegadoRunner}).
   *
   * <p><strong>Sem o CPF/CNPJ do destinatário (issue #399).</strong> Ele entrou na regra 2 (#271)
   * e deixava uma cópia a mais do documento em claro, inclusive no índice de trigrama. A busca por
   * documento é por igualdade na coluna {@code destinatario_cpf_cnpj}
   * ({@code NotaFiscalEspecificacoes.comDocumento}), que uma cifra futura das notas cobre do mesmo
   * jeito que cobre as buscas por CPF da carteira.
   *
   * <p><strong>Regra do limite:</strong> o cabeçalho entra primeiro e inteiro; os itens entram na
   * ordem, um por vez e inteiros, e o que não couber em {@link #TAMANHO_MAXIMO} fica de fora e é
   * contado em {@link TextoNota#itensForaDaBusca()} (quem grava avisa no log). Itens com o mesmo
   * texto contam uma vez só. Nada é cortado no meio de uma palavra.
   *
   * @param numero           número da nota
   * @param destinatarioNome nome do destinatário
   * @param naturezaOperacao natureza da operação
   * @param itens            itens da nota, na ordem
   * @return o texto e a contagem dos itens que ficaram de fora
   */
  public static TextoNota montarTextoDaNota(Object numero, String destinatarioNome,
      String naturezaOperacao, List<ItemBusca> itens) {
    String cabecalho = juntar(numero, destinatarioNome, naturezaOperacao);
    StringBuilder texto = new StringBuilder(cabecalho);
    Set<String> vistos = new LinkedHashSet<>();
    int fora = 0;
    for (ItemBusca item : itens) {
      String parte = juntar(item.descricao(), item.ncm());
      if (parte.isEmpty() || !vistos.add(parte)) {
        continue;
      }
      int acrescimo = parte.length() + (texto.isEmpty() ? 0 : 1);
      if (texto.length() + acrescimo <= TAMANHO_MAXIMO) {
        texto.append(texto.isEmpty() ? "" : " ").append(parte);
      } else {
        fora++;
      }
    }
    return new TextoNota(texto.toString(), fora);
  }

  /**
   * Escapa os curingas do {@code LIKE} ({@code %}, {@code _}) e o próprio caractere de escape
   * (a barra invertida): o que o cliente digita é texto, nunca padrão.
   *
   * @param termo termo já normalizado
   * @return termo seguro para {@code LIKE} com a barra invertida como escape
   */
  public static String escaparLike(String termo) {
    return termo.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
