package br.com.agrocontabil.security;

import br.com.agrocontabil.exception.NaoAutenticadoException;
import java.util.Optional;
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
    return tentar().orElseThrow(NaoAutenticadoException::new);
  }

  /**
   * Retorna a identidade autenticada, se houver, sem lançar.
   *
   * <p>Para quem roda <em>dentro e fora</em> de uma requisição — o auditor do JPA é o caso: num job
   * agendado não existe contexto de segurança, e ausência de identidade ali é normal, não erro.
   * Usar {@link #get()} nesse cenário transformaria o caminho feliz do job numa exceção.
   *
   * @return a identidade autenticada, ou vazio quando não há
   */
  public Optional<UsuarioAutenticado> tentar() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof UsuarioAutenticado usuario)) {
      return Optional.empty();
    }
    return Optional.of(usuario);
  }
}
