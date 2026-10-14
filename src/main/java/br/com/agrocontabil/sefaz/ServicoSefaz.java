package br.com.agrocontabil.sefaz;

/**
 * Webservices NF-e 4.00 usados pelo transporte real (issue #128), com o namespace do WSDL (que
 * envolve a mensagem em {@code nfeDadosMsg}) e a ação SOAP.
 */
enum ServicoSefaz {

  /** Envio da NF-e em lote síncrono ({@code indSinc=1}). */
  AUTORIZACAO("http://www.portalfiscal.inf.br/nfe/wsdl/NFeAutorizacao4", "nfeAutorizacaoLote"),

  /** Consulta da situação da NF-e pela chave de acesso. */
  CONSULTA("http://www.portalfiscal.inf.br/nfe/wsdl/NFeConsultaProtocolo4", "nfeConsultaNF"),

  /** Recepção de evento (cancelamento 110111). */
  EVENTO("http://www.portalfiscal.inf.br/nfe/wsdl/NFeRecepcaoEvento4", "nfeRecepcaoEvento");

  private final String namespace;
  private final String operacao;

  ServicoSefaz(String namespace, String operacao) {
    this.namespace = namespace;
    this.operacao = operacao;
  }

  /** Namespace do WSDL, o do elemento {@code nfeDadosMsg}. */
  String namespace() {
    return namespace;
  }

  /** Ação SOAP 1.2, no parâmetro {@code action} do {@code Content-Type}. */
  String acao() {
    return namespace + "/" + operacao;
  }
}
