package br.com.agrocontabil.danfe;

import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.nfe.NotaFiscalNfeMapper;
import br.com.agrocontabil.nfe.SecureXmlFactories;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Valores fiscais que a DANFE imprime (NT 2026.010, issue #279), de uma fonte só.
 *
 * <p>Com o XML da nota ({@code xmlGerado}: o assinado, nas notas próprias, ou o do terceiro, nas
 * importadas), tudo vem do XML: é ele o documento fiscal, e a NT proíbe imprimir o que nele não
 * está. Sem XML (preview de rascunho, #89), vem da nota pelos mesmos helpers do
 * {@link NotaFiscalNfeMapper} que montarão o XML, então o preview mostra o que a emissão gravará.
 *
 * <p>Alíquotas em percentual ({@code 0.1000} = 0,10%), como no XML. A alíquota de cada esfera é a
 * impressa: a efetiva ({@code pAliqEfet}) quando há {@code gRed}, a vigente sem ele. Valores
 * ausentes ficam nulos (a DANFE imprime vazio).
 *
 * @param crt código do regime tributário ({@code emit/CRT}); nulo se ausente
 * @param bairroDestinatario {@code enderDest/xBairro}
 * @param totais totais do {@code ICMSTot}
 * @param totalCbs {@code IBSCBSTot/gCBS/vCBS}; nulo sem o grupo
 * @param totalIbsUf {@code IBSCBSTot/gIBS/gIBSUF/vIBSUF}; nulo sem o grupo
 * @param totalIbsMun {@code IBSCBSTot/gIBS/gIBSMun/vIBSMun}; nulo sem o grupo
 * @param itens itens na ordem do documento
 */
record DanfeValores(String crt, String bairroDestinatario, Totais totais, BigDecimal totalCbs,
    BigDecimal totalIbsUf, BigDecimal totalIbsMun, List<Item> itens) {

  /** Totais do grupo {@code ICMSTot}. */
  record Totais(BigDecimal vBC, BigDecimal vICMS, BigDecimal vBCST, BigDecimal vST,
      BigDecimal vIPI, BigDecimal vProd, BigDecimal vFrete, BigDecimal vSeg, BigDecimal vDesc,
      BigDecimal vOutro, BigDecimal vNF) {
  }

  /**
   * Um item ({@code det}).
   *
   * @param ibsCbs presença do grupo {@code gIBSCBS} (base, alíquotas e valores)
   */
  record Item(String codigo, String descricao, String ncm, String cfop, BigDecimal quantidade,
      String unidade, BigDecimal valorUnitario, BigDecimal valorTotal, String cst,
      String cClassTrib, boolean ibsCbs, BigDecimal base, BigDecimal aliquotaCbs,
      BigDecimal aliquotaIbsUf, BigDecimal aliquotaIbsMun, BigDecimal valorCbs,
      BigDecimal valorIbsUf, BigDecimal valorIbsMun) {
  }

  /**
   * Lê os valores do XML da NF-e (com ou sem {@code nfeProc}), pelo parser sem DTD.
   *
   * @param xml XML da nota
   * @return os valores
   * @throws IllegalArgumentException se o XML não tiver {@code infNFe}
   */
  static DanfeValores doXml(String xml) {
    Element inf = filho(documento(xml).getDocumentElement(), "infNFe");
    if (inf == null) {
      throw new IllegalArgumentException("XML da nota sem infNFe");
    }
    Element total = filho(inf, "total");
    Element icms = filho(total, "ICMSTot");
    Element ibsCbsTot = filho(total, "IBSCBSTot");
    Element gIbsTot = filho(ibsCbsTot, "gIBS");

    List<Item> itens = new ArrayList<>();
    for (Node no = inf.getFirstChild(); no != null; no = no.getNextSibling()) {
      if (no instanceof Element det && "det".equals(det.getLocalName())) {
        itens.add(itemDoXml(det));
      }
    }
    return new DanfeValores(
        texto(filho(inf, "emit"), "CRT"),
        texto(filho(filho(inf, "dest"), "enderDest"), "xBairro"),
        new Totais(numero(icms, "vBC"), numero(icms, "vICMS"), numero(icms, "vBCST"),
            numero(icms, "vST"), numero(icms, "vIPI"), numero(icms, "vProd"),
            numero(icms, "vFrete"), numero(icms, "vSeg"), numero(icms, "vDesc"),
            numero(icms, "vOutro"), numero(icms, "vNF")),
        numero(filho(ibsCbsTot, "gCBS"), "vCBS"),
        numero(filho(gIbsTot, "gIBSUF"), "vIBSUF"),
        numero(filho(gIbsTot, "gIBSMun"), "vIBSMun"),
        List.copyOf(itens));
  }

  private static Item itemDoXml(Element det) {
    Element prod = filho(det, "prod");
    Element ibsCbs = filho(filho(det, "imposto"), "IBSCBS");
    Element g = filho(ibsCbs, "gIBSCBS");
    Element uf = filho(g, "gIBSUF");
    Element mun = filho(g, "gIBSMun");
    Element cbs = filho(g, "gCBS");
    return new Item(
        texto(prod, "cProd"), texto(prod, "xProd"), texto(prod, "NCM"), texto(prod, "CFOP"),
        numero(prod, "qCom"), texto(prod, "uCom"), numero(prod, "vUnCom"),
        numero(prod, "vProd"),
        texto(ibsCbs, "CST"), texto(ibsCbs, "cClassTrib"), g != null, numero(g, "vBC"),
        aliquotaImpressa(cbs, "pCBS"), aliquotaImpressa(uf, "pIBSUF"),
        aliquotaImpressa(mun, "pIBSMun"),
        numero(cbs, "vCBS"), numero(uf, "vIBSUF"), numero(mun, "vIBSMun"));
  }

  /** {@code pAliqEfet} do {@code gRed} da esfera, se houver; senão a alíquota vigente. */
  private static BigDecimal aliquotaImpressa(Element esfera, String vigente) {
    BigDecimal efetiva = numero(filho(esfera, "gRed"), "pAliqEfet");
    return efetiva != null ? efetiva : numero(esfera, vigente);
  }

  /**
   * Monta os valores a partir da nota, pelos helpers do {@link NotaFiscalNfeMapper} (os mesmos que
   * montam o XML): o preview mostra o que a emissão vai gravar.
   *
   * @param nota nota com itens carregados
   * @return os valores
   */
  static DanfeValores daNota(NotaFiscal nota) {
    List<Item> itens = new ArrayList<>();
    for (NotaFiscalItem item : nota.getItens()) {
      itens.add(itemDaNota(item));
    }
    boolean comIbsCbsTot = nota.getValorTotalIbs() != null || nota.getValorTotalCbs() != null;
    NotaFiscalNfeMapper.TotaisIbs ibs = NotaFiscalNfeMapper.totaisIbs(nota);
    BigDecimal zero = new BigDecimal("0.00");
    return new DanfeValores(
        nota.getProdutor() != null
            ? NotaFiscalNfeMapper.crt(nota.getProdutor().getTipoProdutor(),
                nota.getProdutor().getRegimeTributario()) : null,
        nota.getDestinatarioEndereco() != null ? NotaFiscalNfeMapper.BAIRRO_DESTINATARIO : null,
        // O ICMSTot da nota própria leva zero no que não é produto/nota (ICMS e Funrural por
        // guia; docs/premissas-e-escopo.md), como o buildIcmsTot do mapper.
        new Totais(zero, zero, zero, zero, zero, nota.getValorTotalProdutos(), zero, zero, zero,
            zero, nota.getValorTotalNota()),
        comIbsCbsTot ? orZero(nota.getValorTotalCbs()) : null,
        comIbsCbsTot ? ibs.valorUf() : null,
        comIbsCbsTot ? ibs.valorMun() : null,
        List.copyOf(itens));
  }

  private static Item itemDaNota(NotaFiscalItem item) {
    // Mesmas condições do buildImposto/buildIbsCbs do mapper: grupo IBSCBS só com valor de IBS ou
    // CBS; gIBSCBS só com valor do item e as duas alíquotas.
    boolean grupo = item.getValorIbs() != null || item.getValorCbs() != null;
    boolean g = grupo && item.getValorTotal() != null && item.getAliquotaIbs() != null
        && item.getAliquotaCbs() != null;
    NotaFiscalNfeMapper.ParcelasIbs parcelas = g ? NotaFiscalNfeMapper.parcelasIbs(item) : null;
    return new Item(
        item.getCodigoProduto() != null ? item.getCodigoProduto()
            : String.valueOf(item.getNumeroItem()),
        item.getDescricao(), item.getNcm(), item.getCfop(), item.getQuantidade(),
        item.getUnidade(), item.getValorUnitario(), item.getValorTotal(),
        grupo ? NotaFiscalNfeMapper.cst(item) : null,
        grupo ? NotaFiscalNfeMapper.cClassTrib(item) : null,
        g, g ? item.getValorTotal() : null,
        g ? percentual(NotaFiscalNfeMapper.aliquotaEfetiva(item.getAliquotaCbs(), item)) : null,
        g ? percentual(NotaFiscalNfeMapper.aliquotaEfetiva(parcelas.aliquotaUf(), item)) : null,
        g ? percentual(NotaFiscalNfeMapper.aliquotaEfetiva(parcelas.aliquotaMun(), item)) : null,
        g ? orZero(item.getValorCbs()) : null,
        g ? parcelas.valorUf() : null,
        g ? parcelas.valorMun() : null);
  }

  /** Proporção decimal ({@code 0.0010}) em percentual ({@code 0.10}). */
  private static BigDecimal percentual(BigDecimal proporcao) {
    return proporcao == null ? null : proporcao.movePointRight(2);
  }

  private static BigDecimal orZero(BigDecimal valor) {
    return valor != null ? valor : BigDecimal.ZERO;
  }

  private static Document documento(String xml) {
    try {
      return SecureXmlFactories.hardenedDocumentBuilderFactory().newDocumentBuilder()
          .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalArgumentException("XML da nota ilegível", e);
    }
  }

  /** Primeiro descendente com o nome local (busca em profundidade); nulo se não houver. */
  private static Element filho(Element pai, String nome) {
    if (pai == null) {
      return null;
    }
    for (Node no = pai.getFirstChild(); no != null; no = no.getNextSibling()) {
      if (no instanceof Element e) {
        if (nome.equals(e.getLocalName())) {
          return e;
        }
        Element neto = filho(e, nome);
        if (neto != null) {
          return neto;
        }
      }
    }
    return null;
  }

  private static String texto(Element pai, String nome) {
    Element e = filhoDireto(pai, nome);
    if (e == null) {
      return null;
    }
    String t = e.getTextContent().strip();
    return t.isEmpty() ? null : t;
  }

  private static BigDecimal numero(Element pai, String nome) {
    String t = texto(pai, nome);
    return t == null ? null : new BigDecimal(t);
  }

  /** Filho imediato com o nome local (os valores são folhas do grupo). */
  private static Element filhoDireto(Element pai, String nome) {
    if (pai == null) {
      return null;
    }
    for (Node no = pai.getFirstChild(); no != null; no = no.getNextSibling()) {
      if (no instanceof Element e && nome.equals(e.getLocalName())) {
        return e;
      }
    }
    return null;
  }
}
