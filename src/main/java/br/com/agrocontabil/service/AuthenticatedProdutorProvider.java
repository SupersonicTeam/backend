package br.com.agrocontabil.service;

import br.com.agrocontabil.model.Produtor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Fornece o {@link Produtor} autenticado a partir do {@link SecurityContextHolder}.
 *
 * <p>O mecanismo de autenticacao atual (Sprint 2) e o {@code MockProdutorFilter},
 * que coloca o {@link Produtor} como principal de um
 * {@code UsernamePasswordAuthenticationToken} no contexto de seguranca.
 *
 * <p>Quando a Sprint 8 implementar JWT real, basta ajustar este componente para
 * extrair o {@link Produtor} do token JWT — os services que dependem deste provider
 * nao precisarao ser alterados.
 *
 * <p>Em testes unitarios de service (Mockito puro), este componente e mockado,
 * evitando dependencia do contexto Spring Security.
 */
@Component
public class AuthenticatedProdutorProvider {

  /**
   * Retorna o produtor autenticado na requisicao atual.
   *
   * @return produtor autenticado
   * @throws IllegalStateException se nao houver autenticacao valida no contexto
   */
  public Produtor get() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof Produtor produtor)) {
      throw new IllegalStateException(
          "Nenhum produtor autenticado encontrado no contexto de seguranca. "
              + "Verifique se o filtro de autenticacao esta ativo para este perfil.");
    }
    return produtor;
  }
}
