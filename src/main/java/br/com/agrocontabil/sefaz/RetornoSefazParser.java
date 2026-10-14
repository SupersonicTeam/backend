package br.com.agrocontabil.sefaz;

import static br.com.agrocontabil.sefaz.MensagensSefaz.NS_NFE;
import static br.com.agrocontabil.sefaz.MensagensSefaz.NS_SOAP12;

import br.com.agrocontabil.exception.SefazConexaoException;
import br.com.agrocontabil.exception.SefazRespostaIndeterminadaException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Lê as respostas dos webservices NF-e 4.00 e as traduz no contrato do {@link SefazTransport}
 * (issue #128).
 *
 * <p>Rejeição de dados é resultado, não exceção ({@link SituacaoSefaz#REJEITADA}). Serviço
 * paralisado (108/109) e consumo indevido (656) dizem que <em>nada foi processado</em>: viram
 * {@link SefazConexaoException}, e reenviar é seguro. Resposta sem a estrutura esperada (sem
 * {@code cStat}, SOAP Fault) não diz se o fisco processou: vira
 * {@link SefazRespostaIndeterminadaException}, e o serviço consulta pela chave.
 */
final class RetornoSefazParser {

  /** Lote processado: o resultado de cada NF-e está no {@code protNFe}. */
  static final String LOTE_PROCESSADO = "104";

  /** Lote recebido para processamento assíncrono: a situação sai na consulta. */
  static final String LOTE_RECEBIDO = "103";

  static final String AUTORIZADO = "100";

  /** Cancelamento homologado (101) e homologado fora de prazo (151). */
  static final Set<String> CANCELADA = Set.of("101", "151");

  /** Uso denegado: irregularidade do emitente ou do destinatário. */
  static final Set<String> DENEGADA = Set.of("110", "301", "302", "303");

  static final String NAO_CONSTA = "217";

  /** Nada foi processado: serviço paralisado (108, 109) ou bloqueio por consumo indevido (656). */
  static final Set<String> NAO_PROCESSADO = Set.of("108", "109", "656");

  /** Evento de cancelamento registrado (vinculado ou não) à NF-e. */
  static final Set<String> EVENTO_REGISTRADO = Set.of("135", "155");

  static final String TIPO_EVENTO_CANCELAMENTO = "110111";

  private RetornoSefazParser() {}

  /**
   * Resposta do NFeAutorizacao4 ({@code retEnviNFe}).
   *
   * @param resposta envelope SOAP da resposta
   * @return situação da NF-e enviada
   */
  static SefazRetorno lerAutorizacao(Document resposta) {
    Element ret = retorno(resposta, "retEnviNFe");
    String cStatLote = obrigatorio(ret, "cStat");
    exigirProcessado(cStatLote, texto(ret, "xMotivo"));
    if (LOTE_RECEBIDO.equals(cStatLote)) {
      return new SefazRetorno(SituacaoSefaz.PENDENTE, cStatLote, texto(ret, "xMotivo"), null, null);
    }
    if (!LOTE_PROCESSADO.equals(cStatLote)) {
      // Rejeicao do lote inteiro (schema, certificado, ...): nenhuma NF-e foi autorizada.
      return new SefazRetorno(SituacaoSefaz.REJEITADA, cStatLote, texto(ret, "xMotivo"), null, null);
    }
    Element infProt = filho(ret, "infProt");
    if (infProt == null) {
      throw new SefazRespostaIndeterminadaException("retEnviNFe 104 sem protNFe/infProt");
    }
    String cStat = obrigatorio(infProt, "cStat");
    String xMotivo = texto(infProt, "xMotivo");
    if (AUTORIZADO.equals(cStat)) {
      return new SefazRetorno(SituacaoSefaz.AUTORIZADA, cStat, xMotivo, texto(infProt, "nProt"),
          data(texto(infProt, "dhRecbto")));
    }
    if (DENEGADA.contains(cStat)) {
      return new SefazRetorno(SituacaoSefaz.DENEGADA, cStat, xMotivo, texto(infProt, "nProt"),
          data(texto(infProt, "dhRecbto")));
    }
    // 204 (duplicidade) entra aqui: o SefazService reconcilia pela chave a partir do cStat.
    return new SefazRetorno(SituacaoSefaz.REJEITADA, cStat, xMotivo, null, null);
  }

  /**
   * Resposta do NFeConsultaProtocolo4 ({@code retConsSitNFe}), no mapeamento do contrato:
   * 100 → AUTORIZADA; 101/151 → CANCELADA (protocolo e data do <em>evento</em>); 110/301/302/303 →
   * DENEGADA; 217 → NAO_ENCONTRADA; qualquer outro (em processamento, erro de consulta) →
   * PENDENTE. Rejeitar por um código de consulta marcaria como rejeitada uma nota que o fisco
   * talvez tenha autorizado; pendente mantém a nota para a próxima sincronização.
   *
   * @param resposta envelope SOAP da resposta
   * @return situação atual da NF-e
   */
  static SefazRetorno lerConsulta(Document resposta) {
    Element ret = retorno(resposta, "retConsSitNFe");
    String cStat = obrigatorio(ret, "cStat");
    String xMotivo = texto(ret, "xMotivo");
    exigirProcessado(cStat, xMotivo);
    if (AUTORIZADO.equals(cStat) || DENEGADA.contains(cStat)) {
      Element infProt = filho(ret, "infProt");
      return new SefazRetorno(
          AUTORIZADO.equals(cStat) ? SituacaoSefaz.AUTORIZADA : SituacaoSefaz.DENEGADA,
          cStat, xMotivo,
          infProt == null ? null : texto(infProt, "nProt"),
          infProt == null ? null : data(texto(infProt, "dhRecbto")));
    }
    if (CANCELADA.contains(cStat)) {
      Element evento = eventoDeCancelamento(ret);
      return new SefazRetorno(SituacaoSefaz.CANCELADA, cStat, xMotivo,
          evento == null ? null : texto(evento, "nProt"),
          evento == null ? null : data(texto(evento, "dhRegEvento")));
    }
    if (NAO_CONSTA.equals(cStat)) {
      return new SefazRetorno(SituacaoSefaz.NAO_ENCONTRADA, cStat, xMotivo, null, null);
    }
    return new SefazRetorno(SituacaoSefaz.PENDENTE, cStat, xMotivo, null, null);
  }

  /** Lote de evento processado: o resultado de cada evento está no {@code retEvento}. */
  static final String LOTE_EVENTO_PROCESSADO = "128";

  /**
   * Resposta do NFeRecepcaoEvento4 ({@code retEnvEvento}) para o cancelamento. Registrado
   * (135/155) volta com protocolo e data do evento, como o mock: o {@code SefazService} decide
   * pelo cStat. 573 (evento já registrado) e outras recusas voltam como rejeição com o cStat; o
   * serviço reconcilia o 573 pela consulta.
   *
   * @param resposta envelope SOAP da resposta
   * @return o resultado do evento
   */
  static SefazRetorno lerEvento(Document resposta) {
    Element ret = retorno(resposta, "retEnvEvento");
    String cStatLote = obrigatorio(ret, "cStat");
    exigirProcessado(cStatLote, texto(ret, "xMotivo"));
    if (!LOTE_EVENTO_PROCESSADO.equals(cStatLote)) {
      return new SefazRetorno(SituacaoSefaz.REJEITADA, cStatLote, texto(ret, "xMotivo"), null, null);
    }
    NodeList eventos = ret.getElementsByTagNameNS(NS_NFE, "retEvento");
    Element info = eventos.getLength() == 0 ? null : filho((Element) eventos.item(0), "infEvento");
    if (info == null) {
      throw new SefazRespostaIndeterminadaException("retEnvEvento 128 sem retEvento/infEvento");
    }
    String cStat = obrigatorio(info, "cStat");
    String xMotivo = texto(info, "xMotivo");
    if (EVENTO_REGISTRADO.contains(cStat)) {
      return new SefazRetorno(SituacaoSefaz.AUTORIZADA, cStat, xMotivo, texto(info, "nProt"),
          data(texto(info, "dhRegEvento")));
    }
    return new SefazRetorno(SituacaoSefaz.REJEITADA, cStat, xMotivo, null, null);
  }

  /** {@code retEvento/infEvento} do cancelamento registrado, entre os eventos da NF-e. */
  private static Element eventoDeCancelamento(Element ret) {
    NodeList eventos = ret.getElementsByTagNameNS(NS_NFE, "retEvento");
    for (int i = 0; i < eventos.getLength(); i++) {
      Element info = filho((Element) eventos.item(i), "infEvento");
      if (info != null && TIPO_EVENTO_CANCELAMENTO.equals(texto(info, "tpEvento"))
          && EVENTO_REGISTRADO.contains(texto(info, "cStat"))) {
        return info;
      }
    }
    return null;
  }

  private static void exigirProcessado(String cStat, String xMotivo) {
    if (NAO_PROCESSADO.contains(cStat)) {
      throw new SefazConexaoException("SEFAZ nao processou a requisicao: cStat " + cStat
          + (xMotivo == null ? "" : " (" + xMotivo + ")"));
    }
  }

  /** O elemento de retorno dentro do {@code soap:Body}; fault ou ausência é indeterminada. */
  static Element retorno(Document resposta, String nome) {
    if (resposta.getElementsByTagNameNS(NS_SOAP12, "Fault").getLength() > 0) {
      throw new SefazRespostaIndeterminadaException("SEFAZ respondeu SOAP Fault");
    }
    NodeList encontrados = resposta.getElementsByTagNameNS(NS_NFE, nome);
    if (encontrados.getLength() == 0) {
      throw new SefazRespostaIndeterminadaException("Resposta da SEFAZ sem " + nome);
    }
    return (Element) encontrados.item(0);
  }

  private static String obrigatorio(Element pai, String nome) {
    String valor = texto(pai, nome);
    if (valor == null || valor.isBlank()) {
      throw new SefazRespostaIndeterminadaException("Resposta da SEFAZ sem " + nome);
    }
    return valor;
  }

  /** Texto do primeiro descendente com esse nome no namespace da NF-e. */
  private static String texto(Element pai, String nome) {
    Element e = filho(pai, nome);
    return e == null ? null : e.getTextContent().trim();
  }

  private static Element filho(Element pai, String nome) {
    NodeList lista = pai.getElementsByTagNameNS(NS_NFE, nome);
    for (int i = 0; i < lista.getLength(); i++) {
      Node n = lista.item(i);
      if (n.getNodeType() == Node.ELEMENT_NODE) {
        return (Element) n;
      }
    }
    return null;
  }

  /** Data no formato do layout (ISO com fuso); ilegível vira nula, sem derrubar o resultado. */
  private static OffsetDateTime data(String valor) {
    if (valor == null || valor.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(valor);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
