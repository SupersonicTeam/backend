package br.com.agrocontabil.assistente.rag;

import java.net.URI;
import java.time.LocalDate;
import java.util.Objects;

/**
 * De onde veio o texto de uma norma (issue #156): nome, link oficial, data de captura e início da
 * vigência da redação ingerida.
 *
 * <p>A URL precisa ser do Planalto: é o link que a Ceres mostra como fonte oficial ao usuário, e a
 * citação só vale se apontar para o texto oficial.
 *
 * @param norma          ex.: {@code LC 214/2025}
 * @param url            texto compilado no Planalto ({@code https://www.planalto.gov.br/...})
 * @param dataCaptura    quando o texto foi baixado
 * @param vigenciaInicio desde quando esta redação vale (na primeira carga, a data da norma; numa
 *                       nova redação, a data em que ela passa a valer)
 */
public record FonteLegislacao(
    String norma, String url, LocalDate dataCaptura, LocalDate vigenciaInicio) {

  private static final String HOST_OFICIAL = "www.planalto.gov.br";

  /** Valida os campos; a URL precisa ser {@code https} do Planalto. */
  public FonteLegislacao {
    Objects.requireNonNull(dataCaptura, "dataCaptura");
    Objects.requireNonNull(vigenciaInicio, "vigenciaInicio");
    if (norma == null || norma.isBlank() || norma.length() > 60) {
      throw new IllegalArgumentException("norma obrigatoria (ate 60 caracteres), ex.: LC 214/2025");
    }
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IllegalArgumentException("url invalida: " + url, e);
    }
    if (!"https".equals(uri.getScheme()) || !HOST_OFICIAL.equals(uri.getHost())) {
      throw new IllegalArgumentException(
          "url deve ser o texto oficial em https://" + HOST_OFICIAL + "/, recebido: " + url);
    }
    norma = norma.trim();
  }
}
