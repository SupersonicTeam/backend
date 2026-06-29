package br.com.agrocontabil.service;

import br.com.agrocontabil.model.Producer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Fornece o {@link Producer} autenticado a partir do {@link SecurityContextHolder}.
 *
 * <p>O mecanismo de autenticacao atual (Sprint 2) e o {@code MockProducerFilter},
 * que coloca o {@link Producer} como principal de um
 * {@code UsernamePasswordAuthenticationToken} no contexto de seguranca.
 *
 * <p>Quando a Sprint 8 implementar JWT real, basta ajustar este componente para
 * extrair o {@link Producer} do token JWT — os services que dependem deste provider
 * nao precisarao ser alterados.
 *
 * <p>Em testes unitarios de service (Mockito puro), este componente e mockado,
 * evitando dependencia do contexto Spring Security.
 */
@Component
public class AuthenticatedProducerProvider {

  /**
   * Retorna o producer autenticado na requisicao atual.
   *
   * @return producer autenticado
   * @throws IllegalStateException se nao houver autenticacao valida no contexto
   */
  public Producer get() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof Producer producer)) {
      throw new IllegalStateException(
          "Nenhum produtor autenticado encontrado no contexto de seguranca. "
              + "Verifique se o filtro de autenticacao esta ativo para este perfil.");
    }
    return producer;
  }
}
