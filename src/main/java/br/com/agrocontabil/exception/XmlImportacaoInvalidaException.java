package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lancada quando um XML submetido para importacao (issue #19 — UC002) nao pode ser
 * processado: nao e um XML bem-formado, nao contem um elemento {@code NFe} reconhecivel,
 * ou nao passa na validacao contra o XSD do layout 4.00. HTTP 422.
 *
 * <p>Semanticamente distinta de {@link XmlGenerationException} (que representa falha na
 * <em>geracao/emissao</em> do XML pelo proprio sistema): aqui o XML e uma <em>entrada</em>
 * externa e a falha e do arquivo enviado pelo usuario, nao do pipeline de emissao.
 *
 * <p><strong>LGPD:</strong> a mensagem e os {@link ErrorDetail} nunca devem conter CPF/CNPJ
 * completo do emitente ou do destinatario — quem constroi a excecao mascara antes.
 */
public class XmlImportacaoInvalidaException extends DomainException {

  /**
   * Constroi a excecao a partir de uma unica mensagem de detalhe (sem campo associado).
   *
   * @param detalhe motivo tecnico/amigavel da rejeicao (ja seguro para o cliente e logs)
   */
  public XmlImportacaoInvalidaException(String detalhe) {
    super(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "XML de nota fiscal invalido para importacao.",
        List.of(ErrorDetail.of(ErrorCode.XML_IMPORTACAO_INVALIDA, detalhe)));
  }

  /**
   * Constroi a excecao a partir de uma lista de detalhes ja formatados.
   *
   * @param errors lista de {@link ErrorDetail} (nunca contendo dados pessoais em claro)
   */
  public XmlImportacaoInvalidaException(List<ErrorDetail> errors) {
    super(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "XML de nota fiscal invalido para importacao.",
        errors);
  }
}
