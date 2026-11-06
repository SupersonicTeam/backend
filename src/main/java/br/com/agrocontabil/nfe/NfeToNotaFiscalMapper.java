package br.com.agrocontabil.nfe;

import br.com.agrocontabil.exception.XmlImportacaoInvalidaException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.nfe.model.Dest;
import br.com.agrocontabil.nfe.model.Det;
import br.com.agrocontabil.nfe.model.Emit;
import br.com.agrocontabil.nfe.model.EnderDest;
import br.com.agrocontabil.nfe.model.Ide;
import br.com.agrocontabil.nfe.model.InfNFe;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.nfe.model.Prod;
import br.com.agrocontabil.service.ClassificacaoTributariaService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

/**
 * Mapeia o grafo JAXB {@link NFe} de um XML importado (issue #19 — UC002) para a entidade
 * {@link NotaFiscal}, no sentido inverso de {@link NotaFiscalNfeMapper}.
 *
 * <p><strong>Decisao de produto:</strong> a nota importada e uma nota de <em>entrada</em>
 * (compra). O produtor autenticado e o <strong>destinatario</strong>; o emitente do XML e um
 * terceiro, cujos dados vao para as colunas {@code emitente_nome}/{@code emitente_cpf_cnpj}.
 *
 * <p>Origem = {@link OrigemNotaFiscal#IMPORTADA}. O status inicial e sempre
 * {@code PENDENTE_SEFAZ} (issue #223): nada do XML prova a autorizacao — o {@code protNFe} vem do
 * proprio usuario. Quem decide e a consulta a SEFAZ pela chave, no
 * {@code ImportacaoNotaFiscalService}. Antes, o {@code cStat=100} do upload virava
 * {@code AUTORIZADA} e gerava credito de IBS/CBS de NF-e forjada.
 *
 * <p><strong>NCM/CFOP:</strong> mapeados como informativos, sem validacao dura de existencia.
 * Uma nota real de terceiro pode conter NCM/CFOP fora do subconjunto curado do sistema — por
 * isso os {@code ConstraintValidator} {@code @ValidNcm}/{@code @ValidCfop} (que agem sobre o
 * DTO de emissao) nao se aplicam a importacao.
 *
 * <p><strong>LGPD:</strong> esta classe nunca loga CPF/CNPJ; o chamador mascara quando precisa.
 */
@Component
public class NfeToNotaFiscalMapper {

  /**
   * Converte um {@link NFe} importado em uma {@link NotaFiscal} de entrada, associada ao
   * produtor autenticado como destinatario.
   *
   * @param nfe           grafo desserializado do XML importado
   * @param produtor      produtor autenticado (destinatario/dono da nota importada)
   * @param xmlOriginal   XML bruto assinado (persistido em {@code xmlGerado} para auditoria)
   * @return entidade {@link NotaFiscal} em {@code PENDENTE_SEFAZ} (itens ja associados)
   * @throws XmlImportacaoInvalidaException se faltarem dados estruturais minimos (Id/ide/emit)
   */
  public NotaFiscal toNotaFiscal(NFe nfe, Produtor produtor, String xmlOriginal) {
    InfNFe infNFe = requireInfNFe(nfe);
    Ide ide = requireIde(infNFe);
    Emit emit = requireEmit(infNFe);

    String chaveAcesso = extrairChave(infNFe.getId());

    NotaFiscal nota = NotaFiscal.builder()
        .origem(OrigemNotaFiscal.IMPORTADA)
        .status(StatusNotaFiscal.PENDENTE_SEFAZ)
        .tipoDocumento(TipoDocumentoFiscal.NFE)
        .chaveAcesso(chaveAcesso)
        .numero(parseLong(ide.getNNF(), "numero da NF-e (nNF)"))
        .serie(parseInt(ide.getSerie()))
        .modelo(ide.getMod() != null ? ide.getMod() : "55")
        .naturezaOperacao(ide.getNatOp() != null ? ide.getNatOp() : "IMPORTACAO DE XML")
        .dataEmissao(parseData(ide.getDhEmi()))
        .dataSaida(ide.getDhSaiEnt() != null ? parseDataOpcional(ide.getDhSaiEnt()) : null)
        .produtor(produtor)
        // Emitente terceiro: colunas dedicadas (nao e o produtor).
        .emitenteNome(emit.getXNome())
        .emitenteCpfCnpj(emit.getCnpj() != null ? emit.getCnpj() : emit.getCpf())
        // Destinatario = produtor autenticado; o servico ja conferiu o CPF/CNPJ do dest (#223).
        .destinatarioNome(resolveDestNome(infNFe.getDest(), produtor))
        .destinatarioCpfCnpj(resolveDestCpfCnpj(infNFe.getDest(), produtor))
        .destinatarioIe(infNFe.getDest() != null ? infNFe.getDest().getIe() : null)
        .destinatarioEndereco(resolveEndereco(infNFe.getDest()))
        .valorTotalProdutos(BigDecimal.ZERO)
        .valorTotalNota(BigDecimal.ZERO)
        .xmlGerado(xmlOriginal)
        .versaoLayoutNfe(infNFe.getVersao() != null ? infNFe.getVersao() : "4.00")
        .dataGeracaoXml(OffsetDateTime.now())
        .build();

    BigDecimal totalProdutos = BigDecimal.ZERO;
    if (infNFe.getDet() != null) {
      for (Det det : infNFe.getDet()) {
        NotaFiscalItem item = toItem(det);
        nota.addItem(item);
        totalProdutos = totalProdutos.add(item.getValorTotal());
      }
    }
    nota.setValorTotalProdutos(totalProdutos);
    nota.setValorTotalNota(totalProdutos);

    return nota;
  }

