package br.com.agrocontabil.nfe;

import br.com.agrocontabil.model.RecipientAddress;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.TaxRegime;
import br.com.agrocontabil.model.DocumentType;
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
import br.com.agrocontabil.service.AccessKeyGenerator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Mapeia uma {@link Invoice} (entidade JPA) para o grafo de objetos JAXB
 * ({@link NFe} e sub-tipos), pronto para marshalling.
 *
 * <p>Esta camada concentra <strong>toda</strong> a lógica de formatação:
 * <ul>
 *   <li>Formatação de {@link BigDecimal} com escala exata por tipo de field do XSD</li>
 *   <li>Formatação de datas em {@code AAAA-MM-DDThh:mm:ssTZD}</li>
 *   <li>Zero-padding de série (3 dígitos) e número (9 dígitos)</li>
 *   <li>Derivação do código IBGE da UF ({@code cUF}) a partir da sigla</li>
 *   <li>Geração da key de acesso via {@link AccessKeyGenerator}</li>
 *   <li>Mapeamento de {@link TaxRegime} → CRT</li>
 * </ul>
 *
 * <p>As classes JAXB ({@code nfe/model/}) são <em>puras</em>: sem lógica, sem cálculo.
 *
 * <h3>Débito técnico — endereço do emitente:</h3>
 * <p>O {@link Producer} armazena apenas a UF ({@code state}). Os campos obrigatórios
 * do {@code enderEmit} no XSD (street, número, bairro, cMun, xMun, CEP) são
 * preenchidos com placeholders ({@code "SEM ENDERECO"}, {@code "S/N"}, {@code "CENTRO"},
 * cMun derivado do cUF concatenado com {@code "9999"}, etc.).
 * Endereço completo do emitente deve ser adicionado ao Producer em sprint futura.
 *
 * <h3>Débito técnico — IE do emitente:</h3>
 * <p>O Producer não possui field para Inscrição Estadual (Sprint 2). O mapper usa
 * {@code "ISENTO"} como placeholder para satisfazer o XSD — é um valor aceito pelo
 * layout para emitentes isentos ou producer rural.
 *
 * <h3>Débito técnico — IBS UF×Município:</h3>
 * <p>A entidade armazena a alíquota total de IBS sem discriminação por UF e município.
 * O mapper distribui igualmente (50%/50%) entre {@code pIBSUF} e {@code pIBSMun}.
 * Distribuição real exige parametrização por UF — sprint futura.
 */
@Component
public class InvoiceNfeMapper {

  private static final String NFE_VERSION = "4.00";
  private static final String TPAMB_HOMOLOGATION = "2";
  private static final String TPEMIS_NORMAL = "1";
  private static final String MOD_NF_E = "55";
  private static final String PROC_EMI = "0";
  private static final String VER_PROC = "1.0";
  private static final DateTimeFormatter DFE_DT_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

  /**
   * Tabela de UF → código IBGE (cUF).
   * Fonte: Manual de Orientação do Contribuinte NF-e 4.00, Anexo IV.
   */
  private static final Map<String, String> UF_CODE =
      Map.ofEntries(
          Map.entry("AC", "12"),
          Map.entry("AL", "27"),
          Map.entry("AM", "13"),
          Map.entry("AP", "16"),
          Map.entry("BA", "29"),
          Map.entry("CE", "23"),
          Map.entry("DF", "53"),
          Map.entry("ES", "32"),
          Map.entry("GO", "52"),
          Map.entry("MA", "21"),
          Map.entry("MG", "31"),
          Map.entry("MS", "50"),
          Map.entry("MT", "51"),
          Map.entry("PA", "15"),
          Map.entry("PB", "25"),
          Map.entry("PE", "26"),
          Map.entry("PI", "22"),
          Map.entry("PR", "41"),
          Map.entry("RJ", "33"),
          Map.entry("RN", "24"),
          Map.entry("RO", "11"),
          Map.entry("RR", "14"),
          Map.entry("RS", "43"),
          Map.entry("SC", "42"),
          Map.entry("SE", "28"),
          Map.entry("SP", "35"),
          Map.entry("TO", "17"));

  private final AccessKeyGenerator accessKeyGenerator;

