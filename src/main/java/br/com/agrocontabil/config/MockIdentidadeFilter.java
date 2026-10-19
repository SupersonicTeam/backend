package br.com.agrocontabil.config;

import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.UsuarioAutenticado;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolve, a cada requisicao, qual identidade esta "autenticada" — produtor ou contador
 * (issues #91 e #93).
 *
 * <p><strong>Isto nao e autenticacao.</strong> O header {@code X-Usuario-Id} <em>declara</em> uma
 * identidade; nada a prova. E o mesmo nivel de confianca do mock de usuario unico que existia antes,
 * com a diferenca de permitir N usuarios — o suficiente para exercitar ponta a ponta o anti-IDOR e,
 * desde a #93, o escopo por papel. Autenticacao de verdade (login + JWT) e issue propria.
 *
 * <p>Por isso o filtro so e registrado nos profiles {@code dev} e {@code homolog}. Em producao ele
 * nao existe: nenhuma requisicao carrega principal e o {@code UsuarioAutenticadoProvider} responde
 * 401.
 *
 * <p>Contrato:
 *
 * <ul>
 *   <li>header ausente ou em branco → produtor semente (o primeiro do {@link DataLoader}), para nao
 *       quebrar o frontend Angular nem o uso via Swagger/curl. O fallback e sempre <em>produtor</em>:
 *       cair no papel de contador por omissao trocaria silenciosamente o conjunto de permissoes da
 *       requisicao;
 *   <li>header com UUID de produtor existente → aquele produtor, papel {@code PRODUTOR};
 *   <li>header com UUID de contador existente → aquele contador, papel {@code CONTADOR}. Produtor e
 *       contador vivem em tabelas diferentes, com UUIDs distintos: a busca tenta produtor primeiro e
 *       so entao contador, sem ambiguidade possivel;
 *   <li>header malformado ou id que nao existe em nenhuma das duas tabelas → <strong>nao
 *       autentica</strong> (e nao cai no fallback: mascarar o erro esconderia bug de integracao do
 *       cliente). O 401 nasce no provider, ja dentro do {@code GlobalExceptionHandler} — excecao
 *       lancada aqui no filtro nao passaria pelo {@code @RestControllerAdvice} e escaparia ao
 *       envelope de erro padrao;
 *   <li>header {@code Authorization} presente (qualquer esquema, valido ou nao) → o mock nao age.
 *       Desde a #206 o {@code JwtAutenticacaoFilter} roda antes: Bearer valido ja autenticou,
 *       Bearer invalido ja respondeu 401; um {@code Authorization} que sobra aqui (vazio, Basic) nao
 *       pode virar o produtor semente;
 *   <li>contexto de seguranca ja preenchido → preservado (e o que permite testes de integracao
 *       injetarem o principal na mao).
 * </ul>
 */
@Component
@Profile({"dev", "homolog"})
public class MockIdentidadeFilter extends OncePerRequestFilter {

  /** Header que declara qual usuario esta fazendo a requisicao. Valor: UUID do produtor ou contador. */
  public static final String HEADER_USUARIO_ID = "X-Usuario-Id";

  private static final Logger log = LoggerFactory.getLogger(MockIdentidadeFilter.class);

  /**
   * CPF do produtor semente, usado quando a requisicao nao declara identidade.
   *
   * <p>Vem do {@link DataLoader} para que filtro e seed nao possam divergir.
   */
  private static final String CPF_SEMENTE = DataLoader.CPF_PRODUTOR_SEMENTE;

  private final ProdutorRepository produtorRepository;
  private final ContadorRepository contadorRepository;

  public MockIdentidadeFilter(
      ProdutorRepository produtorRepository, ContadorRepository contadorRepository) {
    this.produtorRepository = produtorRepository;
    this.contadorRepository = contadorRepository;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    if (SecurityContextHolder.getContext().getAuthentication() != null) {
      // Contexto montado por outro mecanismo (JWT, teste de integracao). Nao e nosso para limpar.
      filterChain.doFilter(request, response);
      return;
    }
    if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
      // Quem manda Authorization quer autenticacao de verdade (#206): um Bearer vazio ou outro
      // esquema nao pode cair no produtor semente. Segue sem identidade e a cadeia responde 401.
      filterChain.doFilter(request, response);
      return;
    }

    resolverUsuario(request.getHeader(HEADER_USUARIO_ID))
        .ifPresent(
            usuario ->
                SecurityContextHolder.getContext()
                    .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                            usuario, null, usuario.authorities())));

    try {
      filterChain.doFilter(request, response);
    } finally {
      // O Tomcat reusa threads do pool: sem limpar, a identidade desta requisicao vazaria para a
      // proxima que caisse na mesma thread.
      SecurityContextHolder.clearContext();
    }
  }

  /**
   * Resolve a identidade declarada no header, ou o produtor semente quando nao ha header.
   *
   * @param usuarioId valor bruto do header {@code X-Usuario-Id}; pode ser nulo ou em branco
   * @return a identidade resolvida, ou vazio quando a identidade declarada nao existe
   */
  private Optional<UsuarioAutenticado> resolverUsuario(String usuarioId) {
    if (!StringUtils.hasText(usuarioId)) {
      Optional<Produtor> semente = produtorRepository.findByCpfCnpj(CPF_SEMENTE);
      if (semente.isEmpty()) {
        log.debug("Requisicao sem {} e sem produtor semente no banco.", HEADER_USUARIO_ID);
      }
      return semente.map(UsuarioAutenticado::deProdutor);
    }

    UUID id;
    try {
      id = UUID.fromString(usuarioId.trim());
    } catch (IllegalArgumentException e) {
      log.debug("Header {} nao e um UUID valido.", HEADER_USUARIO_ID);
      return Optional.empty();
    }

    Optional<UsuarioAutenticado> produtor =
        produtorRepository.findById(id).map(UsuarioAutenticado::deProdutor);
    if (produtor.isPresent()) {
      return produtor;
    }

    Optional<Contador> contador = contadorRepository.findById(id);
    if (contador.isEmpty()) {
      log.debug("Header {} nao referencia produtor nem contador existente.", HEADER_USUARIO_ID);
    }
    return contador.map(UsuarioAutenticado::deContador);
  }
}