  // =========================================================================
  // Itens
  // =========================================================================

  private NotaFiscalItem toItem(Det det) {
    Prod prod = det.getProd();
    if (prod == null) {
      throw new XmlImportacaoInvalidaException(
          "Item da NF-e sem grupo <prod> — XML incompleto.");
    }
    BigDecimal quantidade = parseDecimal(prod.getQCom());
    BigDecimal valorUnitario = parseDecimal(prod.getVUnCom());
    BigDecimal valorTotal = parseDecimalOpcional(prod.getVProd());
    if (valorTotal == null) {
      valorTotal = quantidade.multiply(valorUnitario);
    }

    return NotaFiscalItem.builder()
        .numeroItem(parseNItem(det.getNItem()))
        .codigoProduto(prod.getCProd())
        .descricao(prod.getXProd() != null ? prod.getXProd() : "ITEM IMPORTADO")
        // NCM/CFOP informativos: sem validacao dura de existencia (nota de terceiro).
        .ncm(prod.getNcm())
        .cfop(prod.getCfop())
        .unidade(prod.getUCom() != null ? prod.getUCom() : "UN")
        .quantidade(quantidade)
        .valorUnitario(valorUnitario)
        .valorTotal(valorTotal.setScale(2, java.math.RoundingMode.HALF_UP))
        .cClassTrib(cClassTribDeclarado(det, prod.getNcm()))
        .build();
  }

  /**
   * cClassTrib do XML do fornecedor, só para o leite do Anexo I, item 2 (#284), em que a regra
   * por NCM mudou e depende do destino: sem ele o crédito sairia pela regra do comprador (60% ou,
   * no UHT, zero) numa compra que o fornecedor declarou de outro jeito, e o crédito ficaria
   * diferente do destacado. Nos outros NCMs o crédito segue a regra por NCM, como antes.
   */
  private static String cClassTribDeclarado(Det det, String ncm) {
    if (ncm == null || !ClassificacaoTributariaService.LEITE_DO_ANEXO_I.contains(ncm)
        || det.getImposto() == null || det.getImposto().getIbsCbs() == null) {
      return null;
    }
    String declarado = det.getImposto().getIbsCbs().getCClassTrib();
    return declarado == null || declarado.isBlank() ? null : declarado.strip();
  }

  // =========================================================================
  // Resolucao de destinatario (produtor autenticado)
  // =========================================================================

  private String resolveDestNome(Dest dest, Produtor produtor) {
    if (dest != null && dest.getXNome() != null && !dest.getXNome().isBlank()) {
      return dest.getXNome();
    }
    return produtor.getNomeCompleto() != null ? produtor.getNomeCompleto() : "DESTINATARIO";
  }

