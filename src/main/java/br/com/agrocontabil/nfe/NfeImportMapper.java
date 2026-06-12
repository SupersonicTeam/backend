package br.com.agrocontabil.nfe;

import br.com.agrocontabil.exception.XmlImportacaoException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Origem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumento;
import br.com.agrocontabil.nfe.model.Dest;
import br.com.agrocontabil.nfe.model.Det;
import br.com.agrocontabil.nfe.model.Emit;
import br.com.agrocontabil.nfe.model.EnderDest;
import br.com.agrocontabil.nfe.model.Ide;
import br.com.agrocontabil.nfe.model.InfNFe;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.nfe.model.Prod;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

/**
 * Mapeia o grafo JAXB {@link NFe} (XML importado de terceiro) para a entidade
 * {@link NotaFiscal}, marcando-a como {@link Origem#IMPORTADA} e
 * {@link StatusNotaFiscal#AUTORIZADA}.
 *
 * <p>O emitente do XML (terceiro) vai para {@code emitenteNome}/{@code emitenteCpfCnpj};
 * o {@code <dest>} do XML preenche o bloco destinatário; o {@code produtor} é o dono
 * autenticado (passado por parâmetro). Campos IBS/CBS por item ficam fora deste mapper
 * (o XML completo é preservado em {@code xmlGerado}).
 */
@Component
public class NfeImportMapper {

  /**
   * Constrói uma {@link NotaFiscal} importada a partir do grafo JAXB.
   *
   * @param nfe         grafo da NF-e importada
   * @param xmlOriginal XML original (armazenado em {@code xmlGerado})
   * @param dono        produtor autenticado, dono da nota importada
   * @return entidade pronta para persistência
   * @throws XmlImportacaoException se campos obrigatórios estiverem ausentes/inválidos
   */
  public NotaFiscal toEntity(NFe nfe, String xmlOriginal, Produtor dono) {
    InfNFe inf = nfe.getInfNFe();
    if (inf == null || inf.getIde() == null || inf.getEmit() == null) {
      throw new XmlImportacaoException("XML da NF-e sem grupos obrigatorios (ide/emit).");
    }
    Ide ide = inf.getIde();
    Emit emit = inf.getEmit();

    NotaFiscal nota = NotaFiscal.builder()
        .chaveAcesso(extrairChave(inf.getId()))
        .numero(parseLong(ide.getNNF(), "ide.nNF"))
        .serie(parseInt(ide.getSerie(), "ide.serie"))
        .modelo(ide.getMod() != null ? ide.getMod() : "55")
        .tipoDocumento(TipoDocumento.NFE)
        .origem(Origem.IMPORTADA)
        .status(StatusNotaFiscal.AUTORIZADA)
        .naturezaOperacao(ide.getNatOp() != null ? ide.getNatOp() : "IMPORTADA")
        .dataEmissao(parseData(ide.getDhEmi()))
        .produtor(dono)
        .emitenteNome(emit.getXNome())
        .emitenteCpfCnpj(emit.getCnpj() != null ? emit.getCnpj() : emit.getCpf())
        .versaoLayoutNfe(inf.getVersao())
        .xmlGerado(xmlOriginal)
        .dataGeracaoXml(OffsetDateTime.now())
        .valorTotalProdutos(extrairVProd(inf))
        .valorTotalNota(extrairVnf(inf))
        .build();

    aplicarDestinatario(nota, inf.getDest());
    aplicarItens(nota, inf);
    return nota;
  }

  private void aplicarDestinatario(NotaFiscal nota, Dest dest) {
    if (dest == null) {
      nota.setDestinatarioNome("DESTINATARIO NAO INFORMADO");
      nota.setDestinatarioCpfCnpj("00000000000");
      return;
    }
    nota.setDestinatarioNome(dest.getXNome() != null ? dest.getXNome() : "DESTINATARIO");
    nota.setDestinatarioCpfCnpj(dest.getCnpj() != null ? dest.getCnpj() : dest.getCpf());
    nota.setDestinatarioIe(dest.getIe());
    EnderDest end = dest.getEnderDest();
    if (end != null) {
      nota.setDestinatarioEndereco(EnderecoDestinatario.builder()
          .logradouro(end.getXLgr())
          .numero(end.getNro())
          .municipio(end.getXMun())
          .codigoMunicipioIbge(end.getCMun())
          .uf(end.getUf())
          .cep(end.getCep())
          .build());
    }
  }

  private void aplicarItens(NotaFiscal nota, InfNFe inf) {
    if (inf.getDet() == null) {
      return;
    }
    for (Det det : inf.getDet()) {
      Prod prod = det.getProd();
      if (prod == null) {
        continue;
      }
      NotaFiscalItem item = NotaFiscalItem.builder()
          .numeroItem(parseInt(det.getNItem(), "det.nItem"))
          .codigoProduto(prod.getCProd())
          .descricao(prod.getXProd())
          .ncm(prod.getNcm())
          .cfop(prod.getCfop())
          .unidade(prod.getUCom())
          .quantidade(parseDecimal(prod.getQCom(), "prod.qCom"))
          .valorUnitario(parseDecimal(prod.getVUnCom(), "prod.vUnCom"))
          .valorTotal(parseDecimal(prod.getVProd(), "prod.vProd"))
          .build();
      nota.addItem(item);
    }
  }

  private String extrairChave(String id) {
    if (id == null || !id.startsWith("NFe") || id.length() != 47) {
      throw new XmlImportacaoException("Atributo Id da infNFe ausente ou invalido: " + id);
    }
    return id.substring(3);
  }

  private BigDecimal extrairVProd(InfNFe inf) {
    if (inf.getTotal() != null && inf.getTotal().getIcmsTot() != null
        && inf.getTotal().getIcmsTot().getVProd() != null) {
      return new BigDecimal(inf.getTotal().getIcmsTot().getVProd());
    }
    return BigDecimal.ZERO;
  }

  private BigDecimal extrairVnf(InfNFe inf) {
    if (inf.getTotal() != null && inf.getTotal().getIcmsTot() != null
        && inf.getTotal().getIcmsTot().getVNF() != null) {
      return new BigDecimal(inf.getTotal().getIcmsTot().getVNF());
    }
    return BigDecimal.ZERO;
  }

  private long parseLong(String value, String campo) {
    try {
      return Long.parseLong(value.trim());
    } catch (Exception e) {
      throw new XmlImportacaoException("Campo numerico invalido (" + campo + "): " + value);
    }
  }

  private int parseInt(String value, String campo) {
    try {
      return Integer.parseInt(value.trim());
    } catch (Exception e) {
      throw new XmlImportacaoException("Campo inteiro invalido (" + campo + "): " + value);
    }
  }

  private BigDecimal parseDecimal(String value, String campo) {
    try {
      return new BigDecimal(value.trim());
    } catch (Exception e) {
      throw new XmlImportacaoException("Campo decimal invalido (" + campo + "): " + value);
    }
  }

  private OffsetDateTime parseData(String dhEmi) {
    try {
      return OffsetDateTime.parse(dhEmi.trim());
    } catch (Exception e) {
      throw new XmlImportacaoException("Data de emissao (dhEmi) invalida: " + dhEmi);
    }
  }
}
