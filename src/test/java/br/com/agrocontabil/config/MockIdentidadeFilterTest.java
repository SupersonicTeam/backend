package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.security.UsuarioAutenticado;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Testes do resolvedor de identidade por requisicao (issues #91 e #93).
 *
 * <p>Cada cenario verifica o principal que o filtro deixou no {@code SecurityContext}
 * <strong>durante</strong> a cadeia — o filtro limpa o contexto ao terminar (RF06), entao inspecionar
 * depois da chamada nao diria nada. Por isso o {@link FilterChain} mockado captura o principal.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MockIdentidadeFilter — identidade por requisicao (X-Usuario-Id)")
class MockIdentidadeFilterTest {

  private static final String CPF_SEMENTE = DataLoader.CPF_PRODUTOR_SEMENTE;

  @Mock private ProdutorRepository produtorRepository;
  @Mock private ContadorRepository contadorRepository;

  @InjectMocks private MockIdentidadeFilter filter;

  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  @AfterEach
  void limparContexto() {
    SecurityContextHolder.clearContext();
  }

  /** Cadeia que captura o principal autenticado no momento em que a requisicao seria processada. */
  private static final class CapturingChain implements FilterChain {
    private Object principal;
    private java.util.Collection<String> authorities;
    private boolean chamada;

    @Override
    public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
      chamada = true;
      var auth = SecurityContextHolder.getContext().getAuthentication();
      principal = auth == null ? null : auth.getPrincipal();
      authorities =
          auth == null
              ? null
              : auth.getAuthorities().stream()
                  .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                  .toList();
    }
  }

  private static Contador contador() {
    return Contador.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("Escritorio Teste")
        .cpfCnpj("12.345.678/0001-95")
        .build();
  }

  /** Produtor que o filtro deixou no contexto, ja desembrulhado do principal. */
  private static Produtor produtorDe(CapturingChain chain) {
    assertThat(chain.principal).isInstanceOf(UsuarioAutenticado.class);
    return ((UsuarioAutenticado) chain.principal).produtor();
  }

  private static Produtor produtor(String cpf) {
    return Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Produtor " + cpf).cpfCnpj(cpf)
        .build();
  }

  @Test
  @DisplayName("sem header usa o produtor semente (compatibilidade com o Angular atual)")
  void semHeaderUsaSemente() throws Exception {
    Produtor semente = produtor(CPF_SEMENTE);
    when(produtorRepository.findByCpfCnpj(CPF_SEMENTE)).thenReturn(Optional.of(semente));
    CapturingChain chain = new CapturingChain();

    filter.doFilter(request, response, chain);

    assertThat(produtorDe(chain)).isSameAs(semente);
    assertThat(chain.authorities).containsExactly(PapelUsuario.PRODUTOR.authority());
  }

  @Test
  @DisplayName("header com UUID existente autentica aquele produtor, nao o semente")
  void headerResolveProdutorInformado() throws Exception {
    Produtor outro = produtor("987.654.321-00");
    when(produtorRepository.findById(outro.getId())).thenReturn(Optional.of(outro));
    request.addHeader(MockIdentidadeFilter.HEADER_USUARIO_ID, outro.getId().toString());
    CapturingChain chain = new CapturingChain();

    filter.doFilter(request, response, chain);

    assertThat(produtorDe(chain)).isSameAs(outro);
    verify(produtorRepository, never()).findByCpfCnpj(any());
    verify(contadorRepository, never()).findById(any());
  }

  @Test
  @DisplayName("header malformado nao autentica e nao cai no fallback do semente")
  void headerMalformadoNaoAutentica() throws Exception {
    request.addHeader(MockIdentidadeFilter.HEADER_USUARIO_ID, "nao-e-uuid");
    CapturingChain chain = new CapturingChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.principal).isNull();
    assertThat(chain.chamada).isTrue();
    verify(produtorRepository, never()).findByCpfCnpj(any());
  }

  @Test
  @DisplayName("header com UUID inexistente nao autentica e nao cai no fallback do semente")
  void headerInexistenteNaoAutentica() throws Exception {
    UUID desconhecido = UUID.randomUUID();
    when(produtorRepository.findById(desconhecido)).thenReturn(Optional.empty());
    when(contadorRepository.findById(desconhecido)).thenReturn(Optional.empty());
    request.addHeader(MockIdentidadeFilter.HEADER_USUARIO_ID, desconhecido.toString());
    CapturingChain chain = new CapturingChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.principal).isNull();
    assertThat(chain.chamada).isTrue();
    verify(produtorRepository, never()).findByCpfCnpj(any());
  }

  @Test
  @DisplayName("sem header e sem produtor semente no banco, segue a cadeia sem autenticar")
  void semSementeNaoAutentica() throws Exception {
    when(produtorRepository.findByCpfCnpj(CPF_SEMENTE)).thenReturn(Optional.empty());
    CapturingChain chain = new CapturingChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.principal).isNull();
    assertThat(chain.chamada).isTrue();
  }

  @Test
  @DisplayName("preserva autenticacao ja presente no contexto (testes de integracao)")
  void preservaAutenticacaoExistente() throws Exception {
    Produtor jaAutenticado = produtor("111.222.333-44");
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(jaAutenticado, null, List.of()));
    request.addHeader(MockIdentidadeFilter.HEADER_USUARIO_ID, UUID.randomUUID().toString());
    CapturingChain chain = new CapturingChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.principal).isSameAs(jaAutenticado);
    verify(produtorRepository, never()).findById(any());
    verify(produtorRepository, never()).findByCpfCnpj(any());
  }

  @Test
  @DisplayName("limpa o contexto ao final: a identidade nao vaza para a proxima requisicao da thread")
  void limpaContextoAoFinal() throws Exception {
    Produtor semente = produtor(CPF_SEMENTE);
    when(produtorRepository.findByCpfCnpj(CPF_SEMENTE)).thenReturn(Optional.of(semente));

    filter.doFilter(request, response, new CapturingChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  @DisplayName("nao limpa contexto que nao foi este filtro que montou")
  void naoLimpaContextoDeTerceiros() throws Exception {
    Produtor jaAutenticado = produtor("111.222.333-44");
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(jaAutenticado, null, List.of()));

    filter.doFilter(request, response, new CapturingChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
  }

  @Test
  @DisplayName("header com UUID de contador autentica com papel CONTADOR (#93)")
  void headerResolveContador() throws Exception {
    Contador contador = contador();
    when(produtorRepository.findById(contador.getId())).thenReturn(Optional.empty());
    when(contadorRepository.findById(contador.getId())).thenReturn(Optional.of(contador));
    request.addHeader(MockIdentidadeFilter.HEADER_USUARIO_ID, contador.getId().toString());
    CapturingChain chain = new CapturingChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.principal).isInstanceOf(UsuarioAutenticado.class);
    UsuarioAutenticado usuario = (UsuarioAutenticado) chain.principal;
    assertThat(usuario.papel()).isEqualTo(PapelUsuario.CONTADOR);
    assertThat(usuario.contador()).isSameAs(contador);
    assertThat(chain.authorities).containsExactly(PapelUsuario.CONTADOR.authority());
  }

  @Test
  @DisplayName("sem header o fallback e sempre o produtor semente, nunca um contador (#93)")
  void fallbackNuncaEhContador() throws Exception {
    Produtor semente = produtor(CPF_SEMENTE);
    when(produtorRepository.findByCpfCnpj(CPF_SEMENTE)).thenReturn(Optional.of(semente));
    CapturingChain chain = new CapturingChain();

    filter.doFilter(request, response, chain);

    // Cair no papel de contador por omissao trocaria silenciosamente as permissoes da requisicao.
    assertThat(((UsuarioAutenticado) chain.principal).papel()).isEqualTo(PapelUsuario.PRODUTOR);
    verify(contadorRepository, never()).findById(any());
  }
}
