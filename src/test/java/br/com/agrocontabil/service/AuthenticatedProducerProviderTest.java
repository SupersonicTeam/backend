package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.model.Producer;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Testes unitarios do {@link AuthenticatedProducerProvider}.
 *
 * <p>Manipula o {@link SecurityContextHolder} diretamente para simular diferentes
 * estados do contexto de seguranca sem necessidade de contexto Spring.
 *
 * <p>Cenarios cobertos:
 * <ul>
 *   <li>Producer valido no SecurityContext retorna o producer corretamente</li>
 *   <li>Sem Authentication no SecurityContext lanca IllegalStateException</li>
 *   <li>Principal diferente de Producer lanca IllegalStateException</li>
 * </ul>
 */
class AuthenticatedProducerProviderTest {

  private AuthenticatedProducerProvider provider;

  @BeforeEach
  void setUp() {
    provider = new AuthenticatedProducerProvider();
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  // -------------------------------------------------------------------------
  // Happy path — Producer valido no SecurityContext
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("get deve retornar o Produtor quando ele e o principal da autenticacao")
  void getShouldReturnProdutorWhenPresentInSecurityContext() {
    Producer producer = Producer.builder()
        .id(UUID.randomUUID())
        .fullName("Murilo Produtor")
        .taxId("12345678901")
        .build();

    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(producer, null, java.util.List.of());
    SecurityContextHolder.getContext().setAuthentication(auth);

    Producer result = provider.get();

    assertThat(result).isSameAs(producer);
    assertThat(result.getId()).isEqualTo(producer.getId());
    assertThat(result.getFullName()).isEqualTo("Murilo Produtor");
  }

  // -------------------------------------------------------------------------
  // Sem autenticacao — IllegalStateException
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("get deve lancar IllegalStateException quando nao ha Authentication no contexto")
  void getShouldThrowIllegalStateExceptionWhenNoAuthentication() {
    // SecurityContextHolder foi limpo no setUp — nao ha Authentication
    assertThatThrownBy(() -> provider.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Nenhum produtor autenticado");
  }

  @Test
  @DisplayName("get deve lancar IllegalStateException quando Authentication e null explicitamente")
  void getShouldThrowWhenAuthenticationIsNull() {
    SecurityContextHolder.getContext().setAuthentication(null);

    assertThatThrownBy(() -> provider.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Nenhum produtor autenticado");
  }

  // -------------------------------------------------------------------------
  // Principal nao e Producer — IllegalStateException
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("get deve lancar IllegalStateException quando principal nao e Produtor (String)")
  void getShouldThrowWhenPrincipalIsNotProdutor() {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken("usuario-nao-produtor", null, java.util.List.of());
    SecurityContextHolder.getContext().setAuthentication(auth);

    assertThatThrownBy(() -> provider.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Nenhum produtor autenticado");
  }

  @Test
  @DisplayName("get deve lancar IllegalStateException quando principal e um objeto arbitrario")
  void getShouldThrowWhenPrincipalIsArbitraryObject() {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
            new Object(), null, java.util.List.of());
    SecurityContextHolder.getContext().setAuthentication(auth);

    assertThatThrownBy(() -> provider.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Nenhum produtor autenticado");
  }
}
