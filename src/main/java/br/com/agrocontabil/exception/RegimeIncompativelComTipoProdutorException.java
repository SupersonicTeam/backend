package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.RegimeTributario;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Produtor pessoa física com regime {@code SIMPLES} ou {@code MEI} (issue #298) — HTTP 422
 * {@code REGIME_INCOMPATIVEL_COM_TIPO_PRODUTOR}.
 */
public class RegimeIncompativelComTipoProdutorException extends DomainException {

  /**
   * Cria a exceção.
   *
   * @param regime regime recusado
   */
  public RegimeIncompativelComTipoProdutorException(RegimeTributario regime) {
    super(HttpStatus.UNPROCESSABLE_ENTITY,
        "Produtor pessoa fisica nao pode ter o regime " + regime + ".",
        List.of(new ErrorDetail(ErrorCode.REGIME_INCOMPATIVEL_COM_TIPO_PRODUTOR.code(),
            "regimeTributario",
            "Produtor rural pessoa fisica nao se inscreve no Simples Nacional nem no MEI, que sao "
                + "de microempresa e empresa de pequeno porte (LC 123/2006, art. 3o).",
            "Use o regime normal (PRESUMIDO ou REAL), que sai como CRT 3 na NF-e.",
            regime.name(), "PRESUMIDO ou REAL")));
  }
}
