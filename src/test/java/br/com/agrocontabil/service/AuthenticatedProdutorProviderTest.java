package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.exception.NaoAutenticadoException;
import br.com.agrocontabil.exception.PapelNaoAutorizadoException;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.security.UsuarioAutenticado;
import br.com.agrocontabil.security.UsuarioAutenticadoProvider;
import br.com.agrocontabil.support.AutenticacaoTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Testes unitarios do {@link AuthenticatedProdutorProvider}.
 *
 * <p>Manipula o {@link SecurityContextHolder} diretamente para simular diferentes
 * estados do contexto de seguranca sem necessidade de contexto Spring.
 *
 * <p>Cenarios cobertos:
 * <ul>
 *   <li>Produtor valido no SecurityContext retorna o produtor corretamente</li>
 *   <li>Sem Authentication no SecurityContext lanca NaoAutenticadoException (401)</li>
 *   <li>Principal que nao e UsuarioAutenticado lanca NaoAutenticadoException (401)</li>
 *   <li>Contador autenticado lanca PapelNaoAutorizadoException (403) — issue #93</li>
 * </ul>
 */
class AuthenticatedProdutorProviderTest {

  private AuthenticatedProdutorProvider provider;

  @BeforeEach
  void setUp() {
    provider = new AuthenticatedProdutorProvider(new UsuarioAutenticadoProvider());
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  // -------------------------------------------------------------------------
  // Happy path — Produtor valido no SecurityContext
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("get deve retornar o Produtor quando ele e o principal da autenticacao")
  void getShouldReturnProdutorWhenPresentInSecurityContext() {
    Produtor produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("Murilo Produtor")
        .cpfCnpj("12345678901")
        .build();

    AutenticacaoTestSupport.autenticarComo(produtor);

    Produtor result = provider.get();

    assertThat(result).isSameAs(produtor);
    assertThat(result.getId()).isEqualTo(produtor.getId());
    assertThat(result.getNomeCompleto()).isEqualTo("Murilo Produtor");
  }

  // -------------------------------------------------------------------------
  // Sem autenticacao — NaoAutenticadoException (401)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("get deve lancar NaoAutenticadoException quando nao ha Authentication no contexto")
  void getShouldThrowNaoAutenticadoWhenNoAuthentication() {
    // SecurityContextHolder foi limpo no setUp — nao ha Authentication
    assertThatThrownBy(() -> provider.get())
        .isInstanceOf(NaoAutenticadoException.class)
        .hasMessageContaining("identidade de usuário");
  }

  @Test
  @DisplayName("get deve lancar NaoAutenticadoException quando Authentication e null explicitamente")
  void getShouldThrowWhenAuthenticationIsNull() {
    SecurityContextHolder.getContext().setAuthentication(null);

    assertThatThrownBy(() -> provider.get())
        .isInstanceOf(NaoAutenticadoException.class)
        .hasMessageContaining("identidade de usuário");
  }

  // -------------------------------------------------------------------------
  // Principal nao e Produtor — NaoAutenticadoException (401)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("get deve lancar NaoAutenticadoException quando principal nao e UsuarioAutenticado")
  void getShouldThrowWhenPrincipalIsNotProdutor() {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken("usuario-nao-produtor", null, java.util.List.of());
    SecurityContextHolder.getContext().setAuthentication(auth);

    assertThatThrownBy(() -> provider.get())
        .isInstanceOf(NaoAutenticadoException.class)
        .hasMessageContaining("identidade de usuário");
  }

  @Test
  @DisplayName("get deve lancar NaoAutenticadoException quando principal e um objeto arbitrario")
  void getShouldThrowWhenPrincipalIsArbitraryObject() {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
            new Object(), null, java.util.List.of());
    SecurityContextHolder.getContext().setAuthentication(auth);

    assertThatThrownBy(() -> provider.get())
        .isInstanceOf(NaoAutenticadoException.class)
        .hasMessageContaining("identidade de usuário");
  }

  // -------------------------------------------------------------------------
  // Contador autenticado — PapelNaoAutorizadoException (403), issue #93
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("get deve lancar 403 (e nao 401) quando quem esta autenticado e um contador")
  void getShouldThrowPapelNaoAutorizadoParaContador() {
    Contador contador =
        Contador.builder().id(UUID.randomUUID()).nomeCompleto("Escritorio Teste").build();
    AutenticacaoTestSupport.autenticarComo(contador);

    // 401 diria "sem identidade", o que e falso: o contador esta autenticado, so nao e produtor.
    assertThatThrownBy(() -> provider.get())
        .isInstanceOf(PapelNaoAutorizadoException.class)
        .hasMessageContaining("PRODUTOR");
  }

  @Test
  @DisplayName("get devolve o produtor quando o principal e um UsuarioAutenticado de papel PRODUTOR")
  void getShouldReturnProdutorDeUsuarioAutenticado() {
    Produtor produtor = Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Produtor").build();
    SecurityContextHolder.getContext()
        .setAuthentication(AutenticacaoTestSupport.tokenDe(UsuarioAutenticado.deProdutor(produtor)));

    assertThat(provider.get()).isSameAs(produtor);
  }
}
