package br.com.agrocontabil.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Prova que o schema H2 de teste ({@code schema-nota-fiscal-test.sql}) replica as foreign keys
 * definidas pelo Liquibase em produção, com as mesmas ações {@code ON DELETE} (issue #90).
 *
 * <p>Sem estas FKs, comportamentos dependentes de integridade referencial (RESTRICT ao excluir
 * {@code Produtor} com notas, CASCADE ao remover nota, rejeição de filho órfão) passavam em
 * falso verde no H2 e divergiam do PostgreSQL.
 *
 * <p>Todo DML é feito via {@link JdbcTemplate}: o Hibernate mascararia o comportamento do banco
 * com o cascade próprio da entidade ({@code CascadeType.ALL}/{@code orphanRemoval}).
 *
 * <p>Fonte das constraints: changeSets {@code 0002} ({@code fk_nota_fiscal_produtor} RESTRICT,
 * {@code fk_nota_fiscal_item_nota} CASCADE), {@code 0014} ({@code fk_apuracao_produtor}) e
 * {@code 0018} ({@code fk_guia_fiscal_produtor}).
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
class SchemaTestIntegridadeReferencialTest {

  private static final String INSERT_PRODUTOR =
      "INSERT INTO produtores (id, nome_completo) VALUES (?, ?)";

  // Os INSERTs listam TODAS as colunas NOT NULL: o H2 em memoria e compartilhado entre contextos
  // (DB_CLOSE_DELAY=-1) e a tabela pode ter sido criada pelo Hibernate (create-drop) de outro
  // teste, sem os DEFAULTs do script.
  private static final String INSERT_NOTA =
      "INSERT INTO nota_fiscal (id, numero, serie, modelo, natureza_operacao, data_emissao, "
          + "produtor_id, destinatario_nome, destinatario_cpf_cnpj, valor_total_produtos, "
          + "valor_total_nota, status, tipo_documento, origem) "
          + "VALUES (?, 1, 1, '55', 'Venda', ?, ?, 'Jose', '12345678901', 10.00, 10.00, "
          + "'RASCUNHO', 'NFE', 'PROPRIA')";

  private static final String INSERT_ITEM =
      "INSERT INTO nota_fiscal_item (id, nota_fiscal_id, numero_item, descricao, ncm, cfop, "
          + "unidade, quantidade, valor_unitario, valor_total) "
          + "VALUES (?, ?, 1, 'Soja', '12010090', '6101', 'KG', 1, 10.00, 10.00)";

  private static final String INSERT_APURACAO =
      "INSERT INTO apuracao_mensal (id, produtor_id, periodo, status, debito_ibs, debito_cbs, "
          + "credito_ibs, credito_cbs, saldo_ibs, saldo_cbs, total_notas) "
          + "VALUES (?, ?, '2026-09', 'ABERTO', 0, 0, 0, 0, 0, 0, 0)";

  private static final String INSERT_GUIA =
      "INSERT INTO guias_fiscais (id, produtor_id, tipo_guia, data_vencimento, valor, status) "
          + "VALUES (?, ?, 'ICMS', ?, 100.00, 'PENDENTE')";

  @Autowired
  private JdbcTemplate jdbc;

  private UUID produtorId;

  @BeforeEach
  void setUp() {
    produtorId = UUID.randomUUID();
    jdbc.update(INSERT_PRODUTOR, produtorId, "Maria Agricultora");
  }

  // -------------------------------------------------------------------------
  // FK-01 — fk_nota_fiscal_produtor ON DELETE RESTRICT
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("deve rejeitar exclusao de produtor que possui notas fiscais (RESTRICT)")
  void deveRejeitarExclusaoDeProdutorComNotas() {
    jdbc.update(INSERT_NOTA, UUID.randomUUID(), OffsetDateTime.now(), produtorId);

    assertThatThrownBy(() -> jdbc.update("DELETE FROM produtores WHERE id = ?", produtorId))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(count("produtores", "id", produtorId)).isEqualTo(1);
  }

  // -------------------------------------------------------------------------
  // FK-02 — fk_nota_fiscal_item_nota ON DELETE CASCADE
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("deve remover itens automaticamente ao excluir a nota por SQL (CASCADE)")
  void deveRemoverItensAoExcluirNotaPorSql() {
    UUID notaId = UUID.randomUUID();
    jdbc.update(INSERT_NOTA, notaId, OffsetDateTime.now(), produtorId);
    jdbc.update(INSERT_ITEM, UUID.randomUUID(), notaId);
    assertThat(count("nota_fiscal_item", "nota_fiscal_id", notaId)).isEqualTo(1);

    jdbc.update("DELETE FROM nota_fiscal WHERE id = ?", notaId);

    assertThat(count("nota_fiscal_item", "nota_fiscal_id", notaId)).isZero();
  }

  // -------------------------------------------------------------------------
  // FK-03 — filho orfao rejeitado em todas as tabelas com FK
  // -------------------------------------------------------------------------

  /** Cenario: nome da relacao filho -> pai e funcao que monta o INSERT do filho para um pai. */
  record Filho(String relacao, Function<UUID, Object[]> args, String sql) {
    @Override
    public String toString() {
      return relacao;
    }
  }

  static Stream<Filho> filhos() {
    return Stream.of(
        new Filho("nota_fiscal -> produtores", pai ->
            new Object[] {UUID.randomUUID(), OffsetDateTime.now(), pai}, INSERT_NOTA),
        new Filho("nota_fiscal_item -> nota_fiscal", pai ->
            new Object[] {UUID.randomUUID(), pai}, INSERT_ITEM),
        new Filho("apuracao_mensal -> produtores", pai ->
            new Object[] {UUID.randomUUID(), pai}, INSERT_APURACAO),
        new Filho("guias_fiscais -> produtores", pai ->
            new Object[] {UUID.randomUUID(), pai, LocalDate.now()}, INSERT_GUIA));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("filhos")
  @DisplayName("deve rejeitar filho orfao (pai inexistente)")
  void deveRejeitarFilhoOrfao(Filho filho) {
    UUID paiInexistente = UUID.randomUUID();

    assertThatThrownBy(() -> jdbc.update(filho.sql(), filho.args().apply(paiInexistente)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // -------------------------------------------------------------------------
  // AC5 — fk_apuracao_produtor / fk_guia_fiscal_produtor (NO ACTION = rejeita)
  // -------------------------------------------------------------------------

  static Stream<Filho> dependentesDoProdutor() {
    return filhos().filter(f -> f.relacao().endsWith("-> produtores"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("dependentesDoProdutor")
  @DisplayName("deve rejeitar exclusao de produtor que possui dependentes")
  void deveRejeitarExclusaoDeProdutorComDependentes(Filho filho) {
    jdbc.update(filho.sql(), filho.args().apply(produtorId));

    assertThatThrownBy(() -> jdbc.update("DELETE FROM produtores WHERE id = ?", produtorId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private int count(String tabela, String coluna, UUID valor) {
    Integer total = jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + tabela + " WHERE " + coluna + " = ?", Integer.class, valor);
    return total == null ? 0 : total;
  }
}
