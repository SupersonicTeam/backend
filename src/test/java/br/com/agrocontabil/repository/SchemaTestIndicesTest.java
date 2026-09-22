package br.com.agrocontabil.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Prova que o schema H2 de teste replica os índices compostos criados pelo Liquibase para as
 * consultas de escalabilidade (issue #102).
 *
 * <p>A <strong>ordem</strong> das colunas é o que importa: um índice composto só serve ao
 * predicado se as igualdades vierem antes do range. Por isso a asserção compara a lista ordenada
 * por {@code ORDINAL_POSITION}, e não apenas a existência do índice.
 *
 * <p>O objetivo é travar a sincronia entre
 * {@code 0019-indices-compostos-escalabilidade.xml} (produção) e
 * {@code schema-nota-fiscal-test.sql} (teste): se um lado mudar sem o outro, este teste quebra.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
@Sql(
    scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS
)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class SchemaTestIndicesTest {

  @Autowired
  private JdbcTemplate jdbc;

  static Stream<Arguments> indicesEsperados() {
    return Stream.of(
        // Agregacoes do dashboard/apuracao: produtor_id = ? AND status = ? AND data_emissao range.
        Arguments.of("nota_fiscal", "idx_nota_fiscal_produtor_status_emissao",
            List.of("produtor_id", "status", "data_emissao")),
        // GuiaFiscalStatusSyncJob: status = 'PENDENTE' AND data_vencimento < ?.
        Arguments.of("guias_fiscais", "idx_guia_fiscal_status_vencimento",
            List.of("status", "data_vencimento")),
        // Listagem/calendario: produtor_id = ? ORDER BY data_vencimento DESC.
        Arguments.of("guias_fiscais", "idx_guia_fiscal_produtor_vencimento",
            List.of("produtor_id", "data_vencimento")));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("indicesEsperados")
  @DisplayName("indice composto deve existir com as colunas na ordem esperada")
  void indiceCompostoDeveExistirComColunasNaOrdem(
      String tabela, String indice, List<String> colunasEsperadas) {
    List<String> colunas = jdbc.queryForList(
        """
        SELECT COLUMN_NAME
          FROM INFORMATION_SCHEMA.INDEX_COLUMNS
         WHERE UPPER(TABLE_NAME) = UPPER(?)
           AND UPPER(INDEX_NAME) = UPPER(?)
         ORDER BY ORDINAL_POSITION
        """,
        String.class, tabela, indice);

    assertThat(colunas)
        .as("colunas de %s (na ordem)", indice)
        .containsExactlyElementsOf(colunasEsperadas.stream().map(String::toUpperCase).toList());
  }
}
