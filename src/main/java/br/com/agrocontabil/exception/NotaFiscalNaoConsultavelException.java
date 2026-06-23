package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando se tenta consultar o status na SEFAZ de uma nota que ainda não foi
 * transmitida (sem chave de acesso / em RASCUNHO). HTTP 409 Conflict.
 *
 * <p>Só faz sentido consultar o status de notas que já possuem chave de acesso e foram
 * enviadas ao fisco (XML_GERADO em diante).
 */
public class NotaFiscalNaoConsultavelException extends DomainException {

  /**
   * Cria a exceção descrevendo a nota e o status inválido para consulta.
   *
   * @param id     identificador da nota fiscal
   * @param status status atual da nota
   */
  public NotaFiscalNaoConsultavelException(UUID id, StatusNotaFiscal status) {
    super(
        HttpStatus.CONFLICT,
        "Nota fiscal '%s' nao pode ter o status consultado na SEFAZ no status '%s'. "
            .formatted(id, status)
            + "Apenas notas ja transmitidas (com chave de acesso) podem ser consultadas.",
        List.of(ErrorDetail.of(ErrorCode.NOTA_NAO_CONSULTAVEL,
            "Gere o XML e envie a nota a SEFAZ antes de consultar o status.")));
  }
}