  private String resolveDestCpfCnpj(Dest dest, Produtor produtor) {
    if (dest != null) {
      String doc = dest.getCnpj() != null ? dest.getCnpj() : dest.getCpf();
      if (doc != null && !doc.isBlank()) {
        return doc;
      }
    }
    String produtorDoc = produtor.getCpfCnpj() != null
        ? produtor.getCpfCnpj().replaceAll("\\D", "") : null;
    return produtorDoc != null && !produtorDoc.isBlank() ? produtorDoc : "00000000000";
  }

  private EnderecoDestinatario resolveEndereco(Dest dest) {
    if (dest == null || dest.getEnderDest() == null) {
      return null;
    }
    EnderDest end = dest.getEnderDest();
    return EnderecoDestinatario.builder()
        .logradouro(end.getXLgr())
        .numero(end.getNro())
        .municipio(end.getXMun())
        .codigoMunicipioIbge(end.getCMun())
        .uf(end.getUf())
        .cep(end.getCep())
        .build();
  }

  // =========================================================================
  // Validacao estrutural minima
  // =========================================================================

  private InfNFe requireInfNFe(NFe nfe) {
    if (nfe == null || nfe.getInfNFe() == null) {
      throw new XmlImportacaoInvalidaException("XML sem o grupo <infNFe>.");
    }
    return nfe.getInfNFe();
  }

  private Ide requireIde(InfNFe infNFe) {
    if (infNFe.getIde() == null) {
      throw new XmlImportacaoInvalidaException("XML sem o grupo de identificacao <ide>.");
    }
    return infNFe.getIde();
  }

  private Emit requireEmit(InfNFe infNFe) {
    if (infNFe.getEmit() == null) {
      throw new XmlImportacaoInvalidaException("XML sem o grupo de emitente <emit>.");
    }
    return infNFe.getEmit();
  }

  /** Extrai os 44 digitos da chave a partir do atributo Id ("NFe" + 44 digitos). */
  private String extrairChave(String id) {
    if (id == null) {
      throw new XmlImportacaoInvalidaException("XML sem o atributo Id da <infNFe> (chave de acesso).");
    }
    String chave = id.startsWith("NFe") ? id.substring(3) : id;
    if (!chave.matches("\\d{44}")) {
      throw new XmlImportacaoInvalidaException(
          "Chave de acesso invalida no XML (esperado NFe + 44 digitos numericos).");
    }
    return chave;
  }

  // =========================================================================
  // Parsers tolerantes
  // =========================================================================

  private OffsetDateTime parseData(String valor) {
    if (valor == null || valor.isBlank()) {
      throw new XmlImportacaoInvalidaException("Data de emissao (dhEmi) ausente no XML.");
    }
    try {
      return OffsetDateTime.parse(valor);
    } catch (DateTimeParseException e) {
      throw new XmlImportacaoInvalidaException("Data de emissao (dhEmi) em formato invalido.");
    }
  }

  private OffsetDateTime parseDataOpcional(String valor) {
    try {
      return OffsetDateTime.parse(valor);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private Long parseLong(String valor, String campo) {
    try {
      return Long.parseLong(valor.trim());
    } catch (NumberFormatException | NullPointerException e) {
      throw new XmlImportacaoInvalidaException("Campo " + campo + " ausente ou invalido no XML.");
    }
  }

  private Integer parseInt(String valor) {
    if (valor == null || valor.isBlank()) {
      return 1;
    }
    try {
      return Integer.parseInt(valor.trim());
    } catch (NumberFormatException e) {
      return 1;
    }
  }

  private Integer parseNItem(String nItem) {
    if (nItem == null || nItem.isBlank()) {
      return 1;
    }
    try {
      return Integer.parseInt(nItem.trim());
    } catch (NumberFormatException e) {
      return 1;
    }
  }

  private BigDecimal parseDecimal(String valor) {
    BigDecimal parsed = parseDecimalOpcional(valor);
    return parsed != null ? parsed : BigDecimal.ZERO;
  }

  private BigDecimal parseDecimalOpcional(String valor) {
    if (valor == null || valor.isBlank()) {
      return null;
    }
    try {
      return new BigDecimal(valor.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
