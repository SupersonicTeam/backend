package br.com.agrocontabil.assistente;

import java.nio.file.Path;

/**
 * Parâmetros obrigatórios das ferramentas manuais de diagnóstico da rota de lei (#436).
 *
 * <p>O arquivo de perguntas e o de saída não têm padrão: sem eles a ferramenta falharia lendo um
 * conjunto que não foi escolhido (o padrão antigo apontava para um conjunto de diagnóstico) ou
 * gravando por cima de uma medição. A conferência roda num {@code @BeforeAll}, antes de o contexto
 * do Spring subir e de qualquer arquivo ser lido.
 */
final class ParametrosDiagnostico {

  static final String PERGUNTAS = "diagnostico.perguntas";
  static final String SAIDA = "diagnostico.saida";

  private ParametrosDiagnostico() {}

  /** Arquivo JSON com as perguntas ({@code -Ddiagnostico.perguntas}). */
  static Path perguntas() {
    return obrigatorio(PERGUNTAS, "arquivo JSON com as perguntas");
  }

  /** Arquivo JSON de saída ({@code -Ddiagnostico.saida}). */
  static Path saida() {
    return obrigatorio(SAIDA, "arquivo JSON de saída");
  }

  /** Confere os dois parâmetros; chamado antes de qualquer leitura. */
  static void exigir() {
    perguntas();
    saida();
  }

  private static Path obrigatorio(String propriedade, String descricao) {
    String valor = System.getProperty(propriedade);
    if (valor == null || valor.isBlank()) {
      throw new IllegalStateException("Informe -D" + propriedade + "=<" + descricao + ">: a "
          + "ferramenta de diagnóstico não tem valor padrão para ele.");
    }
    return Path.of(valor.strip());
  }
}