  /** Constrói o mapper injetando o gerador de key de acesso. */
  public InvoiceNfeMapper(AccessKeyGenerator accessKeyGenerator) {
    this.accessKeyGenerator = accessKeyGenerator;
  }

  /**
   * Converte uma {@link Invoice} validada para o grafo JAXB {@link NFe}.
   *
   * @param invoice invoice fiscal com todos os campos obrigatórios preenchidos
   * @return grafo JAXB pronto para marshalling
   */
  public NFe toNfe(Invoice invoice) {
    Producer producer = invoice.getProducer();
    String uf = producer.getState() != null ? producer.getState().toUpperCase() : "SP";
    String cUF = UF_CODE.getOrDefault(uf, "35");

    String aamm = invoice.getIssuanceDate().format(DateTimeFormatter.ofPattern("yyMM"));

    String taxId = producer.getTaxId().replaceAll("\\D", "");
    int series = invoice.getSeries() != null ? invoice.getSeries() : 1;
    long number = invoice.getNumber();

    AccessKeyGenerator.AccessKeyResult keyResult =
        accessKeyGenerator.generate(cUF, aamm, taxId, series, number);

    NFe nfe = new NFe();
    InfNFe infNFe = new InfNFe();
    infNFe.setId("NFe" + keyResult.key());
    infNFe.setVersao(NFE_VERSION);
    infNFe.setIde(buildIde(invoice, cUF, keyResult));
    infNFe.setEmit(buildEmit(producer, cUF));
    infNFe.setDest(buildDest(invoice));
    infNFe.setDet(buildDet(invoice));
    infNFe.setTotal(buildTotal(invoice));
    infNFe.setTransp(buildTransp());
    infNFe.setPag(buildPag(invoice));
    infNFe.setInfAdic(buildInfAdic(invoice));
    nfe.setInfNFe(infNFe);
    return nfe;
  }

  // =========================================================================
  // Builders de seções
  // =========================================================================

  private Ide buildIde(
      Invoice invoice, String cUF, AccessKeyGenerator.AccessKeyResult keyResult) {

    Ide ide = new Ide();
    ide.setCUF(cUF);
    ide.setCNF(keyResult.cNF());
    ide.setNatOp(truncate(invoice.getOperationNature(), 60));
    ide.setMod(MOD_NF_E);
    // TSerie pattern: 0|[1-9]{1}[0-9]{0,2} — sem zero-padding
    ide.setSerie(String.valueOf(invoice.getSeries() != null ? invoice.getSeries() : 1));
    // TNF pattern: [1-9]{1}[0-9]{0,8} — sem zero-padding
    ide.setNNF(String.valueOf(invoice.getNumber()));
    ide.setDhEmi(invoice.getIssuanceDate().format(DFE_DT_FMT));
    if (invoice.getExitDate() != null) {
      ide.setDhSaiEnt(invoice.getExitDate().format(DFE_DT_FMT));
    }
    ide.setTpNF("1");   // saída
    ide.setIdDest(resolveIdDest(invoice));
    ide.setCMunFG(resolveCMunFG(invoice));
    ide.setTpImp("1");  // DANFE retrato
    ide.setTpEmis(TPEMIS_NORMAL);
    ide.setCDV(String.valueOf(keyResult.cDV()));
    ide.setTpAmb(TPAMB_HOMOLOGATION);
    ide.setFinNFe("1"); // NF-e normal
    ide.setIndFinal("0");
    ide.setIndPres("9"); // não presencial
    ide.setProcEmi(PROC_EMI);
    ide.setVerProc(VER_PROC);
    return ide;
  }

  private Emit buildEmit(Producer producer, String cUF) {
    Emit emit = new Emit();

    String taxId = producer.getTaxId().replaceAll("\\D", "");
    if (taxId.length() == 14) {
      emit.setCnpj(taxId);
    } else {
      // CPF: zero-pad para 11 dígitos
      emit.setCpf(accessKeyGenerator.zeroPad(taxId, 11));
    }

    String name = producer.getFullName();
    emit.setXNome(truncate(name != null ? name : "EMITENTE", 60));
    emit.setEnderEmit(buildEnderEmit(producer, cUF));
    emit.setIe("ISENTO"); // Débito técnico: IE ausente no Producer; placeholder aceitável no layout
    emit.setCrt(resolveCrt(producer.getTaxRegime()));
    return emit;
  }

