package br.com.agrocontabil.security;

import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Produtor;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Principal da requisição: quem está autenticado e em que papel — issue #93.
 *
 * <p>Antes desta entrega o principal <em>era</em> um {@link Produtor}, e o
 * {@code AuthenticatedProdutorProvider} fazia {@code instanceof Produtor}. Consequência: não havia
 * como um contador estar autenticado — o login de contador cairia direto no 401, e nenhum endpoint
 * conseguia distinguir papéis.
 *
 * <p>Exatamente um dos dois campos de identidade é preenchido, conforme o papel. Em vez de uma
 * hierarquia com dois subtipos, um record com duas fábricas: os consumidores perguntam pelo papel
 * ({@link #isContador()}) e pegam a identidade concreta pelo acessor correspondente, que é o que os
 * serviços precisam.
 *
 * @param papel     papel desta identidade
 * @param produtor  produtor autenticado, ou {@code null} quando o papel é CONTADOR
 * @param contador  contador autenticado, ou {@code null} quando o papel é PRODUTOR
 */
public record UsuarioAutenticado(PapelUsuario papel, Produtor produtor, Contador contador) {

  /**
   * Valida o invariante: o papel e a identidade preenchida precisam corresponder.
   *
   * <p>Um {@code UsuarioAutenticado} inconsistente (papel PRODUTOR sem produtor, por exemplo)
   * viraria {@code NullPointerException} em algum service lá na frente, longe da origem.
   */
  public UsuarioAutenticado {
    if (papel == null) {
      throw new IllegalArgumentException("Papel do usuario autenticado e obrigatorio.");
    }
    boolean ehProdutor = papel == PapelUsuario.PRODUTOR;
    if (ehProdutor == (produtor == null)) {
      throw new IllegalArgumentException(
          "Papel PRODUTOR exige produtor preenchido, e apenas ele.");
    }
    if (ehProdutor == (contador != null)) {
      throw new IllegalArgumentException(
          "Papel CONTADOR exige contador preenchido, e apenas ele.");
    }
  }

  /**
   * Cria o principal de um produtor autenticado.
   *
   * @param produtor produtor resolvido pela camada de identidade
   * @return principal com papel PRODUTOR
   */
  public static UsuarioAutenticado deProdutor(Produtor produtor) {
    return new UsuarioAutenticado(PapelUsuario.PRODUTOR, produtor, null);
  }

  /**
   * Cria o principal de um contador autenticado.
   *
   * @param contador contador resolvido pela camada de identidade
   * @return principal com papel CONTADOR
   */
  public static UsuarioAutenticado deContador(Contador contador) {
    return new UsuarioAutenticado(PapelUsuario.CONTADOR, null, contador);
  }

  /** @return {@code true} quando o papel é CONTADOR */
  public boolean isContador() {
    return papel == PapelUsuario.CONTADOR;
  }

  /**
   * Identificador da identidade autenticada — do produtor ou do contador, conforme o papel.
   *
   * @return id do produtor ou do contador
   */
  public UUID id() {
    return isContador() ? contador.getId() : produtor.getId();
  }

  /**
   * Nome de exibição da identidade autenticada.
   *
   * @return nome completo do produtor ou do contador
   */
  public String nome() {
    return isContador() ? contador.getNomeCompleto() : produtor.getNomeCompleto();
  }

  /**
   * Authorities do token do Spring Security: exatamente a do papel.
   *
   * @return lista com a authority {@code ROLE_PRODUTOR} ou {@code ROLE_CONTADOR}
   */
  public List<GrantedAuthority> authorities() {
    return List.of(new SimpleGrantedAuthority(papel.authority()));
  }
}
