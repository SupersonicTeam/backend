package br.com.agrocontabil.security;

/**
 * Papel do usuário autenticado — issue #93.
 *
 * <p>Define o que cada identidade pode fazer na API. Até esta entrega o token era criado sem
 * nenhuma authority ({@code List.of()}), de modo que o {@code SecurityConfig} não tinha como
 * escrever regra por papel e todo endpoint ficava em {@code permitAll()}.
 *
 * <ul>
 *   <li>{@link #PRODUTOR} — o produtor rural, dono dos próprios dados fiscais;
 *   <li>{@link #CONTADOR} — o escritório de contabilidade, que atua sobre os dados dos produtores
 *       da sua carteira (e só deles: o vínculo {@code contador_produtor} é a autorização).
 * </ul>
 *
 * <p>O prefixo {@code ROLE_} é exigido pelo Spring Security: {@code hasRole("CONTADOR")} procura a
 * authority {@code ROLE_CONTADOR}. Guardar a authority completa aqui evita que o prefixo seja
 * escrito à mão (e esquecido) em cada ponto de configuração.
 */
public enum PapelUsuario {
  PRODUTOR,
  CONTADOR;

  /** Prefixo que o Spring Security assume nas authorities de papel. */
  public static final String PREFIXO_ROLE = "ROLE_";

  /**
   * Authority correspondente a este papel, com o prefixo que o Spring Security espera.
   *
   * @return {@code ROLE_PRODUTOR} ou {@code ROLE_CONTADOR}
   */
  public String authority() {
    return PREFIXO_ROLE + name();
  }
}