  private EnderEmit buildEnderEmit(Producer producer, String cUF) {
    // Débito técnico: Producer não tem endereço completo (apenas state).
    // Placeholders para satisfazer o XSD enquanto endereço completo não está modelado.
    EnderEmit end = new EnderEmit();
    end.setXLgr("SEM ENDERECO");
    end.setNro("S/N");
    end.setXBairro("CENTRO");
    // cMun: código IBGE do município (7 dígitos). Placeholder: cUF + "99999" (não real).
    // Débito técnico: Producer não armazena código IBGE do município.
    end.setCMun(accessKeyGenerator.zeroPad(cUF + "99999", 7));
    end.setXMun("MUNICIPIO NAO INFORMADO");
    end.setUf(producer.getState() != null
        ? producer.getState().toUpperCase() : "SP");
    end.setCep("00000000");
    return end;
  }

  private Dest buildDest(Invoice invoice) {
    Dest dest = new Dest();

    String taxId = invoice.getRecipientTaxId().replaceAll("\\D", "");
    if (taxId.length() == 14) {
      dest.setCnpj(taxId);
    } else {
      dest.setCpf(accessKeyGenerator.zeroPad(taxId, 11));
    }

    dest.setXNome(truncate(invoice.getRecipientName(), 60));

    RecipientAddress end = invoice.getRecipientAddress();
    if (end != null) {
      dest.setEnderDest(buildEnderDest(end));
    }

    if (invoice.getRecipientStateRegistration() != null && !invoice.getRecipientStateRegistration().isBlank()) {
      dest.setIndIEDest("1");
      dest.setIe(invoice.getRecipientStateRegistration());
    } else {
      dest.setIndIEDest("9"); // não contribuinte
    }
    return dest;
  }

  private EnderDest buildEnderDest(RecipientAddress end) {
    EnderDest enderDest = new EnderDest();
    enderDest.setXLgr(
        end.getStreet() != null ? truncate(end.getStreet(), 60) : "SEM LOGRADOURO");
    enderDest.setNro(end.getNumber() != null ? truncate(end.getNumber(), 60) : "S/N");
    enderDest.setXBairro("CENTRO");
    enderDest.setCMun(
        end.getIbgeCityCode() != null ? end.getIbgeCityCode() : "0000000");
    enderDest.setXMun(
        end.getCity() != null ? truncate(end.getCity(), 60) : "MUNICIPIO");
    enderDest.setUf(end.getUf() != null ? end.getUf().toUpperCase() : "SP");
    if (end.getZipCode() != null && !end.getZipCode().isBlank()) {
      enderDest.setCep(end.getZipCode().replaceAll("\\D", ""));
    }
    return enderDest;
  }

  private List<Det> buildDet(Invoice invoice) {
    List<Det> detList = new ArrayList<>();
    for (InvoiceItem item : invoice.getItems()) {
      detList.add(buildDetItem(item));
    }
    return detList;
  }

  private Det buildDetItem(InvoiceItem item) {
    Det det = new Det();
    det.setNItem(String.valueOf(item.getItemNumber()));
    det.setProd(buildProd(item));
    det.setImposto(buildImposto(item));
    return det;
  }

  private Prod buildProd(InvoiceItem item) {
    Prod prod = new Prod();
    String codProd = item.getProductCode() != null
        ? item.getProductCode() : item.getCfop();
    prod.setCProd(truncate(codProd, 60));
    prod.setCEAN("SEM GTIN");
    prod.setXProd(truncate(item.getDescription(), 120));
    prod.setNcm(item.getNcm());
    prod.setCfop(item.getCfop());
    prod.setUCom(truncate(item.getUnit(), 6));
    // qCom: 4 casas decimais (TDec_1104v)
    prod.setQCom(formatDecimal(item.getQuantity(), 4));
    // vUnCom: 4 casas decimais (simplificado — XSD aceita até 10)
    prod.setVUnCom(formatDecimal(item.getUnitPrice(), 4));
    // vProd: 2 casas decimais (TDec_1302)
    prod.setVProd(formatDecimal2(item.getTotalValue()));
    prod.setCEANTrib("SEM GTIN");
    prod.setUTrib(truncate(item.getUnit(), 6));
    prod.setQTrib(formatDecimal(item.getQuantity(), 4));
    prod.setVUnTrib(formatDecimal(item.getUnitPrice(), 4));
    prod.setIndTot("1");
    return prod;
  }

