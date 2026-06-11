package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlID;
import jakarta.xml.bind.annotation.XmlType;
import java.util.ArrayList;
import java.util.List;

/**
 * Grupo de informações da NF-e (tipo {@code infNFe} no XSD leiauteNFe_v4.00).
 *
 * <p>Contém todos os grupos obrigatórios do documento fiscal: identificação,
 * emitente, destinatário, detalhes dos itens, totais, transporte e informações
 * adicionais.
 *
 * <p>O atributo {@code Id} é prefixado com {@code "NFe"} seguido da chave de 44
 * dígitos, conforme exigido pelo layout (ex.: {@code NFe35261200000000000055001000000001100000001X}).
 * O atributo {@code versao} deve ser {@code "4.00"}.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
    name = "infNFe",
    namespace = NfeNamespace.NS,
    propOrder = {"ide", "emit", "dest", "det", "total", "transp", "pag", "infAdic"})
public class InfNFe {

  @XmlAttribute(name = "Id", required = true)
  @XmlID
  private String id;

  @XmlAttribute(name = "versao", required = true)
  private String versao;

  @XmlElement(name = "ide", namespace = NfeNamespace.NS, required = true)
  private Ide ide;

  @XmlElement(name = "emit", namespace = NfeNamespace.NS, required = true)
  private Emit emit;

  @XmlElement(name = "dest", namespace = NfeNamespace.NS)
  private Dest dest;

  @XmlElement(name = "det", namespace = NfeNamespace.NS, required = true)
  private List<Det> det = new ArrayList<>();

  @XmlElement(name = "total", namespace = NfeNamespace.NS, required = true)
  private Total total;

  @XmlElement(name = "transp", namespace = NfeNamespace.NS, required = true)
  private Transp transp;

  @XmlElement(name = "pag", namespace = NfeNamespace.NS, required = true)
  private Pag pag;

  @XmlElement(name = "infAdic", namespace = NfeNamespace.NS)
  private InfAdic infAdic;

  /** Retorna o atributo Id da infNFe (ex.: NFe + 44 dígitos). */
  public String getId() {
    return id;
  }

  /** Define o atributo Id. */
  public void setId(String id) {
    this.id = id;
  }

  /** Retorna a versão do layout (ex.: {@code "4.00"}). */
  public String getVersao() {
    return versao;
  }

  /** Define a versão do layout. */
  public void setVersao(String versao) {
    this.versao = versao;
  }

  /** Retorna a identificação da NF-e. */
  public Ide getIde() {
    return ide;
  }

  /** Define a identificação da NF-e. */
  public void setIde(Ide ide) {
    this.ide = ide;
  }

  /** Retorna o emitente. */
  public Emit getEmit() {
    return emit;
  }

  /** Define o emitente. */
  public void setEmit(Emit emit) {
    this.emit = emit;
  }

  /** Retorna o destinatário. */
  public Dest getDest() {
    return dest;
  }

  /** Define o destinatário. */
  public void setDest(Dest dest) {
    this.dest = dest;
  }

  /** Retorna a lista de detalhes (itens) da nota. */
  public List<Det> getDet() {
    return det;
  }

  /** Define a lista de detalhes. */
  public void setDet(List<Det> det) {
    this.det = det;
  }

  /** Retorna os totais da nota. */
  public Total getTotal() {
    return total;
  }

  /** Define os totais. */
  public void setTotal(Total total) {
    this.total = total;
  }

  /** Retorna os dados de transporte. */
  public Transp getTransp() {
    return transp;
  }

  /** Define os dados de transporte. */
  public void setTransp(Transp transp) {
    this.transp = transp;
  }

  /** Retorna o grupo de pagamento (obrigatório no layout 4.00). */
  public Pag getPag() {
    return pag;
  }

  /** Define o grupo de pagamento. */
  public void setPag(Pag pag) {
    this.pag = pag;
  }

  /** Retorna as informações adicionais (opcional). */
  public InfAdic getInfAdic() {
    return infAdic;
  }

  /** Define as informações adicionais. */
  public void setInfAdic(InfAdic infAdic) {
    this.infAdic = infAdic;
  }
}
