package br.com.agrocontabil.support;

import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.security.UsuarioAutenticado;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Monta o principal das requisições nos testes — issue #93.
 *
 * <p>Antes da #93 cada teste construía na mão um {@code UsernamePasswordAuthenticationToken} com o
 * {@link Produtor} como principal. Agora o principal é um {@link UsuarioAutenticado} e carrega a
 * authority do papel: replicar isso em quinze arquivos garantiria que o próximo ajuste na forma do
 * principal deixasse metade deles para trás — e um teste que autentica de forma diferente da
 * produção não prova nada sobre produção.
 */
public final class AutenticacaoTestSupport {

  private AutenticacaoTestSupport() {
  }

  /**
   * Token de um produtor autenticado, com a authority {@code ROLE_PRODUTOR}.
   *
   * @param produtor produtor a autenticar
   * @return token pronto para o {@code SecurityContextHolder}
   */
  public static Authentication tokenDe(Produtor produtor) {
    return tokenDe(UsuarioAutenticado.deProdutor(produtor));
  }

  /**
   * Token de um contador autenticado, com a authority {@code ROLE_CONTADOR}.
   *
   * @param contador contador a autenticar
   * @return token pronto para o {@code SecurityContextHolder}
   */
  public static Authentication tokenDe(Contador contador) {
    return tokenDe(UsuarioAutenticado.deContador(contador));
  }

  /**
   * Token de uma identidade já montada.
   *
   * @param usuario identidade autenticada
   * @return token com as authorities do papel
   */
  public static Authentication tokenDe(UsuarioAutenticado usuario) {
    return new UsernamePasswordAuthenticationToken(usuario, null, usuario.authorities());
  }

  /**
   * Coloca o produtor no contexto de segurança da thread atual.
   *
   * @param produtor produtor a autenticar
   */
  public static void autenticarComo(Produtor produtor) {
    SecurityContextHolder.getContext().setAuthentication(tokenDe(produtor));
  }

  /**
   * Coloca o contador no contexto de segurança da thread atual.
   *
   * @param contador contador a autenticar
   */
  public static void autenticarComo(Contador contador) {
    SecurityContextHolder.getContext().setAuthentication(tokenDe(contador));
  }

  /** Limpa o contexto de segurança — chamar no {@code @AfterEach} de quem autentica. */
  public static void limpar() {
    SecurityContextHolder.clearContext();
  }
}
