package br.com.agrocontabil.service;

import br.com.agrocontabil.exception.NaoAutenticadoException;
import br.com.agrocontabil.model.Produtor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Fornece o {@link Produtor} autenticado a partir do {@link SecurityContextHolder}.
 *
 * <p>O mecanismo de identidade atual e o {@code MockProdutorFilter}, ativo apenas nos profiles
 * {@code dev} e {@code homolog}, que resolve o {@link Produtor} declarado no header
 * {@code X-Usuario-Id} e o coloca como principal de um
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
   * @throws NaoAutenticadoException se nao houver identidade valida no contexto — HTTP 401 pelo
   *     {@code GlobalExceptionHandler}, que ja trata {@code DomainException}
   */
  public Produtor get() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof Produtor produtor)) {
      // 401, nao 500: a ausencia de identidade e erro do cliente (ou profile sem filtro de
      // identidade), nao falha interna do servidor.
      throw new NaoAutenticadoException();
    }
    return produtor;
  }
}
