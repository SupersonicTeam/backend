package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando a requisição não carrega uma identidade de usuário válida. HTTP 401.
 *
 * <p>Cobre dois casos, deliberadamente indistinguíveis para o cliente (issue #92):
 *
 * <ul>
 *   <li>nenhum mecanismo de identidade está ativo — é o que acontece fora dos profiles
 *       {@code dev}/{@code homolog}, onde o {@code MockIdentidadeFilter} não é registrado. Antes
 *       disso a API respondia <strong>500</strong> em todo endpoint autenticado, porque a
 *       {@code IllegalStateException} do provider caía na rede de segurança do handler genérico;
 *   <li>a identidade declarada no header {@code X-Usuario-Id} não resolve (UUID malformado ou
 *       produtor inexistente). Responder 401 — e não 404 — evita confirmar quais ids existem.
 * </ul>
 *
 * <p>A mensagem não repete o valor recebido no header: refletir entrada arbitrária na resposta não
 * ajuda o cliente legítimo e serve ao ilegítimo.
 */
public class NaoAutenticadoException extends DomainException {

  private static final String MENSAGEM = "Requisicao sem identidade de usuario valida.";

  private static final String SUGESTAO =
      "Envie o header X-Usuario-Id com o identificador do usuario.";

  public NaoAutenticadoException() {
    super(
        HttpStatus.UNAUTHORIZED,
        MENSAGEM,
        List.of(
            new ErrorDetail(
                ErrorCode.NAO_AUTENTICADO.code(), null, MENSAGEM, SUGESTAO, null, null)));
  }
}
