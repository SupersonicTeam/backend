package br.com.agrocontabil.nfe;

import br.com.agrocontabil.common.UfIbge;
import br.com.agrocontabil.model.EnderecoDestinatario;
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
 * <h3>Débito técnico — endereço do emitente:</h3>
 * <p>O {@link Produtor} armazena apenas a UF ({@code estado}). Os campos obrigatórios
 * do {@code enderEmit} no XSD (logradouro, número, bairro, cMun, xMun, CEP) são
 * preenchidos com placeholders ({@code "SEM ENDERECO"}, {@code "S/N"}, {@code "CENTRO"},
 * cMun derivado do cUF concatenado com {@code "9999"}, etc.).
 * Endereço completo do emitente deve ser adicionado ao Produtor em sprint futura.
 *
 * <h3>Débito técnico — IE do emitente:</h3>
 * <p>O Produtor não possui campo para Inscrição Estadual (Sprint 2). O mapper usa
 * {@code "ISENTO"} como placeholder para satisfazer o XSD — é um valor aceito pelo
 * layout para emitentes isentos ou produtor rural.
 *
 * <h3>Split IBS UF×Município (issue Agrocontabil#31, RN002):</h3>
 * <p>As parcelas estadual e municipal do IBS são resolvidas por UF no {@code TributacaoService}
 * (tabela {@code aliquota_ibs_uf}) e gravadas no item ({@code aliquotaIbsUf}/{@code aliquotaIbsMun},
 * {@code valorIbsUf}/{@code valorIbsMun}). O mapper consome essas parcelas reais em {@code pIBSUF}/
 * {@code pIBSMun} e {@code vIBSUF}/{@code vIBSMun}, dispensando a antiga divisão 50/50. Para itens
 * legados (gravados antes da issue #31, sem as parcelas) mantém-se o fallback 50/50.
 */
@Component
public class NotaFiscalNfeMapper {

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

  /**
   * Tabela de UF → código IBGE (cUF).
   * Fonte: Manual de Orientação do Contribuinte NF-e 4.00, Anexo IV.
   */
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
    emit.setEnderEmit(buildEnderEmit(produtor, cUF));
    emit.setIe("ISENTO"); // Débito técnico: IE ausente no Produtor; placeholder aceitável no layout
    emit.setCrt(resolveCrt(produtor.getRegimeTributario()));
    return emit;
  }

  private EnderEmit buildEnderEmit(Produtor produtor, String cUF) {
    // Débito técnico: Produtor não tem endereço completo (apenas estado).
    // Placeholders para satisfazer o XSD enquanto endereço completo não está modelado.
    EnderEmit end = new EnderEmit();
    end.setXLgr("SEM ENDERECO");
    end.setNro("S/N");
    end.setXBairro("CENTRO");
    // cMun: código IBGE do município (7 dígitos). Placeholder: cUF + "99999" (não real).
    // Débito técnico: Produtor não armazena código IBGE do município.
    end.setCMun(chaveAcessoGenerator.zeroPad(cUF + "99999", 7));
    end.setXMun("MUNICIPIO NAO INFORMADO");
    end.setUf(produtor.getEstado() != null
        ? produtor.getEstado().toUpperCase() : "SP");
    end.setCep("00000000");
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
    EnderDest enderDest = new EnderDest();
    enderDest.setXLgr(
        end.getLogradouro() != null ? truncate(end.getLogradouro(), 60) : "SEM LOGRADOURO");
    enderDest.setNro(end.getNumero() != null ? truncate(end.getNumero(), 60) : "S/N");
    enderDest.setXBairro("CENTRO");
    enderDest.setCMun(
        end.getCodigoMunicipioIbge() != null ? end.getCodigoMunicipioIbge() : "0000000");
    enderDest.setXMun(
        end.getMunicipio() != null ? truncate(end.getMunicipio(), 60) : "MUNICIPIO");
    enderDest.setUf(end.getUf() != null ? end.getUf().toUpperCase() : "SP");
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

  private IbsCbs buildIbsCbs(NotaFiscalItem item) {
    IbsCbs ibsCbs = new IbsCbs();
    ibsCbs.setCst(item.getCstIbsCbs() != null ? item.getCstIbsCbs() : "101");
    ibsCbs.setCClassTrib(item.getCClassTrib() != null ? item.getCClassTrib() : "010100");

    if (item.getBaseCalculoIbsCbs() != null
        && item.getAliquotaIbs() != null
        && item.getAliquotaCbs() != null) {
      ibsCbs.setGIBSCBS(buildGIbsCbs(item));
    }
    return ibsCbs;
  }

  private IbsCbs.GIbsCbs buildGIbsCbs(NotaFiscalItem item) {
    IbsCbs.GIbsCbs g = new IbsCbs.GIbsCbs();
    g.setVBC(formatDecimal2(item.getBaseCalculoIbsCbs()));

    // Parcelas estadual/municipal reais de IBS (issue #31): resolvidas por UF no
    // TributacaoService e gravadas no item. Fallback 50/50 apenas para itens legados
    // gravados antes da issue #31 (parcelas ausentes) — mantém compatibilidade.
    BigDecimal alqIbs = item.getAliquotaIbs();
    BigDecimal valorIbs = item.getValorIbs() != null
        ? item.getValorIbs() : BigDecimal.ZERO;

    BigDecimal alqIbsUf = item.getAliquotaIbsUf();
    BigDecimal alqIbsMun = item.getAliquotaIbsMun();
    if (alqIbsUf == null || alqIbsMun == null) {
      alqIbsUf = alqIbs.divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
      alqIbsMun = alqIbs.subtract(alqIbsUf);
    }

    BigDecimal valorIbsUf = item.getValorIbsUf();
    BigDecimal valorIbsMun = item.getValorIbsMun();
    if (valorIbsUf == null || valorIbsMun == null) {
      valorIbsUf = valorIbs.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
      valorIbsMun = valorIbs.subtract(valorIbsUf);
    }

    IbsCbs.GIbsUf ibsUf = new IbsCbs.GIbsUf();
    ibsUf.setPIBSUF(formatAliquotaPercent(alqIbsUf));
    ibsUf.setVIBSUF(formatDecimal2(valorIbsUf));

    IbsCbs.GIbsMun ibsMun = new IbsCbs.GIbsMun();
    ibsMun.setPIBSMun(formatAliquotaPercent(alqIbsMun));
    ibsMun.setVIBSMun(formatDecimal2(valorIbsMun));

    g.setGIBSUF(ibsUf);
    g.setGIBSMun(ibsMun);
    g.setVIBS(formatDecimal2(valorIbs));

    BigDecimal alqCbs = item.getAliquotaCbs();
    BigDecimal valorCbs = item.getValorCbs() != null ? item.getValorCbs() : BigDecimal.ZERO;

    IbsCbs.GCbs gCbs = new IbsCbs.GCbs();
    gCbs.setPCBS(formatAliquotaPercent(alqCbs));
    gCbs.setVCBS(formatDecimal2(valorCbs));

    g.setGCBS(gCbs);
    return g;
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

  private IbsCbsTot buildIbsCbsTot(NotaFiscal nota) {
    BigDecimal totalIbs = nota.getValorTotalIbs() != null
        ? nota.getValorTotalIbs() : BigDecimal.ZERO;
    BigDecimal totalCbs = nota.getValorTotalCbs() != null
        ? nota.getValorTotalCbs() : BigDecimal.ZERO;

    // Base total = soma das bases dos itens; fallback = totalProdutos se itens sem base
    BigDecimal baseTotal = nota.getItens().stream()
        .map(i -> i.getBaseCalculoIbsCbs() != null
            ? i.getBaseCalculoIbsCbs() : BigDecimal.ZERO)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    IbsCbsTot tot = new IbsCbsTot();
    tot.setVBCIBSCBS(formatDecimal2(baseTotal));

    // gIBS — soma das parcelas estadual/municipal reais dos itens (issue #31).
    // Fallback 50/50 apenas se algum item não trouxer as parcelas (item legado).
    boolean parcelasReais = nota.getItens().stream()
        .allMatch(i -> i.getValorIbsUf() != null && i.getValorIbsMun() != null);
    BigDecimal ibsUf;
    BigDecimal ibsMun;
    if (parcelasReais) {
      ibsUf = nota.getItens().stream()
          .map(NotaFiscalItem::getValorIbsUf)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
      ibsMun = nota.getItens().stream()
          .map(NotaFiscalItem::getValorIbsMun)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    } else {
      ibsUf = totalIbs.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
      ibsMun = totalIbs.subtract(ibsUf);
    }

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
   * <p>Usa o código IBGE do município do destinatário quando disponível;
   * caso contrário, usa placeholder derivado do cUF.
   */
  private String resolveCMunFG(NotaFiscal nota) {
    EnderecoDestinatario end = nota.getDestinatarioEndereco();
    if (end != null
        && end.getCodigoMunicipioIbge() != null
        && !end.getCodigoMunicipioIbge().isBlank()) {
      return end.getCodigoMunicipioIbge();
    }
    // Fallback: cUF + "9999" como código de município placeholder
    Produtor produtor = nota.getProdutor();
    String cUF = UfIbge.codigo(produtor.getEstado()).orElse("35");
    return cUF + "9999";
  }

  /**
   * Mapeia {@link RegimeTributario} para o CRT da NF-e.
   *
   * <ul>
   *   <li>SIMPLES_NACIONAL → "1"</li>
   *   <li>outros / null → "3" (Regime Normal)</li>
   * </ul>
   */
  private String resolveCrt(RegimeTributario regime) {
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
