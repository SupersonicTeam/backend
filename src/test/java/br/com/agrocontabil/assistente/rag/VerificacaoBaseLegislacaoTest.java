package br.com.agrocontabil.assistente.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Aviso de base de legislação vazia na subida (issue #175): com {@code legislacao_trecho} vazia a
 * Ceres responde "não encontrei fundamento legal" a toda pergunta de lei, e quem montou o
 * ambiente não tinha como saber que faltava a ingestão. O aviso nunca derruba a subida.
 */
@ExtendWith(OutputCaptureExtension.class)
class VerificacaoBaseLegislacaoTest {

  private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
  private final VerificacaoBaseLegislacao verificacao = new VerificacaoBaseLegislacao(jdbc);

  @Test
  @DisplayName("Base vazia: WARN com o comando de ingestão")
  void baseVaziaAvisa(CapturedOutput saida) {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Boolean.class))).thenReturn(false);

    assertThat(verificacao.verificar()).isFalse();

    assertThat(saida.getOut()).contains("WARN").contains("legislacao_trecho")
        .contains("ingestao");
  }

  @Test
  @DisplayName("Base carregada: nenhum WARN")
  void baseCarregadaNaoAvisa(CapturedOutput saida) {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Boolean.class))).thenReturn(true);

    assertThat(verificacao.verificar()).isTrue();

    assertThat(saida.getOut()).doesNotContain("WARN");
  }

  @Test
  @DisplayName("Tabela ausente: WARN, sem lançar")
  void tabelaAusenteAvisaSemLancar(CapturedOutput saida) {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Boolean.class)))
        .thenThrow(new BadSqlGrammarException("verificar", "SELECT", new java.sql.SQLException(
            "relation \"legislacao_trecho\" does not exist")));

    assertThatCode(verificacao::verificar).doesNotThrowAnyException();

    assertThat(saida.getOut()).contains("WARN").contains("legislacao_trecho");
  }

  @Test
  @DisplayName("Banco fora na subida: WARN, sem lançar e sem a mensagem do driver")
  void bancoForaAvisaSemLancar(CapturedOutput saida) {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Boolean.class)))
        .thenThrow(new DataAccessResourceFailureException("senha=segredo"));

    assertThat(verificacao.verificar()).isFalse();

    assertThat(saida.getOut()).contains("WARN").doesNotContain("segredo");
  }
}
