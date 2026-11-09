package br.com.agrocontabil.common;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Forma do slug do escritório, o rótulo de {@code {slug}.agroconia.com.br} (issue #464). Regra
 * única da validação do cadastro ({@code @SlugEscritorio}), do serviço e, depois, do endpoint
 * público, do "ask" do Caddy e do login no subdomínio.
 *
 * <p>Só ASCII minúsculo, dígito e hífen, de 3 a 32 caracteres, sem hífen nas pontas, sem {@code --}
 * (barra o {@code xn--} do punycode e os homógrafos de nome internacionalizado) e nunca só
 * dígitos. Os nomes reservados ficam no {@code SlugsReservados}: aqui é só a forma.
 */
public final class FormatoSlug {

  /** De 3 a 32 caracteres, começa e termina com letra ou dígito. Classes ASCII (sem Unicode). */
  private static final Pattern PADRAO = Pattern.compile("[a-z0-9][a-z0-9-]{1,30}[a-z0-9]");

  private static final Pattern SO_DIGITOS = Pattern.compile("[0-9]+");

  private FormatoSlug() {
  }

  /**
   * Forma canônica antes de validar e de gravar: sem espaços nas pontas e em minúsculas.
   *
   * @param slug valor informado; pode ser nulo
   * @return o valor normalizado, ou nulo
   */
  public static String normalizar(String slug) {
    return slug == null ? null : slug.strip().toLowerCase(Locale.ROOT);
  }

  /**
   * O valor, já normalizado, tem a forma de um slug?
   *
   * @param normalizado valor depois de {@link #normalizar(String)}
   * @return {@code true} se a forma vale
   */
  public static boolean valido(String normalizado) {
    return normalizado != null
        && PADRAO.matcher(normalizado).matches()
        && !normalizado.contains("--")
        && !SO_DIGITOS.matcher(normalizado).matches();
  }
}
