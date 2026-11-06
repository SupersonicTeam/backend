package br.com.agrocontabil.nfe;

import br.com.agrocontabil.common.UfIbge;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import java.util.List;
import java.util.Optional;

/**
 * Regra única do grupo {@code ICMS} do item da NF-e: qual CST vai ao XML, à DANFE, ao validador
 * de emissão e ao sinal de nota a conferir. O sistema <strong>não calcula ICMS</strong> (premissas,
 * #280): só emite o grupo com o CST informado pelo usuário ou pelo contador, ou o padrão do único
 * caso confirmado pelo escritório (#284, pergunta 9).
 *
 * <p><strong>Por que o grupo sai sempre:</strong> a NT 2025.002 v1.53 deixou o {@code N01 ICMS}
 * com ocorrência 0-1 no leiaute (e o XSD PL_010g com a escolha {@code minOccurs="0"}), mas só para
 * as NF-e de crédito e débito. A regra B25-90 (obrigatória, rejeição 1002 "NF-e sem informação de
 * ICMS / ISSQN") recusa a NF-e de outra finalidade sem o grupo {@code ICMS} nem o {@code ISSQN}.
 * Antes desta regra, toda NF-e do sistema ({@code finNFe = 1}) seria rejeitada na SEFAZ real.
 *
 * <p><strong>Padrão (diferimento total, CST 51):</strong> venda <em>interna</em> de produção do
 * estabelecimento (CFOP {@value #CFOP_VENDA_PRODUCAO_INTERNA}) para destinatário contribuinte
 * (com IE). É o caso que o escritório confirmou: a soja em grão vendida pelo produtor de MT dentro
 * do estado sai com o ICMS diferido, CST 51, ICMS próprio zero. O grupo {@code ICMS51} exige só
 * {@code orig} e {@code CST}; os valores do diferimento ficam a critério da UF (MOC 7.00, Anexo I,
 * grupo N07 e regra N07-10, facultativa).
 *
 * <p><strong>Fora do padrão, sem CST informado:</strong> nada é escolhido pelo sistema. O CST fica
 * a definir com o contador, e a emissão é barrada (422 {@code ICMS_A_DEFINIR}). O CST informado só
 * pode ser um dos que o leiaute aceita sem o cálculo do ICMS ({@link #CSTS_SEM_CALCULO}): o
 * {@code ICMS40} (40, 41 e 50), o {@code ICMS51} e o {@code ICMS90} exigem só {@code orig} e
 * {@code CST}; o {@code ICMS00}, o {@code ICMS20} e os demais exigem base, alíquota e valor.
 */
public final class TributacaoIcms {

  /** CFOP "venda de produção do estabelecimento", operação interna. */
  public static final String CFOP_VENDA_PRODUCAO_INTERNA = "5101";

  /** CST do diferimento. */
  public static final String CST_DIFERIMENTO = "51";

  /** CST da exportação (MOC 7.00, regra N12-10: CFOP 7xxx exige CST 41). */
  public static final String CST_NAO_TRIBUTADA = "41";

  /** Origem nacional (tabela {@code Torig}), o padrão da origem da mercadoria. */
  public static final String ORIGEM_NACIONAL = "0";

  /** CSTs que o sistema emite: os grupos do leiaute que não exigem o cálculo do ICMS. */
  public static final List<String> CSTS_SEM_CALCULO = List.of("40", "41", "50", "51", "90");

  /** A lista para mensagem: "40, 41, 50, 51 ou 90". */
  public static final String CSTS_SEM_CALCULO_TEXTO =
      String.join(", ", CSTS_SEM_CALCULO.subList(0, CSTS_SEM_CALCULO.size() - 1)) + " ou "
          + CSTS_SEM_CALCULO.get(CSTS_SEM_CALCULO.size() - 1);

  private TributacaoIcms() {}

  /**
   * CST do ICMS que vai ao XML.
   *
   * @param nota nota do item (UFs e IE do destinatário)
   * @param item item
   * @return o CST informado, o 51 do padrão, ou vazio (a definir com o contador)
   */
  public static Optional<String> cst(NotaFiscal nota, NotaFiscalItem item) {
    EnderecoDestinatario endereco = nota == null ? null : nota.getDestinatarioEndereco();
    return cst(item.getCstIcms(), item.getCfop(),
        nota == null || nota.getProdutor() == null ? null : nota.getProdutor().getEstado(),
        endereco == null ? null : endereco.getUf(),
        nota == null ? null : nota.getDestinatarioIe());
  }

  /**
   * CST do ICMS que vai ao XML, pelos dados soltos (a projeção do sinal de nota não carrega a
   * entidade).
   *
   * @param cstInformado   CST informado no item (nulo = não informado)
   * @param cfop           CFOP do item
   * @param ufEmitente     UF do emitente
   * @param ufDestinatario UF do destinatário
   * @param ieDestinatario IE do destinatário (vazia = não contribuinte)
   * @return o CST informado, o 51 do padrão, ou vazio
   */
  public static Optional<String> cst(String cstInformado, String cfop, String ufEmitente,
      String ufDestinatario, String ieDestinatario) {
    if (cstInformado != null && !cstInformado.isBlank()) {
      return Optional.of(cstInformado.strip());
    }
    return diferimentoPadrao(cfop, ufEmitente, ufDestinatario, ieDestinatario)
        ? Optional.of(CST_DIFERIMENTO) : Optional.empty();
  }

  /**
   * Venda interna de produção própria a contribuinte: o único caso com CST padrão.
   *
   * @param cfop           CFOP do item
   * @param ufEmitente     UF do emitente
   * @param ufDestinatario UF do destinatário
   * @param ieDestinatario IE do destinatário
   * @return {@code true} se o CST 51 vale sem ser informado
   */
  public static boolean diferimentoPadrao(String cfop, String ufEmitente, String ufDestinatario,
      String ieDestinatario) {
    if (cfop == null || !CFOP_VENDA_PRODUCAO_INTERNA.equals(cfop.strip())
        || ufEmitente == null || ufDestinatario == null
        || ieDestinatario == null || ieDestinatario.isBlank()) {
      return false;
    }
    String destino = ufDestinatario.strip().toUpperCase();
    return !UfIbge.isExterior(destino) && ufEmitente.strip().toUpperCase().equals(destino);
  }

  /**
   * Origem da mercadoria que vai ao XML: a informada ou a nacional.
   *
   * @param item item
   * @return a origem, nunca nula
   */
  public static String origem(NotaFiscalItem item) {
    return item.getOrigemIcms() == null || item.getOrigemIcms().isBlank() ? ORIGEM_NACIONAL
        : item.getOrigemIcms().strip();
  }

  /**
   * Origem + CST, como a DANFE imprime o CST do ICMS (MOC 7.00, Anexo II: campo CST = N11 + N12).
   *
   * @param origem origem da mercadoria
   * @param cst    CST (ou CSOSN, de nota importada do Simples)
   * @return a concatenação, ou nulo sem CST
   */
  public static String impresso(String origem, String cst) {
    if (cst == null) {
      return null;
    }
    return (origem == null ? "" : origem) + cst;
  }
}
