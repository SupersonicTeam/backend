package br.com.agrocontabil.security;

import br.com.agrocontabil.exception.NaoAutenticadoException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Fornece o {@link UsuarioAutenticado} da requisição atual — issue #93.
 *
 * <p>É a única porta de entrada para "quem está autenticado e em que papel". O
 * {@code AuthenticatedProdutorProvider} passou a ser um atalho sobre este componente, para os
 * serviços que só fazem sentido no contexto de um produtor.
 *
 * <p>Não distingue, para o cliente, "sem identidade" de "identidade que não resolve": os dois são
 * 401 (issue #92), para não confirmar quais ids existem.
 */
@Component
public class UsuarioAutenticadoProvider {

  /**
   * Retorna a identidade autenticada na requisição atual.
   *
   * @return usuário autenticado, com o papel
   * @throws NaoAutenticadoException se o contexto de segurança não carregar um
   *     {@link UsuarioAutenticado} — HTTP 401 pelo {@code GlobalExceptionHandler}
   */
  public UsuarioAutenticado get() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof UsuarioAutenticado usuario)) {
      throw new NaoAutenticadoException();
    }
    return usuario;
  }
}
