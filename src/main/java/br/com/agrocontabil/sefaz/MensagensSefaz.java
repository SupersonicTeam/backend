package br.com.agrocontabil.sefaz;

import java.util.regex.Pattern;

/**
 * Mensagens SOAP 1.2 dos webservices NF-e 4.00 (issue #128), montadas como texto.
 *
 * <p>Sem JAX-WS de propósito: o envelope da NF-e 4.00 é fixo e o mesmo em todas as UFs (o que
 * muda é só o endereço), e gerar clientes a partir do WSDL de cada autorizadora traria código
 * gerado e uma pilha SOAP inteira para três operações. O conteúdo variável é a NF-e já assinada
 * (inserida como veio, sem a declaração XML) e campos validados antes (chave de 44 dígitos).
 */
final class MensagensSefaz {

  /** Namespace do layout da NF-e. */
  static final String NS_NFE = "http://www.portalfiscal.inf.br/nfe";

  /** Namespace do envelope SOAP 1.2. */
  static final String NS_SOAP12 = "http://www.w3.org/2003/05/soap-envelope";

  private static final Pattern DECLARACAO_XML = Pattern.compile("^\\s*<\\?xml[^>]*\\?>\\s*");
  private static final Pattern CHAVE = Pattern.compile("\\d{44}");

  private MensagensSefaz() {}

  /**
   * Envio síncrono de uma NF-e ({@code enviNFe} com {@code indSinc=1}).
   *
   * @param xmlAssinado NF-e assinada
   * @param idLote      identificador do lote (até 15 dígitos)
   * @return o envelope SOAP
   */
  static String autorizacao(String xmlAssinado, long idLote) {
    String nfe = DECLARACAO_XML.matcher(xmlAssinado).replaceFirst("");
    return envelope(ServicoSefaz.AUTORIZACAO, "<enviNFe xmlns=\"" + NS_NFE + "\" versao=\"4.00\">"
        + "<idLote>" + idLote + "</idLote><indSinc>1</indSinc>" + nfe + "</enviNFe>");
  }

  /**
   * Consulta da situação pela chave ({@code consSitNFe}).
   *
   * @param chaveAcesso chave de 44 dígitos
   * @param ambiente    ambiente
   * @return o envelope SOAP
   * @throws IllegalArgumentException se a chave não tem 44 dígitos (nada vai ao fisco)
   */
  static String consulta(String chaveAcesso, SefazAmbiente ambiente) {
    exigirChave(chaveAcesso);
    return envelope(ServicoSefaz.CONSULTA, "<consSitNFe xmlns=\"" + NS_NFE + "\" versao=\"4.00\">"
        + "<tpAmb>" + tpAmb(ambiente) + "</tpAmb><xServ>CONSULTAR</xServ>"
        + "<chNFe>" + chaveAcesso + "</chNFe></consSitNFe>");
  }

  /** Id do {@code infEvento} de cancelamento: "ID" + tipo + chave + sequência de 2 dígitos. */
  static String idEventoCancelamento(String chaveAcesso) {
    exigirChave(chaveAcesso);
    return "ID" + RetornoSefazParser.TIPO_EVENTO_CANCELAMENTO + chaveAcesso + "01";
  }

  /**
   * Evento de cancelamento 110111 ainda <strong>sem assinatura</strong> ({@code evento} com o
   * {@code infEvento}); quem chama assina o {@code infEvento} e o põe no lote com
   * {@link #loteEvento}.
   *
   * @param chaveAcesso   chave da NF-e
   * @param documento     CPF (11) ou CNPJ (14) do autor, o emitente
   * @param protocolo     protocolo de autorização da NF-e
   * @param justificativa motivo (15 a 255 caracteres, escapado aqui)
   * @param dhEvento      instante do evento, com fuso
   * @param ambiente      ambiente
   * @return o elemento {@code evento}
   */
  static String eventoCancelamento(String chaveAcesso, String documento, String protocolo,
      String justificativa, java.time.OffsetDateTime dhEvento, SefazAmbiente ambiente) {
    String tagDocumento = documento.length() == 11 ? "CPF" : "CNPJ";
    return "<evento xmlns=\"" + NS_NFE + "\" versao=\"1.00\"><infEvento Id=\""
        + idEventoCancelamento(chaveAcesso) + "\">"
        + "<cOrgao>" + cuf(chaveAcesso) + "</cOrgao><tpAmb>" + tpAmb(ambiente) + "</tpAmb>"
        + "<" + tagDocumento + ">" + documento + "</" + tagDocumento + ">"
        + "<chNFe>" + chaveAcesso + "</chNFe>"
        + "<dhEvento>" + dhEvento.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "</dhEvento>"
        + "<tpEvento>" + RetornoSefazParser.TIPO_EVENTO_CANCELAMENTO + "</tpEvento>"
        + "<nSeqEvento>1</nSeqEvento><verEvento>1.00</verEvento>"
        + "<detEvento versao=\"1.00\"><descEvento>Cancelamento</descEvento>"
        + "<nProt>" + escapar(protocolo) + "</nProt><xJust>" + escapar(justificativa) + "</xJust>"
        + "</detEvento></infEvento></evento>";
  }

  /**
   * Lote de um evento já assinado ({@code envEvento}).
   *
   * @param eventoAssinado elemento {@code evento} com a assinatura
   * @param idLote         identificador do lote
   * @return o envelope SOAP
   */
  static String loteEvento(String eventoAssinado, long idLote) {
    String evento = DECLARACAO_XML.matcher(eventoAssinado).replaceFirst("");
    return envelope(ServicoSefaz.EVENTO, "<envEvento xmlns=\"" + NS_NFE + "\" versao=\"1.00\">"
        + "<idLote>" + idLote + "</idLote>" + evento + "</envEvento>");
  }

  /**
   * Texto livre para dentro de um elemento XML: escapa os caracteres especiais e tira quebras de
   * linha e espaços repetidos, que a SEFAZ recusa na justificativa.
   */
  static String escapar(String texto) {
    if (texto == null) {
      return "";
    }
    // Caractere de controle (colado de outro sistema) torna o XML 1.0 invalido: o evento nao
    // montaria e o cancelamento falharia sempre. Sai, junto com quebras de linha.
    return texto.replaceAll("[\\p{Cntrl}&&[^\\t\\n\\r]]", "").replaceAll("\\s+", " ").trim()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;");
  }

  /** Código do ambiente no layout: 1 produção, 2 homologação. */
  static String tpAmb(SefazAmbiente ambiente) {
    return ambiente == SefazAmbiente.PRODUCAO ? "1" : "2";
  }

  /** cUF da autorizadora: os 2 primeiros dígitos da chave. */
  static String cuf(String chaveAcesso) {
    exigirChave(chaveAcesso);
    return chaveAcesso.substring(0, 2);
  }

  static void exigirChave(String chaveAcesso) {
    if (chaveAcesso == null || !CHAVE.matcher(chaveAcesso).matches()) {
      throw new IllegalArgumentException("Chave de acesso deve ter 44 digitos");
    }
  }

  private static String envelope(ServicoSefaz servico, String conteudo) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<soap12:Envelope xmlns:soap12=\"" + NS_SOAP12 + "\"><soap12:Body>"
        + "<nfeDadosMsg xmlns=\"" + servico.namespace() + "\">" + conteudo + "</nfeDadosMsg>"
        + "</soap12:Body></soap12:Envelope>";
  }
}
