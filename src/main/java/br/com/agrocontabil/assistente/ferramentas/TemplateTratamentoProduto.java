package br.com.agrocontabil.assistente.ferramentas;

import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.Produto;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.TratamentoConsultado;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Texto da resposta sobre o tratamento de um produto (issue #283): montado pelo backend a partir
 * da tabela oficial, com a citação vinda do dado (o artigo e o anexo do {@code cClassTrib}), nunca
 * do modelo. O {@code OrquestradorService} o põe na resposta depois da validação.
 */
public final class TemplateTratamentoProduto {

  /** Norma de todos os dispositivos da tabela de redução. */
  public static final String NORMA = "LC 214/2025";

  static final int DESCRICAO_MAXIMA = 110;

  static final String RESSALVA = "Confira com seu contador se a sua venda atende a condicao.";

  private static final Map<Integer, String> ROMANOS =
      Map.of(1, "I", 7, "VII", 9, "IX", 15, "XV");

  private TemplateTratamentoProduto() {}

  /** Frase sem produto achado: também é a frase fixa da negativa (#274). */
  public static String naoEncontrado(String termo) {
    return "Nao encontrei " + (termo == null || termo.isBlank() ? "esse produto" : termo)
        + " na tabela oficial de classificacao tributaria do IBS e da CBS. Tente outro nome do"
        + " produto ou o codigo NCM que esta na nota fiscal dele.";
  }

  /**
   * Texto completo da resposta.
   *
   * @param consulta resultado da porta
   * @return parágrafo pronto para o usuário
   */
  public static String texto(TratamentoConsultado consulta) {
    if (consulta.produtos().isEmpty()) {
      return naoEncontrado(consulta.termo());
    }
    List<String> frases = new ArrayList<>();
    boolean comCondicao = false;
    for (Produto p : consulta.produtos()) {
      frases.add(frase(p));
      comCondicao |= p.condicao() != null;
    }
    String fonte = consulta.versaoTabela() == null
        ? "Tratamento pela tabela oficial de classificacao tributaria."
        : "Tratamento pela tabela oficial de classificacao tributaria (" + consulta.versaoTabela()
            + ").";
    frases.add(comCondicao ? fonte + " " + RESSALVA : fonte);
    return String.join("\n", frases);
  }

  /** Dispositivos citados ({@code norma|art. N}), sem repetir, na ordem dos produtos. */
  public static List<String> dispositivos(TratamentoConsultado consulta) {
    Set<String> dispositivos = new LinkedHashSet<>();
    for (Produto p : consulta.produtos()) {
      if (p.artigo() != null) {
        dispositivos.add(NORMA + "|art. " + p.artigo());
      }
    }
    return List.copyOf(dispositivos);
  }

  /** Frase de um produto (também usada pelo atalho de legislação, #347). */
  public static String frase(Produto p) {
    StringBuilder s = new StringBuilder(nome(p)).append(": ");
    if (!p.ncmConhecido()) {
      // Codigo fora da referencia e de todo anexo: pode nem existir. Nada de afirmar.
      return s.append("nao encontrei esse codigo na referencia de NCM nem nos anexos de reducao"
          + " da LC 214/2025. Se o codigo estiver certo, nao ha reducao e o IBS e a CBS sao"
          + " cobrados pela aliquota cheia (cClassTrib ").append(p.cClassTrib())
          .append("). Confira o NCM na nota fiscal do produto.").toString();
    }
    if (p.percentualReducao().signum() == 0) {
      s.append("sem reducao; o IBS e a CBS sao cobrados pela aliquota cheia (cClassTrib ")
          .append(p.cClassTrib()).append(").");
    } else {
      s.append("reducao de ").append(percentual(p.percentualReducao()))
          .append(" do IBS e da CBS");
      if (p.percentualReducao().compareTo(BigDecimal.valueOf(100)) == 0) {
        s.append(" (aliquota zero)");
      }
      String fundamento = fundamento(p);
      s.append(", ").append(hipotese(p)).append(" (")
          .append(fundamento == null ? "" : fundamento + "; ")
          .append("cClassTrib ").append(p.cClassTrib()).append(").");
    }
    if (p.condicao() != null) {
      s.append(" Condicao: ").append(p.condicao()).append('.');
    }
    return s.toString();
  }

  /** Dispositivo legível: "LC 214/2025, art. 137" ou "LC 214/2025, art. 138, Anexo IX". */
  public static String fundamento(Produto p) {
    if (p.artigo() == null) {
      return null;
    }
    String f = NORMA + ", art. " + p.artigo();
    return p.anexo() == null ? f : f + ", Anexo " + romano(p.anexo());
  }

  private static String hipotese(Produto p) {
    if (p.inNatura()) {
      return "como produto agropecuario in natura";
    }
    return p.anexo() == null ? "pela tabela oficial" : "pelo Anexo " + romano(p.anexo());
  }

  private static String nome(Produto p) {
    String descricao = curta(p.descricao());
    if (p.ncm() != null) {
      String ncm = ncm(p.ncm());
      return descricao == null ? "NCM " + ncm : descricao + " (NCM " + ncm + ")";
    }
    String grupo = p.quantidadeNcms() + " NCMs";
    return descricao == null ? grupo : descricao + " (" + grupo + ")";
  }

  private static String curta(String descricao) {
    if (descricao == null || descricao.isBlank()) {
      return null;
    }
    String d = descricao.strip();
    if (d.length() <= DESCRICAO_MAXIMA) {
      return d;
    }
    int corte = d.lastIndexOf(' ', DESCRICAO_MAXIMA);
    return d.substring(0, corte > 0 ? corte : DESCRICAO_MAXIMA).replaceAll("[,;:]$", "") + "...";
  }

  /** {@code 12019000} → {@code 1201.90.00}. */
  public static String ncm(String ncm) {
    return ncm.length() == 8
        ? ncm.substring(0, 4) + "." + ncm.substring(4, 6) + "." + ncm.substring(6) : ncm;
  }

  /** {@code 60.00} → {@code 60%}; {@code 30.50} → {@code 30,5%}. */
  public static String percentual(BigDecimal valor) {
    return valor.stripTrailingZeros().toPlainString().replace('.', ',') + "%";
  }

  private static String romano(int anexo) {
    return ROMANOS.getOrDefault(anexo, String.valueOf(anexo));
  }
}
