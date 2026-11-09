package br.com.agrocontabil.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Slugs que nenhum escritório registra (issue #464), lidos de
 * {@code resources/escritorio/slugs-reservados.txt} na subida: arquivo ausente ou linha fora da
 * forma derrubam a aplicação na hora, em vez de liberar em silêncio um nome como {@code www}.
 *
 * <p>Duas regras: o nome exato (infraestrutura, ambientes, produto, marcas e órgãos) e o termo
 * contido ({@code contem:agroconia}), contra phishing como {@code agroconia-suporte}.
 */
@Component
public class SlugsReservados {

  /** Arquivo versionado da lista. */
  static final String ARQUIVO = "escritorio/slugs-reservados.txt";

  private static final String PREFIXO_CONTEM = "contem:";

  private final Set<String> nomes;
  private final Set<String> termosContidos;

  /** Lê o arquivo da lista. */
  public SlugsReservados() {
    this(ARQUIVO);
  }

  SlugsReservados(String arquivo) {
    Set<String> lidosNomes = new LinkedHashSet<>();
    Set<String> lidosTermos = new LinkedHashSet<>();
    try (InputStream entrada = new ClassPathResource(arquivo).getInputStream();
        BufferedReader leitor = new BufferedReader(
            new InputStreamReader(entrada, StandardCharsets.UTF_8))) {
      String linha;
      while ((linha = leitor.readLine()) != null) {
        String valor = linha.strip();
        if (valor.isEmpty() || valor.startsWith("#")) {
          continue;
        }
        if (valor.startsWith(PREFIXO_CONTEM)) {
          lidosTermos.add(conferido(valor.substring(PREFIXO_CONTEM.length()), linha));
        } else {
          lidosNomes.add(conferido(valor, linha));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Lista de slugs reservados ilegivel: " + arquivo, e);
    }
    if (lidosNomes.isEmpty()) {
      throw new IllegalStateException("Lista de slugs reservados vazia: " + arquivo);
    }
    this.nomes = Set.copyOf(lidosNomes);
    this.termosContidos = Set.copyOf(lidosTermos);
  }

  /**
   * O slug, já normalizado, é reservado ou contém um termo proibido?
   *
   * @param slug slug normalizado
   * @return {@code true} se nenhum escritório pode registrá-lo
   */
  public boolean reservado(String slug) {
    return slug != null
        && (nomes.contains(slug) || termosContidos.stream().anyMatch(slug::contains));
  }

  /**
   * Nomes reservados exatos.
   *
   * @return conjunto imutável
   */
  public Set<String> nomes() {
    return nomes;
  }

  /**
   * Termos que nenhum slug pode conter.
   *
   * @return conjunto imutável
   */
  public Set<String> termosContidos() {
    return termosContidos;
  }

  /** Cada valor da lista já vem normalizado: minúsculas ASCII, dígito e hífen. */
  private static String conferido(String valor, String linha) {
    if (valor.isEmpty() || !valor.equals(valor.toLowerCase(Locale.ROOT))
        || !valor.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
            || c == '-')) {
      throw new IllegalStateException("Slug reservado fora da forma: '" + linha + "'");
    }
    return valor;
  }
}
