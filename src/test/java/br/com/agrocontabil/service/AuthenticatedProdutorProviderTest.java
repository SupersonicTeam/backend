package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.exception.NaoAutenticadoException;
import br.com.agrocontabil.model.Produtor;
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
 *   <li>Principal diferente de Produtor lanca NaoAutenticadoException (401)</li>
 * </ul>
 */
class AuthenticatedProdutorProviderTest {

  private AuthenticatedProdutorProvider provider;

  @BeforeEach
  void setUp() {
    provider = new AuthenticatedProdutorProvider();
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

    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(produtor, null, java.util.List.of());
    SecurityContextHolder.getContext().setAuthentication(auth);

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
        .hasMessageContaining("identidade de usuario");
  }

  @Test
  @DisplayName("get deve lancar NaoAutenticadoException quando Authentication e null explicitamente")
  void getShouldThrowWhenAuthenticationIsNull() {
    SecurityContextHolder.getContext().setAuthentication(null);

    assertThatThrownBy(() -> provider.get())
        .isInstanceOf(NaoAutenticadoException.class)
        .hasMessageContaining("identidade de usuario");
  }

  // -------------------------------------------------------------------------
  // Principal nao e Produtor — NaoAutenticadoException (401)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("get deve lancar NaoAutenticadoException quando principal nao e Produtor (String)")
  void getShouldThrowWhenPrincipalIsNotProdutor() {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken("usuario-nao-produtor", null, java.util.List.of());
    SecurityContextHolder.getContext().setAuthentication(auth);

    assertThatThrownBy(() -> provider.get())
        .isInstanceOf(NaoAutenticadoException.class)
        .hasMessageContaining("identidade de usuario");
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
        .hasMessageContaining("identidade de usuario");
  }
}
