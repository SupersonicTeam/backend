package br.com.agrocontabil.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Produtor;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

/**
 * Testes do principal da requisição e do invariante papel ↔ identidade (issue #93).
 *
 * <p>O invariante existe porque um {@code UsuarioAutenticado} inconsistente só falharia lá na
 * frente, dentro de um service, com {@code NullPointerException} longe da origem.
 */
@DisplayName("UsuarioAutenticado — principal com papel (#93)")
class UsuarioAutenticadoTest {

  private static Produtor produtor() {
    return Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Joao Produtor").build();
  }

  private static Contador contador() {
    return Contador.builder().id(UUID.randomUUID()).nomeCompleto("Escritorio Campo Certo").build();
  }

  @Test
  @DisplayName("deProdutor expoe papel, id, nome e authority do produtor")
  void deProdutor() {
    Produtor produtor = produtor();

    UsuarioAutenticado usuario = UsuarioAutenticado.deProdutor(produtor);

    assertThat(usuario.papel()).isEqualTo(PapelUsuario.PRODUTOR);
    assertThat(usuario.isContador()).isFalse();
    assertThat(usuario.produtor()).isSameAs(produtor);
    assertThat(usuario.contador()).isNull();
    assertThat(usuario.id()).isEqualTo(produtor.getId());
    assertThat(usuario.nome()).isEqualTo("Joao Produtor");
    assertThat(usuario.authorities()).extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_PRODUTOR");
  }

  @Test
  @DisplayName("deContador expoe papel, id, nome e authority do contador")
  void deContador() {
    Contador contador = contador();

    UsuarioAutenticado usuario = UsuarioAutenticado.deContador(contador);

    assertThat(usuario.papel()).isEqualTo(PapelUsuario.CONTADOR);
    assertThat(usuario.isContador()).isTrue();
    assertThat(usuario.contador()).isSameAs(contador);
    assertThat(usuario.produtor()).isNull();
    assertThat(usuario.id()).isEqualTo(contador.getId());
    assertThat(usuario.nome()).isEqualTo("Escritorio Campo Certo");
    assertThat(usuario.authorities()).extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_CONTADOR");
  }

  @Test
  @DisplayName("papel PRODUTOR sem produtor e rejeitado na construcao")
  void produtorObrigatorioParaPapelProdutor() {
    assertThatThrownBy(() -> new UsuarioAutenticado(PapelUsuario.PRODUTOR, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PRODUTOR");
  }

  @Test
  @DisplayName("papel CONTADOR sem contador e rejeitado na construcao")
  void contadorObrigatorioParaPapelContador() {
    assertThatThrownBy(() -> new UsuarioAutenticado(PapelUsuario.CONTADOR, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CONTADOR");
  }

  @Test
  @DisplayName("as duas identidades preenchidas ao mesmo tempo sao rejeitadas")
  void naoAceitaAsDuasIdentidades() {
    assertThatThrownBy(
            () -> new UsuarioAutenticado(PapelUsuario.PRODUTOR, produtor(), contador()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new UsuarioAutenticado(PapelUsuario.CONTADOR, produtor(), contador()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("papel nulo e rejeitado")
  void papelObrigatorio() {
    assertThatThrownBy(() -> new UsuarioAutenticado(null, produtor(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Papel");
  }

  @Test
  @DisplayName("authority carrega o prefixo ROLE_ que o Spring Security espera")
  void authorityTemPrefixoRole() {
    assertThat(PapelUsuario.PRODUTOR.authority()).isEqualTo("ROLE_PRODUTOR");
    assertThat(PapelUsuario.CONTADOR.authority()).isEqualTo("ROLE_CONTADOR");
  }
}
