package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.nfe.VersaoRegraFiscal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * O XML guardado da nota foi gerado por uma versão da regra fiscal anterior à atual (issue #389)
 * — HTTP 409. Nada foi transmitido à SEFAZ; a nota voltou a um estado editável e o XML precisa ser
 * gerado de novo.
 */
public class RegraFiscalDesatualizadaException extends DomainException {

  /**
   * Cria a exceção.
   *
   * @param notaId        nota
   * @param versaoDoXml   versão gravada na nota (nula = anterior à marca)
   */
  public RegraFiscalDesatualizadaException(UUID notaId, Integer versaoDoXml) {
    super(HttpStatus.CONFLICT, mensagem(notaId), List.of(new ErrorDetail(
        ErrorCode.XML_REGRA_FISCAL_DESATUALIZADA.code(), "versaoRegraFiscal", mensagem(notaId),
        "Nada foi enviado à SEFAZ. A nota voltou para correção: revise os itens, envie para "
            + "revisão se houver contador e gere o XML de novo (POST /api/notas/{id}/gerar-xml).",
        versaoDoXml == null ? null : versaoDoXml.toString(),
        String.valueOf(VersaoRegraFiscal.ATUAL))));
  }

  private static String mensagem(UUID notaId) {
    return "O XML da nota " + notaId + " foi gerado com regras fiscais anteriores às atuais e "
        + "não pode ser enviado à SEFAZ.";
  }
}
