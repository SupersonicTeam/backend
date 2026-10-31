package br.com.agrocontabil.nfe;

import br.com.agrocontabil.common.UfIbge;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.EnderecoProdutor;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.nfe.model.Dest;
import br.com.agrocontabil.nfe.model.Det;
import br.com.agrocontabil.nfe.model.Emit;
import br.com.agrocontabil.nfe.model.EnderDest;
import br.com.agrocontabil.nfe.model.EnderEmit;
import br.com.agrocontabil.nfe.model.IbsCbs;
import br.com.agrocontabil.nfe.model.IbsCbsTot;
import br.com.agrocontabil.nfe.model.IcmsTot;
import br.com.agrocontabil.nfe.model.Ide;
import br.com.agrocontabil.nfe.model.Imposto;
import br.com.agrocontabil.nfe.model.InfAdic;
import br.com.agrocontabil.nfe.model.InfNFe;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.nfe.model.Pag;
import br.com.agrocontabil.nfe.model.Prod;
import br.com.agrocontabil.nfe.model.Total;
import br.com.agrocontabil.nfe.model.Transp;
import br.com.agrocontabil.service.AliquotaIbsCbsResolver;
import br.com.agrocontabil.service.ChaveAcessoGenerator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Mapeia uma {@link NotaFiscal} (entidade JPA) para o grafo de objetos JAXB
 * ({@link NFe} e sub-tipos), pronto para marshalling.
 *
 * <p>Esta camada concentra <strong>toda</strong> a lógica de formatação:
 * <ul>
 *   <li>Formatação de {@link BigDecimal} com escala exata por tipo de campo do XSD</li>
 *   <li>Formatação de datas em {@code AAAA-MM-DDThh:mm:ssTZD}</li>
 *   <li>Zero-padding de série (3 dígitos) e número (9 dígitos)</li>
 *   <li>Derivação do código IBGE da UF ({@code cUF}) a partir da sigla</li>
 *   <li>Geração da chave de acesso via {@link ChaveAcessoGenerator}</li>
 *   <li>Mapeamento de {@link RegimeTributario} → CRT</li>
 * </ul>
 *
 * <p>As classes JAXB ({@code nfe/model/}) são <em>puras</em>: sem lógica, sem cálculo.
 *
 * <h3>Emitente (issue #88):</h3>
 * <p>IE e endereço vêm do {@code Produtor} — o {@code FiscalRulesValidator} garante a presença
 * antes da emissão. A IE só assume {@code ISENTO} quando ausente, caso legítimo da NFA de
 * produtor rural pessoa física. O {@code cMunFG} é o município do <strong>emitente</strong>
 * (local do fato gerador), não o do destinatário.
 *
 * <h3>Débito técnico — bairro do destinatário:</h3>
 * <p>{@code EnderecoDestinatario} não modela o bairro e o XSD exige {@code xBairro} no
 * {@code enderDest}: por isso o literal {@code "CENTRO"} permanece nesse grupo. Resolver
 * modelando o campo no domínio e no DTO. O {@code "S/N"} de {@code nro} não é placeholder — é a
 * convenção do layout para imóvel sem número.
 */
@Component
public class NotaFiscalNfeMapper {

  /**
   * Bairro do destinatário no XML (débito técnico acima). Público para a DANFE imprimir o mesmo
   * valor do XML.
   */
  public static final String BAIRRO_DESTINATARIO = "CENTRO";

  private static final String NFE_VERSAO = "4.00";
  private static final String TPAMB_HOMOLOGACAO = "2";
  private static final String TPEMIS_NORMAL = "1";
  private static final String MOD_NF_E = "55";
  private static final String PROC_EMI = "0";
  private static final String VER_PROC = "1.0";
  // "xxx" (minusculo) em vez de "XXX": XXX imprime "Z" para offset zero (UTC), mas o XSD
  // da NF-e (TDateTimeUTC) exige offset numerico explicito e rejeita "Z" como literal.
  // Isso importa porque timestamptz do Postgres normaliza qualquer OffsetDateTime para UTC
  // no round-trip, entao uma nota emitida as 10h em -03:00 volta do banco como 13h em Z.
  private static final DateTimeFormatter DFE_DT_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

  private final ChaveAcessoGenerator chaveAcessoGenerator;

  /** Constrói o mapper injetando o gerador de chave de acesso. */
  public NotaFiscalNfeMapper(ChaveAcessoGenerator chaveAcessoGenerator) {
    this.chaveAcessoGenerator = chaveAcessoGenerator;
  }

  /**
   * Converte uma {@link NotaFiscal} validada para o grafo JAXB {@link NFe}.
   *
   * @param nota nota fiscal com todos os campos obrigatórios preenchidos
   * @return grafo JAXB pronto para marshalling
   */
  public NFe toNfe(NotaFiscal nota) {
    Produtor produtor = nota.getProdutor();
    // UF do emitente e obrigatoria na emissao (FiscalRulesValidator); o orElseThrow protege
    // contra chamada fora do pipeline, em vez de gerar chave com cUF de outro estado.
    String cUF = UfIbge.codigo(produtor.getEstado())
        .orElseThrow(() -> new IllegalStateException(
            "UF do emitente ausente ou desconhecida; nao e possivel determinar o cUF"));

    String aamm = nota.getDataEmissao().format(DateTimeFormatter.ofPattern("yyMM"));

    String cpfCnpj = produtor.getCpfCnpj().replaceAll("\\D", "");
    int serie = nota.getSerie() != null ? nota.getSerie() : 1;
    long numero = nota.getNumero();

    ChaveAcessoGenerator.ChaveResult chaveResult =
        chaveAcessoGenerator.gerar(cUF, aamm, cpfCnpj, serie, numero);

    NFe nfe = new NFe();
    InfNFe infNFe = new InfNFe();
    infNFe.setId("NFe" + chaveResult.chave());
    infNFe.setVersao(NFE_VERSAO);
    infNFe.setIde(buildIde(nota, cUF, chaveResult));
    infNFe.setEmit(buildEmit(produtor, cUF));
    infNFe.setDest(buildDest(nota));
    infNFe.setDet(buildDet(nota));
    infNFe.setTotal(buildTotal(nota));
    infNFe.setTransp(buildTransp());
    infNFe.setPag(buildPag(nota));
    infNFe.setInfAdic(buildInfAdic());
    nfe.setInfNFe(infNFe);
    return nfe;
  }

  // =========================================================================
  // Builders de seções
  // =========================================================================

  private Ide buildIde(
      NotaFiscal nota, String cUF, ChaveAcessoGenerator.ChaveResult chaveResult) {

    Ide ide = new Ide();
    ide.setCUF(cUF);
    ide.setCNF(chaveResult.cNF());
    ide.setNatOp(truncate(nota.getNaturezaOperacao(), 60));
    ide.setMod(MOD_NF_E);
    // TSerie pattern: 0|[1-9]{1}[0-9]{0,2} — sem zero-padding
    ide.setSerie(String.valueOf(nota.getSerie() != null ? nota.getSerie() : 1));
    // TNF pattern: [1-9]{1}[0-9]{0,8} — sem zero-padding
    ide.setNNF(String.valueOf(nota.getNumero()));
    ide.setDhEmi(nota.getDataEmissao().format(DFE_DT_FMT));
    if (nota.getDataSaida() != null) {
      ide.setDhSaiEnt(nota.getDataSaida().format(DFE_DT_FMT));
    }
    ide.setTpNF("1");   // saída
    ide.setIdDest(resolveIdDest(nota));
    ide.setCMunFG(resolveCMunFG(nota));
    ide.setTpImp("1");  // DANFE retrato
    ide.setTpEmis(TPEMIS_NORMAL);
    ide.setCDV(String.valueOf(chaveResult.cDV()));
    ide.setTpAmb(TPAMB_HOMOLOGACAO);
    ide.setFinNFe("1"); // NF-e normal
    ide.setIndFinal("0");
    ide.setIndPres("9"); // não presencial
    ide.setProcEmi(PROC_EMI);
    ide.setVerProc(VER_PROC);
    return ide;
  }

  private Emit buildEmit(Produtor produtor, String cUF) {
    Emit emit = new Emit();

    String cpfCnpj = produtor.getCpfCnpj().replaceAll("\\D", "");
    if (cpfCnpj.length() == 14) {
      emit.setCnpj(cpfCnpj);
    } else {
      // CPF: zero-pad para 11 dígitos
      emit.setCpf(chaveAcessoGenerator.zeroPad(cpfCnpj, 11));
    }

    String nome = produtor.getNomeCompleto();
    emit.setXNome(truncate(nome != null ? nome : "EMITENTE", 60));
    emit.setEnderEmit(buildEnderEmit(produtor));
    // IE real do emitente. "ISENTO" so e aceitavel na NFA (produtor rural PF sem IE); para a
    // NF-e modelo 55 o FiscalRulesValidator ja exigiu a IE antes de chegar aqui (issue #88).
    String ie = produtor.getInscricaoEstadual();
    emit.setIe(ie != null && !ie.isBlank() ? ie.trim() : "ISENTO");
    emit.setCrt(crt(produtor.getRegimeTributario()));
    return emit;
  }

  private EnderEmit buildEnderEmit(Produtor produtor) {
    // Endereco real do emitente (issue #88). A obrigatoriedade dos campos e garantida pelo
    // FiscalRulesValidator antes da emissao; aqui apenas mapeamos, truncando ao limite do layout.
    EnderecoProdutor endereco = produtor.getEndereco();
    EnderEmit end = new EnderEmit();
    if (endereco == null) {
      throw new IllegalStateException(
          "Endereco do emitente ausente; a emissao deveria ter sido barrada pela validacao fiscal");
    }
    end.setXLgr(truncate(endereco.getLogradouro(), 60));
    end.setNro(truncate(endereco.getNumero(), 60));
    if (endereco.getComplemento() != null && !endereco.getComplemento().isBlank()) {
      end.setXCpl(truncate(endereco.getComplemento(), 60));
    }
    end.setXBairro(truncate(endereco.getBairro(), 60));
    end.setCMun(endereco.getCodigoMunicipioIbge());
    end.setXMun(truncate(endereco.getMunicipio(), 60));
    end.setUf(produtor.getEstado().toUpperCase());
    end.setCep(endereco.getCep() != null ? endereco.getCep().replaceAll("\\D", "") : null);
    return end;
  }

  private Dest buildDest(NotaFiscal nota) {
    Dest dest = new Dest();

    String cpfCnpj = nota.getDestinatarioCpfCnpj().replaceAll("\\D", "");
    if (cpfCnpj.length() == 14) {
      dest.setCnpj(cpfCnpj);
    } else {
      dest.setCpf(chaveAcessoGenerator.zeroPad(cpfCnpj, 11));
    }

    dest.setXNome(truncate(nota.getDestinatarioNome(), 60));

    EnderecoDestinatario end = nota.getDestinatarioEndereco();
    if (end != null) {
      dest.setEnderDest(buildEnderDest(end));
    }

    if (nota.getDestinatarioIe() != null && !nota.getDestinatarioIe().isBlank()) {
      dest.setIndIEDest("1");
      dest.setIe(nota.getDestinatarioIe());
    } else {
      dest.setIndIEDest("9"); // não contribuinte
    }
    return dest;
  }

  private EnderDest buildEnderDest(EnderecoDestinatario end) {
    // Sem placeholders (issue #88): o FiscalRulesValidator exige logradouro, municipio, codigo
    // IBGE e UF do destinatario na emissao, alem da coerencia IBGE x UF. O numero segue opcional
    // no dominio e o layout exige o elemento, entao um imovel sem numero vira "S/N" (convencao do
    // layout, nao placeholder). O bairro nao e modelado — ver o debito no Javadoc da classe.
    EnderDest enderDest = new EnderDest();
    enderDest.setXLgr(truncate(end.getLogradouro(), 60));
    enderDest.setNro(end.getNumero() != null ? truncate(end.getNumero(), 60) : "S/N");
    enderDest.setXBairro(BAIRRO_DESTINATARIO);
    enderDest.setCMun(end.getCodigoMunicipioIbge());
    enderDest.setXMun(truncate(end.getMunicipio(), 60));
    enderDest.setUf(end.getUf().toUpperCase());
    if (end.getCep() != null && !end.getCep().isBlank()) {
      enderDest.setCep(end.getCep().replaceAll("\\D", ""));
    }
    return enderDest;
  }

  private List<Det> buildDet(NotaFiscal nota) {
    List<Det> detList = new ArrayList<>();
    for (NotaFiscalItem item : nota.getItens()) {
      detList.add(buildDetItem(item));
    }
    return detList;
  }

  private Det buildDetItem(NotaFiscalItem item) {
    Det det = new Det();
    det.setNItem(String.valueOf(item.getNumeroItem()));
    det.setProd(buildProd(item));
    det.setImposto(buildImposto(item));
    return det;
  }

  private Prod buildProd(NotaFiscalItem item) {
    Prod prod = new Prod();
    String codProd = item.getCodigoProduto() != null
        ? item.getCodigoProduto() : item.getCfop();
    prod.setCProd(truncate(codProd, 60));
    prod.setCEAN("SEM GTIN");
    prod.setXProd(truncate(item.getDescricao(), 120));
    prod.setNcm(item.getNcm());
    prod.setCfop(item.getCfop());
    prod.setUCom(truncate(item.getUnidade(), 6));
    // qCom: 4 casas decimais (TDec_1104v)
    prod.setQCom(formatDecimal(item.getQuantidade(), 4));
    // vUnCom: 4 casas decimais (simplificado — XSD aceita até 10)
    prod.setVUnCom(formatDecimal(item.getValorUnitario(), 4));
    // vProd: 2 casas decimais (TDec_1302)
    prod.setVProd(formatDecimal2(item.getValorTotal()));
    prod.setCEANTrib("SEM GTIN");
    prod.setUTrib(truncate(item.getUnidade(), 6));
    prod.setQTrib(formatDecimal(item.getQuantidade(), 4));
    prod.setVUnTrib(formatDecimal(item.getValorUnitario(), 4));
    prod.setIndTot("1");
    return prod;
  }

  private Imposto buildImposto(NotaFiscalItem item) {
    Imposto imposto = new Imposto();
    if (item.getValorIbs() != null || item.getValorCbs() != null) {
      imposto.setIbsCbs(buildIbsCbs(item));
    }
    return imposto;
  }

  /**
   * CST e cClassTrib do tratamento aplicado no cálculo (tabela oficial, IT 2025.002 — issues
   * #272 e #278): o cClassTrib aplicado, ou o informado; o CST informado, ou os três primeiros
   * dígitos do cClassTrib. Sem padrão fixo: o {@code FiscalRulesValidator} barra a emissão sem
   * cClassTrib válido antes de chegar aqui.
   */
  private IbsCbs buildIbsCbs(NotaFiscalItem item) {
    String cClassTrib = cClassTrib(item);
    if (cClassTrib == null) {
      throw new IllegalStateException("Item " + item.getNumeroItem() + " sem cClassTrib aplicado");
    }
    IbsCbs ibsCbs = new IbsCbs();
    ibsCbs.setCst(cst(item));
    ibsCbs.setCClassTrib(cClassTrib);

    if (item.getValorTotal() != null
        && item.getAliquotaIbs() != null
        && item.getAliquotaCbs() != null) {
      ibsCbs.setGIBSCBS(buildGIbsCbs(item));
    }
    return ibsCbs;
  }

  /** cClassTrib que vai ao XML: o aplicado no cálculo ou o informado no item (#278). */
  public static String cClassTrib(NotaFiscalItem item) {
    return item.getCClassTribAplicado() != null ? item.getCClassTribAplicado()
        : item.getCClassTrib();
  }

  /**
   * CST que vai ao XML (#278): o informado, só quando o cliente também informou o cClassTrib (CST
   * sem classificação é resto de rascunho antigo, como o exemplo {@code 000} do OpenAPI); senão o
   * prefixo do cClassTrib.
   */
  public static String cst(NotaFiscalItem item) {
    if (item.getCstIbsCbs() != null && item.getCClassTrib() != null) {
      return item.getCstIbsCbs();
    }
    String cClassTrib = cClassTrib(item);
    return cClassTrib == null ? null : cClassTrib.substring(0, 3);
  }

  /** Redução de alíquota aplicada ao item (0 a 100), nunca nula (#278). */
  public static BigDecimal reducao(NotaFiscalItem item) {
    if (item.getPercentualReducaoAplicado() != null) {
      return item.getPercentualReducaoAplicado();
    }
    return item.getPercentualReducao() != null ? item.getPercentualReducao() : BigDecimal.ZERO;
  }

  /** CSTs com redução de alíquota, em que o grupo gRed é obrigatório (ind_gRed = 1). */
  static final java.util.Set<String> CST_COM_GRED = java.util.Set.of("011", "200", "515");

  private IbsCbs.GIbsCbs buildGIbsCbs(NotaFiscalItem item) {
    IbsCbs.GIbsCbs g = new IbsCbs.GIbsCbs();
    // vBC e o valor cheio: a LC 214/2025 reduz a aliquota (gRed), nao a base (#278). Nota
    // calculada antes da #278 tem a base reduzida gravada, mas o mesmo valor do tributo.
    g.setVBC(formatDecimal2(item.getValorTotal()));
    String cst = cst(item);
    boolean comGRed = cst != null && CST_COM_GRED.contains(cst);

    ParcelasIbs parcelas = parcelasIbs(item);
    BigDecimal alqIbsUf = parcelas.aliquotaUf();
    BigDecimal alqIbsMun = parcelas.aliquotaMun();
    BigDecimal valorIbsUf = parcelas.valorUf();
    BigDecimal valorIbsMun = parcelas.valorMun();
    BigDecimal valorIbs = item.getValorIbs() != null ? item.getValorIbs() : BigDecimal.ZERO;

    IbsCbs.GIbsUf ibsUf = new IbsCbs.GIbsUf();
    ibsUf.setPIBSUF(formatAliquotaPercent(alqIbsUf));
    if (comGRed) {
      ibsUf.setGRed(buildGRed(alqIbsUf, item));
    }
    ibsUf.setVIBSUF(formatDecimal2(valorIbsUf));

    IbsCbs.GIbsMun ibsMun = new IbsCbs.GIbsMun();
    ibsMun.setPIBSMun(formatAliquotaPercent(alqIbsMun));
    if (comGRed) {
      ibsMun.setGRed(buildGRed(alqIbsMun, item));
    }
    ibsMun.setVIBSMun(formatDecimal2(valorIbsMun));

    g.setGIBSUF(ibsUf);
    g.setGIBSMun(ibsMun);
    g.setVIBS(formatDecimal2(valorIbs));

    BigDecimal alqCbs = item.getAliquotaCbs();
    BigDecimal valorCbs = item.getValorCbs() != null ? item.getValorCbs() : BigDecimal.ZERO;

    IbsCbs.GCbs gCbs = new IbsCbs.GCbs();
    gCbs.setPCBS(formatAliquotaPercent(alqCbs));
    if (comGRed) {
      gCbs.setGRed(buildGRed(alqCbs, item));
    }
    gCbs.setVCBS(formatDecimal2(valorCbs));

    g.setGCBS(gCbs);
    return g;
  }

  /** Parcelas estadual e municipal do IBS de um item: alíquotas e valores. */
  public record ParcelasIbs(BigDecimal aliquotaUf, BigDecimal aliquotaMun, BigDecimal valorUf,
      BigDecimal valorMun) {
  }

  /**
   * Parcelas do IBS que vão ao XML (issues #31 e #277): as gravadas no item; item legado gravado
   * antes da #31 (sem parcelas) usa a regra do fallback do resolvedor — todo estadual antes de 2027,
   * 50/50 depois. Fonte única do XML e da DANFE (#279).
   *
   * @param item item com IBS calculado
   * @return parcelas, nunca nulas (zero sem IBS)
   */
  public static ParcelasIbs parcelasIbs(NotaFiscalItem item) {
    BigDecimal alqIbs = item.getAliquotaIbs() != null ? item.getAliquotaIbs() : BigDecimal.ZERO;
    BigDecimal valorIbs = item.getValorIbs() != null ? item.getValorIbs() : BigDecimal.ZERO;
    boolean dividido = AliquotaIbsCbsResolver.temParcelaMunicipal(
        item.getNotaFiscal() != null ? item.getNotaFiscal().getDataEmissao() : null);

    BigDecimal alqIbsUf = item.getAliquotaIbsUf();
    BigDecimal alqIbsMun = item.getAliquotaIbsMun();
    if (alqIbsUf == null || alqIbsMun == null) {
      alqIbsUf = dividido ? alqIbs.divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP) : alqIbs;
      alqIbsMun = alqIbs.subtract(alqIbsUf);
    }
    BigDecimal valorIbsUf = item.getValorIbsUf();
    BigDecimal valorIbsMun = item.getValorIbsMun();
    if (valorIbsUf == null || valorIbsMun == null) {
      valorIbsUf = dividido ? valorIbs.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)
          : valorIbs;
      valorIbsMun = valorIbs.subtract(valorIbsUf);
    }
    return new ParcelasIbs(alqIbsUf, alqIbsMun, valorIbsUf, valorIbsMun);
  }

  /**
   * Alíquota impressa (NT 2026.010, item 4.3): com {@code gRed} (CST 011, 200 ou 515), a efetiva,
   * {@code aliquota × (1 − redução / 100)}; sem, a vigente. Fonte única do {@code pAliqEfet} do XML
   * e da DANFE (#279).
   *
   * @param aliquota alíquota vigente (proporção decimal, ex.: {@code 0.0010})
   * @param item item da nota
   * @return a alíquota a mostrar; nula se {@code aliquota} for nula
   */
  public static BigDecimal aliquotaEfetiva(BigDecimal aliquota, NotaFiscalItem item) {
    String cst = cst(item);
    if (aliquota == null || cst == null || !CST_COM_GRED.contains(cst)) {
      return aliquota;
    }
    BigDecimal fator = BigDecimal.ONE.subtract(
        reducao(item).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
    return aliquota.multiply(fator);
  }

  /**
   * Grupo {@code gRed}: {@code pRedAliq} = redução e {@code pAliqEfet} = alíquota × (1 − redução
   * / 100), os dois em percentual com 4 casas (#278).
   */
  private IbsCbs.GRed buildGRed(BigDecimal aliquota, NotaFiscalItem item) {
    IbsCbs.GRed gRed = new IbsCbs.GRed();
    gRed.setPRedAliq(reducao(item).setScale(4, RoundingMode.HALF_UP).toPlainString());
    gRed.setPAliqEfet(formatAliquotaPercent(aliquotaEfetiva(aliquota, item)));
    return gRed;
  }

  private Total buildTotal(NotaFiscal nota) {
    Total total = new Total();
    total.setIcmsTot(buildIcmsTot(nota));
    if (nota.getValorTotalIbs() != null || nota.getValorTotalCbs() != null) {
      total.setIbsCbsTot(buildIbsCbsTot(nota));
    }
    return total;
  }

  private IcmsTot buildIcmsTot(NotaFiscal nota) {
    IcmsTot t = new IcmsTot();
    String zero = "0.00";
    String vProd = formatDecimal2(nota.getValorTotalProdutos());
    String vNF = formatDecimal2(nota.getValorTotalNota());
    t.setVBC(zero);
    t.setVICMS(zero);
    t.setVICMSDeson(zero);
    t.setVFCP(zero);
    t.setVBCST(zero);
    t.setVST(zero);
    t.setVFCPST(zero);
    t.setVFCPSTRet(zero);
    t.setVProd(vProd);
    t.setVFrete(zero);
    t.setVSeg(zero);
    t.setVDesc(zero);
    t.setVII(zero);
    t.setVIPI(zero);
    t.setVIPIDevol(zero);
    t.setVPIS(zero);
    t.setVCOFINS(zero);
    t.setVOutro(zero);
    t.setVNF(vNF);
    return t;
  }

  /** Totais das parcelas estadual e municipal do IBS da nota. */
  public record TotaisIbs(BigDecimal valorUf, BigDecimal valorMun) {
  }

  /**
   * Totais do IBS por esfera que vão ao {@code IBSCBSTot} (issue #31): a soma das parcelas reais
   * dos itens; com algum item legado sem as parcelas, o total da nota dividido pela regra do
   * fallback do resolvedor (todo estadual antes de 2027). Fonte única do XML e do preview da
   * DANFE (#279).
   *
   * @param nota nota com itens
   * @return os totais, nunca nulos
   */
  public static TotaisIbs totaisIbs(NotaFiscal nota) {
    boolean parcelasReais = nota.getItens().stream()
        .allMatch(i -> i.getValorIbsUf() != null && i.getValorIbsMun() != null);
    if (parcelasReais) {
      return new TotaisIbs(
          nota.getItens().stream().map(NotaFiscalItem::getValorIbsUf)
              .reduce(BigDecimal.ZERO, BigDecimal::add),
          nota.getItens().stream().map(NotaFiscalItem::getValorIbsMun)
              .reduce(BigDecimal.ZERO, BigDecimal::add));
    }
    BigDecimal totalIbs = nota.getValorTotalIbs() != null
        ? nota.getValorTotalIbs() : BigDecimal.ZERO;
    BigDecimal ibsUf = AliquotaIbsCbsResolver.temParcelaMunicipal(nota.getDataEmissao())
        ? totalIbs.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP) : totalIbs;
    return new TotaisIbs(ibsUf, totalIbs.subtract(ibsUf));
  }

  private IbsCbsTot buildIbsCbsTot(NotaFiscal nota) {
    BigDecimal totalIbs = nota.getValorTotalIbs() != null
        ? nota.getValorTotalIbs() : BigDecimal.ZERO;
    BigDecimal totalCbs = nota.getValorTotalCbs() != null
        ? nota.getValorTotalCbs() : BigDecimal.ZERO;

    // Base total = soma dos vBC dos itens com IBS/CBS (valor cheio, #278).
    BigDecimal baseTotal = nota.getItens().stream()
        .filter(i -> i.getValorTotal() != null && i.getAliquotaIbs() != null
            && i.getAliquotaCbs() != null)
        .map(i -> i.getValorTotal().setScale(2, RoundingMode.HALF_UP))
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    IbsCbsTot tot = new IbsCbsTot();
    tot.setVBCIBSCBS(formatDecimal2(baseTotal));

    TotaisIbs totaisIbs = totaisIbs(nota);
    BigDecimal ibsUf = totaisIbs.valorUf();
    BigDecimal ibsMun = totaisIbs.valorMun();

    IbsCbsTot.GIbsUfTot ufTot = new IbsCbsTot.GIbsUfTot();
    ufTot.setVDif("0.00");
    ufTot.setVDevTrib("0.00");
    ufTot.setVIBSUF(formatDecimal2(ibsUf));

    IbsCbsTot.GIbsMunTot munTot = new IbsCbsTot.GIbsMunTot();
    munTot.setVDif("0.00");
    munTot.setVDevTrib("0.00");
    munTot.setVIBSMun(formatDecimal2(ibsMun));

    IbsCbsTot.GIbsTot ibsTot = new IbsCbsTot.GIbsTot();
    ibsTot.setGIBSUF(ufTot);
    ibsTot.setGIBSMun(munTot);
    ibsTot.setVIBS(formatDecimal2(totalIbs));
    // Crédito presumido: zero neste escopo (produtor rural sem crédito presumido)
    ibsTot.setVCredPres("0.00");
    ibsTot.setVCredPresCondSus("0.00");
    tot.setGIBS(ibsTot);

    // gCBS
    IbsCbsTot.GCbsTot cbsTot = new IbsCbsTot.GCbsTot();
    cbsTot.setVDif("0.00");
    cbsTot.setVDevTrib("0.00");
    cbsTot.setVCBS(formatDecimal2(totalCbs));
    // Crédito presumido: zero neste escopo
    cbsTot.setVCredPres("0.00");
    cbsTot.setVCredPresCondSus("0.00");
    tot.setGCBS(cbsTot);

    return tot;
  }

  private Transp buildTransp() {
    Transp transp = new Transp();
    transp.setModFrete("9"); // sem transporte
    return transp;
  }

  /**
   * Constrói o grupo de pagamento ({@code pag}), obrigatório no layout 4.00.
   *
   * <p>Para NF-e de produtor rural sem transação financeira explícita, usa-se
   * {@code tPag="90"} (Sem Pagamento) com o valor total da nota em {@code vPag}.
   */
  private Pag buildPag(NotaFiscal nota) {
    Pag.DetPag detPag = new Pag.DetPag();
    detPag.setTPag("90"); // Sem Pagamento
    detPag.setVPag(formatDecimal2(nota.getValorTotalNota()));

    Pag pag = new Pag();
    pag.getDetPag().add(detPag);
    return pag;
  }

  private InfAdic buildInfAdic() {
    InfAdic infAdic = new InfAdic();
    infAdic.setInfCpl(
        "NOTA FISCAL EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL. "
            + "IBS/CBS INFORMATIVOS - ANO-TESTE 2026 - EC 132/2023 / LC 214/2025.");
    return infAdic;
  }

  // =========================================================================
  // Helpers de formatação (esta camada concentra toda formatação de decimais)
  // =========================================================================

  /**
   * Formata um BigDecimal com 2 casas decimais (tipo TDec_1302 / TDec1302RTC).
   *
   * <p>Aceita null → retorna "0.00".
   */
  private String formatDecimal2(BigDecimal value) {
    if (value == null) {
      return "0.00";
    }
    return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /**
   * Formata um BigDecimal com {@code scale} casas decimais.
   *
   * @param value valor a formatar
   * @param scale número de casas decimais
   * @return string formatada
   */
  private String formatDecimal(BigDecimal value, int scale) {
    if (value == null) {
      return "0." + "0".repeat(scale);
    }
    return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
  }

  /**
   * Converte uma alíquota em proporção decimal (ex.: 0.0010) para percentual
   * no formato do XSD {@code TDec_0302_04RTC} (ex.: {@code "0.1000"} = 0,10%).
   *
   * <p>O XSD exige o valor em percentual (%), não em fração. Logo:
   * {@code 0.0010 * 100 = 0.10}, formatado como {@code "0.1000"} (4 casas).
   */
  private String formatAliquotaPercent(BigDecimal aliquotaDecimal) {
    if (aliquotaDecimal == null) {
      return "0.0000";
    }
    BigDecimal percent = aliquotaDecimal
        .multiply(BigDecimal.valueOf(100))
        .setScale(4, RoundingMode.HALF_UP);
    return percent.toPlainString();
  }

  /**
   * Resolve o identificador de destino ({@code idDest}) com base nas UFs
   * do emitente e destinatário.
   *
   * @param nota nota fiscal
   * @return {@code "1"} interna ou {@code "2"} interestadual
   */
  private String resolveIdDest(NotaFiscal nota) {
    Produtor produtor = nota.getProdutor();
    String ufEmit = produtor.getEstado() != null
        ? produtor.getEstado().toUpperCase() : "";
    String ufDest = nota.getDestinatarioEndereco() != null
        && nota.getDestinatarioEndereco().getUf() != null
        ? nota.getDestinatarioEndereco().getUf().toUpperCase() : "";
    return ufEmit.equals(ufDest) ? "1" : "2";
  }

  /**
   * Resolve o código do município do fato gerador ({@code cMunFG}).
   *
   * <p>É o município do <strong>emitente</strong> — o local onde a operação ocorre — e não o do
   * destinatário, como estava antes da issue #88. A presença é garantida pelo
   * {@code FiscalRulesValidator}.
   */
  private String resolveCMunFG(NotaFiscal nota) {
    EnderecoProdutor endereco = nota.getProdutor().getEndereco();
    if (endereco == null || endereco.getCodigoMunicipioIbge() == null) {
      throw new IllegalStateException(
          "Codigo IBGE do municipio do emitente ausente; a emissao deveria ter sido barrada"
              + " pela validacao fiscal");
    }
    return endereco.getCodigoMunicipioIbge();
  }

  /**
   * Código do Regime Tributário (CRT, C21) do emitente: 1 Simples, 4 MEI, 3 regime normal. Fonte
   * única do XML e da DANFE (#279).
   *
   * @param regime regime do produtor; nulo = regime normal
   * @return o CRT
   */
  public static String crt(RegimeTributario regime) {
    if (regime == null) {
      return "3";
    }
    return switch (regime) {
      case SIMPLES -> "1";
      case MEI -> "4";
      default -> "3"; // PRESUMIDO, REAL → Regime Normal
    };
  }

  /**
   * Trunca uma string para no máximo {@code maxLen} caracteres.
   *
   * @param s      string a truncar
   * @param maxLen tamanho máximo
   * @return string truncada ou a original se dentro do limite
   */
  private String truncate(String s, int maxLen) {
    if (s == null) {
      return "";
    }
    return s.length() <= maxLen ? s : s.substring(0, maxLen);
  }
}