  private Imposto buildImposto(InvoiceItem item) {
    Imposto imposto = new Imposto();
    if (item.getIbsValue() != null || item.getCbsValue() != null) {
      imposto.setIbsCbs(buildIbsCbs(item));
    }
    return imposto;
  }

  private IbsCbs buildIbsCbs(InvoiceItem item) {
    IbsCbs ibsCbs = new IbsCbs();
    ibsCbs.setCst(item.getCstIbsCbs() != null ? item.getCstIbsCbs() : "101");
    ibsCbs.setCClassTrib(item.getCClassTrib() != null ? item.getCClassTrib() : "010100");

    if (item.getIbsCbsCalculationBase() != null
        && item.getIbsRate() != null
        && item.getCbsRate() != null) {
      ibsCbs.setGIBSCBS(buildGIbsCbs(item));
    }
    return ibsCbs;
  }

  private IbsCbs.GIbsCbs buildGIbsCbs(InvoiceItem item) {
    IbsCbs.GIbsCbs g = new IbsCbs.GIbsCbs();
    g.setVBC(formatDecimal2(item.getIbsCbsCalculationBase()));

    // Débito técnico: alíquota total de IBS dividida igualmente UF 50% / Mun 50%
    // A entidade não discrimina as parcelas por ente federativo — sprint futura.
    BigDecimal alqIbs = item.getIbsRate();
    BigDecimal alqIbsUf = alqIbs.divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
    BigDecimal alqIbsMun = alqIbs.subtract(alqIbsUf);

    BigDecimal ibsValue = item.getIbsValue() != null
        ? item.getIbsValue() : BigDecimal.ZERO;
    BigDecimal valorIbsUf = ibsValue.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    BigDecimal valorIbsMun = ibsValue.subtract(valorIbsUf);

    IbsCbs.GIbsUf ibsUf = new IbsCbs.GIbsUf();
    ibsUf.setPIBSUF(formatAliquotaPercent(alqIbsUf));
    ibsUf.setVIBSUF(formatDecimal2(valorIbsUf));

    IbsCbs.GIbsMun ibsMun = new IbsCbs.GIbsMun();
    ibsMun.setPIBSMun(formatAliquotaPercent(alqIbsMun));
    ibsMun.setVIBSMun(formatDecimal2(valorIbsMun));

    g.setGIBSUF(ibsUf);
    g.setGIBSMun(ibsMun);
    g.setVIBS(formatDecimal2(ibsValue));

    BigDecimal alqCbs = item.getCbsRate();
    BigDecimal cbsValue = item.getCbsValue() != null ? item.getCbsValue() : BigDecimal.ZERO;

    IbsCbs.GCbs gCbs = new IbsCbs.GCbs();
    gCbs.setPCBS(formatAliquotaPercent(alqCbs));
    gCbs.setVCBS(formatDecimal2(cbsValue));

    g.setGCBS(gCbs);
    return g;
  }

  private Total buildTotal(Invoice invoice) {
    Total total = new Total();
    total.setIcmsTot(buildIcmsTot(invoice));
    if (invoice.getTotalIbsValue() != null || invoice.getTotalCbsValue() != null) {
      total.setIbsCbsTot(buildIbsCbsTot(invoice));
    }
    return total;
  }

