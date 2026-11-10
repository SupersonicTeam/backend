package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lancada quando as pre-condicoes de emissao de uma NFA (Nota Fiscal Avulsa do produtor rural)
 * nao sao atendidas — issue #18. HTTP 422.
 *
 * <p>Casos cobertos:
 * <ul>
 *   <li>Produtor emitente nao e pessoa fisica ({@link ErrorCode#NFA_PRODUTOR_NAO_PF}).</li>
 *   <li>CPF do emitente ausente ou com digito verificador invalido
 *       ({@link ErrorCode#DOCUMENTO_INVALIDO}).</li>
 * </ul>
 *
 * <p><strong>LGPD:</strong> a mensagem exposta ao cliente nunca contem o CPF/CNPJ em claro —
 * o documento so aparece mascarado no log do servico.
 */
public class NfaInvalidaParaEmissaoException extends DomainException {

  private NfaInvalidaParaEmissaoException(ErrorDetail detail) {
    super(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "Nota fiscal avulsa (NFA) inválida para emissão. Corrija os dados do emitente.",
        List.of(detail));
  }

  /** NFA exige produtor pessoa fisica. */
  public static NfaInvalidaParaEmissaoException produtorNaoPessoaFisica() {
    return new NfaInvalidaParaEmissaoException(
        ErrorDetail.of(
            ErrorCode.NFA_PRODUTOR_NAO_PF,
            "produtor.tipoProdutor",
            "A NFA (Nota Fiscal Avulsa) só pode ser emitida por produtor pessoa física."));
  }

  /** CPF do emitente ausente ou com digito verificador invalido. */
  public static NfaInvalidaParaEmissaoException cpfEmitenteInvalido() {
    return new NfaInvalidaParaEmissaoException(
        ErrorDetail.of(
            ErrorCode.DOCUMENTO_INVALIDO,
            "produtor.cpfCnpj",
            "O CPF do emitente é obrigatório e deve ter dígito verificador válido para emitir NFA."));
  }
}
