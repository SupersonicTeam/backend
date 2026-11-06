package br.com.agrocontabil.config.demonstracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Os scripts de reset da demonstração ({@code docs/demonstracao/reset-*.sql}) repetem constantes do
 * seed. Teste de texto, sem banco e sem Docker: mudou o {@link CenarioDemonstracao}, ele pede o
 * script novo em qualquer máquina (o reset em si é provado no PostgreSQL pelo
 * {@code ResetDemonstracaoPostgresIntegrationTest}).
 */
class ScriptsResetDemonstracaoTest {

  @ParameterizedTest
  @ValueSource(strings = {"docs/demonstracao/reset-01-conferencia.sql",
      "docs/demonstracao/reset-02-apagar.sql"})
  void scriptsCoerentesComOCenario(String arquivo) throws IOException {
    String sql = Files.readString(Path.of(arquivo), StandardCharsets.UTF_8);

    assertThat(sql)
        .contains("nome = '" + CenarioDemonstracao.ESCRITORIO_NOME + "'")
        .contains("cnpj = '" + CenarioDemonstracao.ESCRITORIO_CNPJ + "'");
    CenarioDemonstracao.CARTEIRA.forEach(cliente ->
        assertThat(sql).as(cliente.nome()).contains("'" + cliente.nome() + "'"));
    CenarioDemonstracao.EQUIPE.forEach(membro ->
        assertThat(sql).as(membro.nome()).contains("'" + membro.nome() + "'"));
    // Nunca as tabelas de fora do reset nem SQL que apague em massa.
    String minusculo = sql.toLowerCase();
    assertThat(minusculo).doesNotContain("truncate").doesNotContain("drop table")
        .doesNotContain("delete from legislacao_trecho").doesNotContain("delete from shedlock")
        .doesNotContain("delete from databasechangelog")
        .doesNotContain("delete from registro_auditoria");
    // Ponto e virgula dentro de comentario quebra a divisao do script em comandos.
    sql.lines().filter(l -> l.stripLeading().startsWith("--"))
        .forEach(l -> assertThat(l).doesNotContain(";"));
  }
}
