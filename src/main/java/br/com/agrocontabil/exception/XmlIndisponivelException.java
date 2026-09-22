package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lançada ao solicitar o XML de uma nota que existe e pertence ao produtor, mas que ainda não
 * teve o XML gerado (em RASCUNHO ou COM_INCONSISTENCIAS). HTTP 409 Conflict.
 *
 * <p>É o par de {@link DanfeIndisponivelException}: em ambos os casos a nota existe e o que falta
 * é o recurso derivado dela. O 404 fica reservado para nota inexistente ou de outro produtor
 * (anti-IDOR), de modo que o cliente consegue distinguir "não é sua / não existe" de
 * "ainda não gerado" (issue #89).
 */
public class XmlIndisponivelException extends DomainException {

  /**
   * Cria a exceção descrevendo a nota e o status em que o XML ainda não existe.
   *
   * @param id     identificador da nota fiscal
   * @param status status atual da nota
   */
  public XmlIndisponivelException(UUID id, StatusNotaFiscal status) {
    super(
        HttpStatus.CONFLICT,
        "XML da NF-e indisponivel para a nota '%s' no status '%s'. "
            .formatted(id, status)
            + "Gere o XML da nota antes de baixa-lo.",
        List.of(ErrorDetail.of(ErrorCode.XML_INDISPONIVEL,
            "Gere o XML da nota (POST /api/notas/{id}/gerar-xml) antes de baixa-lo.")));
  }
}
