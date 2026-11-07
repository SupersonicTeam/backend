package br.com.agrocontabil.assistente.ferramentas;

import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.Alternativa;
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

  static final String RESSALVA = "Confira com seu contador se a sua venda atende à condição.";

  /**
   * Sem condição nem alternativa (#443): o percentual da tabela também não sai como definitivo,
   * porque o enquadramento do produto vendido no NCM e no anexo é do contador (decisão da #284).
   */
  static final String RESSALVA_ENQUADRAMENTO =
      "O enquadramento do seu produto nessa classificação se confirma com o seu contador.";

  /** Com duas situações (o leite, #284), quem escolhe é o contador, não a tabela. */
  static final String RESSALVA_ALTERNATIVA =
      "O enquadramento da sua venda em uma das duas situações é com o seu contador.";

  private static final Map<Integer, String> ROMANOS =
      Map.of(1, "I", 7, "VII", 9, "IX", 15, "XV");

  private TemplateTratamentoProduto() {}

  /** Frase sem produto achado: também é a frase fixa da negativa (#274). */
  public static String naoEncontrado(String termo) {
    return "Não encontrei " + (termo == null || termo.isBlank() ? "esse produto" : termo)
        + " na tabela oficial de classificação tributária do IBS e da CBS. Tente outro nome do"
        + " produto ou o código NCM que está na nota fiscal dele.";
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
    boolean comAlternativa = false;
    for (Produto p : consulta.produtos()) {
      frases.add(frase(p));
      // A condicao do produto com alternativa ja tem a ressalva propria (as duas situacoes).
      comCondicao |= p.condicao() != null && p.alternativa() == null;
      comAlternativa |= p.alternativa() != null;
    }
    String fonte = consulta.versaoTabela() == null
        ? "Tratamento pela tabela oficial de classificação tributária."
        : "Tratamento pela tabela oficial de classificação tributária (" + consulta.versaoTabela()
            + ").";
    StringBuilder fim = new StringBuilder(fonte);
    if (comCondicao) {
      fim.append(' ').append(RESSALVA);
    }
    if (comAlternativa) {
      fim.append(' ').append(RESSALVA_ALTERNATIVA);
    }
    if (!comCondicao && !comAlternativa) {
      fim.append(' ').append(RESSALVA_ENQUADRAMENTO);
    }
    frases.add(fim.toString());
    return String.join("\n", frases);
  }

  /** Dispositivos citados ({@code norma|art. N}), sem repetir, na ordem dos produtos. */
  public static List<String> dispositivos(TratamentoConsultado consulta) {
    Set<String> dispositivos = new LinkedHashSet<>();
    for (Produto p : consulta.produtos()) {
      if (p.artigo() != null) {
        dispositivos.add(NORMA + "|art. " + p.artigo());
      }
      if (p.alternativa() != null && p.alternativa().artigo() != null) {
        dispositivos.add(NORMA + "|art. " + p.alternativa().artigo());
      }
    }
    return List.copyOf(dispositivos);
  }

  /** Frase de um produto (também usada pelo atalho de legislação, #347). */
  public static String frase(Produto p) {
    StringBuilder s = new StringBuilder(nome(p)).append(": ");
    if (!p.ncmConhecido()) {
      // Codigo fora da referencia e de todo anexo: pode nem existir. Nada de afirmar.
      return s.append("não encontrei esse código na referência de NCM nem nos anexos de redução"
          + " da LC 214/2025. Se o código estiver certo, não há redução e o IBS e a CBS são"
          + " cobrados pela alíquota cheia (cClassTrib ").append(p.cClassTrib())
          .append("). Confira o NCM na nota fiscal do produto.").toString();
    }
    if (p.percentualReducao().signum() == 0) {
      s.append("sem redução; o IBS e a CBS são cobrados pela alíquota cheia (cClassTrib ")
          .append(p.cClassTrib()).append(").");
    } else {
      s.append("redução de ").append(percentual(p.percentualReducao()))
          .append(" do IBS e da CBS");
      if (p.percentualReducao().compareTo(BigDecimal.valueOf(100)) == 0) {
        s.append(" (alíquota zero)");
      }
      String fundamento = fundamento(p);
      s.append(", ").append(hipotese(p)).append(" (")
          .append(fundamento == null ? "" : fundamento + "; ")
          .append("cClassTrib ").append(p.cClassTrib()).append(").");
    }
    if (p.condicao() != null) {
      s.append(" Condição: ").append(p.condicao()).append('.');
    }
    if (p.alternativa() != null) {
      s.append(' ').append(alternativa(p.alternativa()));
    }
    return s.toString();
  }

  /** A outra situação do produto, que vale só com o cClassTrib informado (o leite, #284). */
  private static String alternativa(Alternativa a) {
    StringBuilder s = new StringBuilder("Já o ").append(a.condicao()).append(" tem ");
    if (a.percentualReducao().compareTo(BigDecimal.valueOf(100)) == 0) {
      s.append("alíquota zero");
    } else {
      s.append("redução de ").append(percentual(a.percentualReducao()));
    }
    s.append(" do IBS e da CBS (");
    if (a.artigo() != null) {
      s.append(NORMA).append(", art. ").append(a.artigo());
      if (a.anexo() != null) {
        s.append(", Anexo ").append(romano(a.anexo()));
      }
      s.append("; ");
    }
    return s.append("cClassTrib ").append(a.cClassTrib()).append("), com esse cClassTrib")
        .append(" informado no item da nota.").toString();
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
      return "como produto agropecuário in natura";
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
