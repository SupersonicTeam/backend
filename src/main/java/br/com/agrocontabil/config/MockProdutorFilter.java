package br.com.agrocontabil.config;

import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ProdutorRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolve, a cada requisicao, qual {@link Produtor} esta "autenticado" (issue #91).
 *
 * <p><strong>Isto nao e autenticacao.</strong> O header {@code X-Usuario-Id} <em>declara</em> uma
 * identidade; nada a prova. E o mesmo nivel de confianca do mock de usuario unico que existia antes,
 * com a diferenca de permitir N usuarios — o suficiente para exercitar ponta a ponta o anti-IDOR que
 * os services ja aplicam. Autenticacao de verdade (JWT + RBAC) e a Sprint 8, issue #93.
 *
 * <p>Por isso o filtro so e registrado nos profiles {@code dev} e {@code homolog}. Em producao ele
 * nao existe: nenhuma requisicao carrega principal e o {@code AuthenticatedProdutorProvider} responde
 * 401.
 *
 * <p>Contrato:
 *
 * <ul>
 *   <li>header ausente ou em branco → produtor semente (o primeiro do {@code DataLoader}), para nao
 *       quebrar o frontend Angular nem o uso via Swagger/curl;
 *   <li>header com UUID de produtor existente → aquele produtor;
 *   <li>header malformado ou de produtor inexistente → <strong>nao autentica</strong> (e nao cai no
 *       fallback: mascarar o erro esconderia bug de integracao do cliente). O 401 nasce no
 *       {@code AuthenticatedProdutorProvider}, ja dentro do {@code GlobalExceptionHandler} — excecao
 *       lancada aqui no filtro nao passaria pelo {@code @RestControllerAdvice} e escaparia ao
 *       envelope de erro padrao;
 *   <li>contexto de seguranca ja preenchido → preservado (e o que permite testes de integracao
 *       injetarem o principal na mao).
 * </ul>
 */
@Component
@Profile({"dev", "homolog"})
public class MockProdutorFilter extends OncePerRequestFilter {

  /** Header que declara qual usuario esta fazendo a requisicao. Valor: UUID do produtor. */
  public static final String HEADER_USUARIO_ID = "X-Usuario-Id";

  private static final Logger log = LoggerFactory.getLogger(MockProdutorFilter.class);

  /**
   * CPF do produtor semente, usado quando a requisicao nao declara identidade.
   *
   * <p>Vem do {@link DataLoader} para que filtro e seed nao possam divergir.
   */
  private static final String CPF_SEMENTE = DataLoader.CPF_PRODUTOR_SEMENTE;

  private final ProdutorRepository produtorRepository;

  public MockProdutorFilter(ProdutorRepository produtorRepository) {
    this.produtorRepository = produtorRepository;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    if (SecurityContextHolder.getContext().getAuthentication() != null) {
      // Contexto montado por outro mecanismo (ex.: teste de integracao). Nao e nosso para limpar.
      filterChain.doFilter(request, response);
      return;
    }

    resolverProdutor(request.getHeader(HEADER_USUARIO_ID))
        .ifPresent(
            produtor ->
                SecurityContextHolder.getContext()
                    .setAuthentication(
                        new UsernamePasswordAuthenticationToken(produtor, null, List.of())));

    try {
      filterChain.doFilter(request, response);
    } finally {
      // O Tomcat reusa threads do pool: sem limpar, a identidade desta requisicao vazaria para a
      // proxima que caisse na mesma thread.
      SecurityContextHolder.clearContext();
    }
  }

  /**
   * Resolve o produtor declarado no header, ou o produtor semente quando nao ha header.
   *
   * @param usuarioId valor bruto do header {@code X-Usuario-Id}; pode ser nulo ou em branco
   * @return o produtor resolvido, ou vazio quando a identidade declarada nao existe
   */
  private Optional<Produtor> resolverProdutor(String usuarioId) {
    if (!StringUtils.hasText(usuarioId)) {
      Optional<Produtor> semente = produtorRepository.findByCpfCnpj(CPF_SEMENTE);
      if (semente.isEmpty()) {
        log.debug("Requisicao sem {} e sem produtor semente no banco.", HEADER_USUARIO_ID);
      }
      return semente;
    }

    UUID id;
    try {
      id = UUID.fromString(usuarioId.trim());
    } catch (IllegalArgumentException e) {
      log.debug("Header {} nao e um UUID valido.", HEADER_USUARIO_ID);
      return Optional.empty();
    }

    Optional<Produtor> produtor = produtorRepository.findById(id);
    if (produtor.isEmpty()) {
      log.debug("Header {} referencia produtor inexistente.", HEADER_USUARIO_ID);
    }
    return produtor;
  }
}
