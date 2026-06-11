package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;
import java.util.ArrayList;
import java.util.List;

/**
 * Grupo de dados de pagamento da NF-e (elemento {@code <pag>}).
 *
 * <p>Obrigatório no layout 4.00 para satisfazer o XSD. Contém ao menos um
 * {@link DetPag} descrevendo a forma de pagamento.
 *
 * <p>Para notas sem pagamento (produtores rurais sem transação financeira
 * explicitada), usa-se {@code tPag="90"} (Sem Pagamento) com {@code vPag="0.00"}.
 * O XSD permite omitir {@code vPag} quando {@code tPag=90}, mas incluímos
 * o valor zero para explicitar o campo.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "pag", namespace = NfeNamespace.NS, propOrder = {"detPag"})
public class Pag {

  @XmlElement(name = "detPag", namespace = NfeNamespace.NS, required = true)
  private List<DetPag> detPag = new ArrayList<>();

  /** Retorna os itens de detalhe de pagamento. */
  public List<DetPag> getDetPag() {
    return detPag;
  }

  /** Define os itens de detalhe de pagamento. */
  public void setDetPag(List<DetPag> detPag) {
    this.detPag = detPag;
  }

  // =========================================================================
  // Tipos internos
  // =========================================================================

  /**
   * Detalhe de forma de pagamento.
   *
   * <p>{@code tPag="90"} = Sem Pagamento (código previsto no XSD para NF-e
   * de produtor rural e similares sem pagamento explícito).
   */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(name = "detPag", namespace = NfeNamespace.NS, propOrder = {"tPag", "vPag"})
  public static class DetPag {

    /** Forma de pagamento: {@code "90"} = Sem Pagamento. */
    @XmlElement(name = "tPag", namespace = NfeNamespace.NS, required = true)
    private String tPag;

    /** Valor do pagamento. Pode ser {@code "0.00"} quando {@code tPag=90}. */
    @XmlElement(name = "vPag", namespace = NfeNamespace.NS, required = true)
    private String vPag;

    /** Retorna o código da forma de pagamento. */
    public String getTPag() { return tPag; }

    /** Define o código da forma de pagamento. */
    public void setTPag(String tPag) { this.tPag = tPag; }

    /** Retorna o valor do pagamento. */
    public String getVPag() { return vPag; }

    /** Define o valor do pagamento. */
    public void setVPag(String vPag) { this.vPag = vPag; }
  }
}