  private IcmsTot buildIcmsTot(Invoice invoice) {
    IcmsTot t = new IcmsTot();
    String zero = "0.00";
    String vProd = formatDecimal2(invoice.getTotalProductsValue());
    String vNF = formatDecimal2(invoice.getTotalInvoiceValue());
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

  private IbsCbsTot buildIbsCbsTot(Invoice invoice) {
    BigDecimal ibsTotal = invoice.getTotalIbsValue() != null
        ? invoice.getTotalIbsValue() : BigDecimal.ZERO;
    BigDecimal cbsTotal = invoice.getTotalCbsValue() != null
        ? invoice.getTotalCbsValue() : BigDecimal.ZERO;

    // Base total = sum das bases dos items; fallback = totalProducts se items sem base
    BigDecimal baseTotal = invoice.getItems().stream()
        .map(i -> i.getIbsCbsCalculationBase() != null
            ? i.getIbsCbsCalculationBase() : BigDecimal.ZERO)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    IbsCbsTot tot = new IbsCbsTot();
    tot.setVBCIBSCBS(formatDecimal2(baseTotal));

    // gIBS
    BigDecimal ibsUf = ibsTotal.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    BigDecimal ibsMun = ibsTotal.subtract(ibsUf);

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
    ibsTot.setVIBS(formatDecimal2(ibsTotal));
    // Crédito presumido: zero neste escopo (producer rural sem crédito presumido)
    ibsTot.setVCredPres("0.00");
    ibsTot.setVCredPresCondSus("0.00");
    tot.setGIBS(ibsTot);

    // gCBS
    IbsCbsTot.GCbsTot cbsTot = new IbsCbsTot.GCbsTot();
    cbsTot.setVDif("0.00");
    cbsTot.setVDevTrib("0.00");
    cbsTot.setVCBS(formatDecimal2(cbsTotal));
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
   * <p>Para NF-e de producer rural sem transação financeira explícita, usa-se
   * {@code tPag="90"} (Sem Pagamento) com o valor total da invoice em {@code vPag}.
   */
  private Pag buildPag(Invoice invoice) {
    Pag.DetPag detPag = new Pag.DetPag();
    detPag.setTPag("90"); // Sem Pagamento
    detPag.setVPag(formatDecimal2(invoice.getTotalInvoiceValue()));

    Pag pag = new Pag();
    pag.getDetPag().add(detPag);
    return pag;
  }

  private InfAdic buildInfAdic(Invoice invoice) {
    StringBuilder infCpl = new StringBuilder(
        "NOTA FISCAL EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL. "
            + "IBS/CBS INFORMATIVOS - ANO-TESTE 2026 - EC 132/2023 / LC 214/2025.");
    if (invoice.getDocumentType() == DocumentType.NFA) {
      infCpl.append(" NOTA FISCAL AVULSA - PRODUTOR RURAL PESSOA FISICA.");
    }
    InfAdic infAdic = new InfAdic();
    infAdic.setInfCpl(infCpl.toString());
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
   * @param invoice invoice fiscal
   * @return {@code "1"} interna ou {@code "2"} interestadual
   */
  private String resolveIdDest(Invoice invoice) {
    Producer producer = invoice.getProducer();
    String issuerUf = producer.getState() != null
        ? producer.getState().toUpperCase() : "";
    String recipientUf = invoice.getRecipientAddress() != null
        && invoice.getRecipientAddress().getUf() != null
        ? invoice.getRecipientAddress().getUf().toUpperCase() : "";
    return issuerUf.equals(recipientUf) ? "1" : "2";
  }

  /**
   * Resolve o código do município do fato gerador ({@code cMunFG}).
   *
   * <p>Usa o código IBGE do município do destinatário quando disponível;
   * caso contrário, usa placeholder derivado do cUF.
   */
  private String resolveCMunFG(Invoice invoice) {
    RecipientAddress end = invoice.getRecipientAddress();
    if (end != null
        && end.getIbgeCityCode() != null
        && !end.getIbgeCityCode().isBlank()) {
      return end.getIbgeCityCode();
    }
    // Fallback: cUF + "9999" como código de município placeholder
    Producer producer = invoice.getProducer();
    String uf = producer.getState() != null ? producer.getState().toUpperCase() : "SP";
    String cUF = UF_CODE.getOrDefault(uf, "35");
    return cUF + "9999";
  }

  /**
   * Mapeia {@link TaxRegime} para o CRT da NF-e.
   *
   * <ul>
   *   <li>SIMPLES_NACIONAL → "1"</li>
   *   <li>outros / null → "3" (Regime Normal)</li>
   * </ul>
   */
  private String resolveCrt(TaxRegime regime) {
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
