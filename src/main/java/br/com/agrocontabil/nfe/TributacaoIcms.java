package br.com.agrocontabil.nfe;

import br.com.agrocontabil.common.UfIbge;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoProdutor;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
 * (com IE), por emitente de regime normal. É o caso que o escritório confirmou: a soja em grão
 * vendida pelo produtor de MT dentro do estado sai com o ICMS diferido, CST 51, ICMS próprio
 * zero. O grupo {@code ICMS51} exige só {@code orig} e {@code CST}; os valores do diferimento
 * ficam a critério da UF (MOC 7.00, Anexo I, grupo N07 e regra N07-10, facultativa). O padrão vale
 * para toda UF e todo produto (decisão de 2026-11-06), mas só está confirmado nos pares (UF; NCM)
 * de {@value #RECURSO_CONFIRMADOS} (hoje MT + soja em grão). Fora deles, o item emite e o sinal
 * não bloqueante {@code ICMS_PADRAO_A_CONFIRMAR} pede a conferência com o contador.
 *
 * <p><strong>Fora do padrão, sem CST informado:</strong> nada é escolhido pelo sistema. O CST fica
 * a definir com o contador, e a emissão é barrada (422 {@code ICMS_A_DEFINIR}). O CST informado só
 * pode ser um dos que o leiaute aceita sem o cálculo do ICMS ({@link #CSTS_SEM_CALCULO}): o
 * {@code ICMS40} (40, 41 e 50), o {@code ICMS51} e o {@code ICMS90} exigem só {@code orig} e
 * {@code CST}; o {@code ICMS00}, o {@code ICMS20} e os demais exigem base, alíquota e valor.
 *
 * <p><strong>Emitente do Simples Nacional ou MEI (CRT 1 ou 4):</strong> informa CSOSN, não CST
 * (MOC 7.00, regra N12-20, rejeição 590), nos grupos {@code ICMSSN}, que o sistema não monta. Não
 * há padrão nem CST que sirva: a emissão é barrada até esse tratamento existir.
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

  /**
   * Os CSTs que o sistema emite, como expressão regular (fonte do {@code @Pattern} do DTO, que
   * exige uma constante; a lista abaixo é conferida contra ela por teste). O vazio vale como não
   * informado.
   */
  public static final String CSTS_SEM_CALCULO_REGEX = "|40|41|50|51|90";

  /** CSTs que o sistema emite: os grupos do leiaute que não exigem o cálculo do ICMS. */
  public static final List<String> CSTS_SEM_CALCULO = List.of("40", "41", "50", "51", "90");

  /** A lista para mensagem: "40, 41, 50, 51 ou 90". */
  public static final String CSTS_SEM_CALCULO_TEXTO =
      String.join(", ", CSTS_SEM_CALCULO.subList(0, CSTS_SEM_CALCULO.size() - 1)) + " ou "
          + CSTS_SEM_CALCULO.get(CSTS_SEM_CALCULO.size() - 1);

  /** Por que o item não pode ser emitido com o grupo ICMS. */
  public enum Problema {
    /** Sem CST informado e fora do padrão: o contador define. */
    A_DEFINIR,
    /** Sem CST informado numa exportação: o único aceito é o 41. */
    EXPORTACAO_A_DEFINIR,
    /** CST informado (nota antiga) que exige o cálculo do ICMS. */
    FORA_DA_LISTA,
    /** Exportação com CST diferente de 41 (N12-10). */
    EXPORTACAO_SEM_41,
    /** Emitente do Simples Nacional ou MEI: CSOSN, fora do escopo. */
    EMITENTE_SIMPLES
  }

  /** Pares (UF; NCM) com o diferimento confirmado pelo contador, versionados no classpath. */
  static final String RECURSO_CONFIRMADOS = "icms/diferimento-confirmado.csv";

  private TributacaoIcms() {}

  /**
   * O CST 51 sai pelo padrão (sem CST informado) num par (UF; NCM) que o contador ainda não
   * confirmou: o item emite, mas o sinal {@code ICMS_PADRAO_A_CONFIRMAR} avisa antes do XML.
   *
   * @param cstInformado CST informado no item
   * @param cfop         CFOP do item
   * @param ncm          NCM do item
   * @param dados        emitente e destinatário
   * @return {@code true} se o 51 vem do padrão fora da lista confirmada
   */
  public static boolean padraoAConfirmar(String cstInformado, String cfop, String ncm,
      DadosOperacao dados) {
    boolean informado = cstInformado != null && !cstInformado.isBlank();
    return !informado && !dados.emitenteSimples() && diferimentoPadrao(cfop, dados)
        && !padraoConfirmado(dados.ufEmitente(), ncm);
  }

  /**
   * O par (UF do emitente; NCM) está na lista do contador ({@value #RECURSO_CONFIRMADOS}).
   *
   * @param uf  UF do emitente
   * @param ncm NCM do item
   * @return {@code true} se confirmado
   */
  public static boolean padraoConfirmado(String uf, String ncm) {
    return uf != null && ncm != null
        && Confirmados.PARES.contains(uf.strip().toUpperCase() + ";" + ncm.strip());
  }

  /** Lista carregada uma vez, na primeira consulta. */
  private static final class Confirmados {
    static final Set<String> PARES = carregar();

    private static Set<String> carregar() {
      try (InputStream in = TributacaoIcms.class.getClassLoader()
          .getResourceAsStream(RECURSO_CONFIRMADOS)) {
        if (in == null) {
          throw new IllegalStateException("Lista de diferimento confirmado ausente: "
              + RECURSO_CONFIRMADOS);
        }
        return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
            .map(String::strip)
            .filter(l -> !l.isEmpty() && !l.startsWith("#"))
            .map(l -> l.split(";", 3))
            .map(c -> c[0].strip().toUpperCase() + ";" + c[1].strip())
            .collect(Collectors.toUnmodifiableSet());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  /**
   * CST do ICMS que vai ao XML.
   *
   * @param nota nota do item (emitente, UFs e IE do destinatário)
   * @param item item
   * @return o CST informado, o 51 do padrão, ou vazio (a definir, ou emitente do Simples)
   */
  public static Optional<String> cst(NotaFiscal nota, NotaFiscalItem item) {
    return cst(item.getCstIcms(), item.getCfop(), dados(nota));
  }

  /**
   * O que impede a emissão do grupo ICMS do item; vazio se o item pode sair. Regra única do
   * {@code FiscalRulesValidator} e do sinal {@code ICMS_A_DEFINIR}.
   *
   * @param nota nota do item
   * @param item item
   * @return o problema, ou vazio
   */
  public static Optional<Problema> problema(NotaFiscal nota, NotaFiscalItem item) {
    return problema(item.getCstIcms(), item.getCfop(), dados(nota));
  }

  /**
   * O que impede a emissão, pelos dados soltos (a projeção do sinal de nota não carrega a
   * entidade).
   *
   * @param cstInformado CST informado no item (nulo ou vazio = não informado)
   * @param cfop         CFOP do item
   * @param dados        emitente e destinatário
   * @return o problema, ou vazio
   */
  public static Optional<Problema> problema(String cstInformado, String cfop, DadosOperacao dados) {
    if (dados.emitenteSimples()) {
      return Optional.of(Problema.EMITENTE_SIMPLES);
    }
    boolean exportacao = cfop != null && cfop.strip().startsWith("7");
    Optional<String> cst = cst(cstInformado, cfop, dados);
    if (cst.isEmpty()) {
      return Optional.of(exportacao ? Problema.EXPORTACAO_A_DEFINIR : Problema.A_DEFINIR);
    }
    if (!CSTS_SEM_CALCULO.contains(cst.get())) {
      return Optional.of(Problema.FORA_DA_LISTA);
    }
    if (exportacao && !CST_NAO_TRIBUTADA.equals(cst.get())) {
      return Optional.of(Problema.EXPORTACAO_SEM_41);
    }
    return Optional.empty();
  }

  /**
   * CST do ICMS que vai ao XML, pelos dados soltos.
   *
   * @param cstInformado CST informado no item (nulo ou vazio = não informado)
   * @param cfop         CFOP do item
   * @param dados        emitente e destinatário
   * @return o CST informado, o 51 do padrão, ou vazio
   */
  public static Optional<String> cst(String cstInformado, String cfop, DadosOperacao dados) {
    if (dados.emitenteSimples()) {
      return Optional.empty();
    }
    if (cstInformado != null && !cstInformado.isBlank()) {
      return Optional.of(cstInformado.strip());
    }
    return diferimentoPadrao(cfop, dados) ? Optional.of(CST_DIFERIMENTO) : Optional.empty();
  }

  /**
   * Venda interna de produção própria a contribuinte: o único caso com CST padrão.
   *
   * @param cfop  CFOP do item
   * @param dados emitente e destinatário
   * @return {@code true} se o CST 51 vale sem ser informado
   */
  static boolean diferimentoPadrao(String cfop, DadosOperacao dados) {
    if (cfop == null || !CFOP_VENDA_PRODUCAO_INTERNA.equals(cfop.strip())
        || dados.ieDestinatario() == null || dados.ieDestinatario().isBlank()) {
      return false;
    }
    return UfIbge.operacaoInterna(dados.ufEmitente(), dados.ufDestinatario());
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

  private static DadosOperacao dados(NotaFiscal nota) {
    if (nota == null) {
      return new DadosOperacao(null, null, null, null, null);
    }
    EnderecoDestinatario endereco = nota.getDestinatarioEndereco();
    return new DadosOperacao(
        nota.getProdutor() == null ? null : nota.getProdutor().getTipoProdutor(),
        nota.getProdutor() == null ? null : nota.getProdutor().getRegimeTributario(),
        nota.getProdutor() == null ? null : nota.getProdutor().getEstado(),
        endereco == null ? null : endereco.getUf(),
        nota.getDestinatarioIe());
  }

  /**
   * Emitente e destinatário, o que a regra precisa da nota.
   *
   * @param tipoEmitente   pessoa física ou jurídica
   * @param regimeEmitente regime gravado do emitente
   * @param ufEmitente     UF do emitente
   * @param ufDestinatario UF do destinatário
   * @param ieDestinatario IE do destinatário (vazia = não contribuinte)
   */
  public record DadosOperacao(TipoProdutor tipoEmitente, RegimeTributario regimeEmitente,
      String ufEmitente, String ufDestinatario, String ieDestinatario) {

    /** CRT 1 (Simples) ou 4 (MEI), pela mesma regra do XML ({@code NotaFiscalNfeMapper.crt}). */
    boolean emitenteSimples() {
      String crt = NotaFiscalNfeMapper.crt(tipoEmitente, regimeEmitente);
      return "1".equals(crt) || "4".equals(crt);
    }
  }
}
