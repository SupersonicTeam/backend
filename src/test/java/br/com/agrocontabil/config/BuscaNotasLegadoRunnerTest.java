package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.common.NormalizacaoBusca;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Preenchimento da {@code busca_normalizada} das notas anteriores ao changeSet 0037 (issue #174):
 * a mesma regra Java da gravação, inclusive para letras fora de qualquer mapa fixo de acentos.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class BuscaNotasLegadoRunnerTest {

  @Autowired private BuscaNotasLegadoRunner runner;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TestDatabaseCleaner cleaner;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    produtor = produtorRepository.save(
        Produtor.builder().nomeCompleto("Maria Agricultora").cpfCnpj("98765432100").build());
  }

  @AfterEach
  void tearDown() {
    cleaner.limparTudo();
  }

  @Test
  @DisplayName("Nota sem busca_normalizada recebe a da NormalizacaoBusca; a segunda rodada não acha nada")
  void preencheComARegraJavaEIdempotente() {
    UUID comAcentoForaDoMapa = notaLegada(7L, "Kråkeslottet Łódź",
        "Remessa para armazém");
    UUID comum = notaLegada(8L, "Cooperativa AGRÍCOLA", "Venda");

    assertThat(runner.preencherLegado()).isEqualTo(2);

    assertThat(busca(comAcentoForaDoMapa))
        .isEqualTo("7 krakeslottet łodz remessa para armazem 12345678901")
        .isEqualTo(NormalizacaoBusca.juntar(7L, "Kråkeslottet Łódź",
            "Remessa para armazém", "12345678901"));
    assertThat(busca(comum)).isEqualTo("8 cooperativa agricola venda 12345678901");
    assertThat(runner.preencherLegado()).isZero();
  }

  @Test
  @DisplayName("Nota que já tem busca_normalizada não é tocada")
  void naoTocaNotaJaPreenchida() {
    UUID id = notaFiscalRepository.save(nota(9L, "Cooperativa", "Venda")).getId();
    jdbc.update("UPDATE nota_fiscal SET busca_normalizada = 'valor gravado' WHERE id = ?", id);

    assertThat(runner.preencherLegado()).isZero();
    assertThat(busca(id)).isEqualTo("valor gravado");
  }

  @Test
  @DisplayName("#271: nota da regra 1 (sem itens nem documento) e recalculada; a atual nao e tocada")
  void recalculaNotaDaRegraAnteriorComItensEDocumento() {
    UUID antiga = notaFiscalRepository.save(comItens(nota(10L, "Cooperativa", "Venda"),
        "Soja em grãos", "12019000")).getId();
    UUID atual = notaFiscalRepository.save(comItens(nota(11L, "Laticinio", "Venda"),
        "Leite", "04012090")).getId();
    // Como ficou gravada pela regra 1: so numero, destinatario e natureza, sem versao.
    jdbc.update("UPDATE nota_fiscal SET busca_normalizada = '10 cooperativa venda',"
        + " busca_versao = 1 WHERE id = ?", antiga);

    assertThat(runner.preencherLegado()).isEqualTo(1);

    assertThat(busca(antiga))
        .isEqualTo("10 cooperativa venda 12345678901 soja em graos 12019000");
    assertThat(jdbc.queryForObject("SELECT busca_versao FROM nota_fiscal WHERE id = ?",
        Integer.class, antiga)).isEqualTo(NormalizacaoBusca.VERSAO_REGRA);
    assertThat(busca(atual)).contains("leite 04012090");
    assertThat(runner.preencherLegado()).isZero();
  }

  @Test
  @DisplayName("#271: percorre varios lotes por id, sem repetir nem pular nota")
  void percorreVariosLotes() {
    for (long n = 20; n < 27; n++) {
      UUID id = notaFiscalRepository.save(comItens(nota(n, "Destino " + n, "Venda"),
          "Produto " + n, "12019000")).getId();
      jdbc.update("UPDATE nota_fiscal SET busca_normalizada = 'velho', busca_versao = 1"
          + " WHERE id = ?", id);
    }
    BuscaNotasLegadoRunner emLotesDeTres = new BuscaNotasLegadoRunner(jdbc, false, 3);

    assertThat(emLotesDeTres.preencherLegado()).isEqualTo(7);

    assertThat(jdbc.queryForList("SELECT busca_normalizada FROM nota_fiscal", String.class))
        .allMatch(b -> b.contains("produto ") && b.contains("12345678901"));
    assertThat(emLotesDeTres.preencherLegado()).isZero();
  }

  private NotaFiscal comItens(NotaFiscal nota, String descricao, String ncm) {
    nota.addItem(br.com.agrocontabil.model.NotaFiscalItem.builder()
        .numeroItem(1).codigoProduto("P1").descricao(descricao).ncm(ncm).cfop("5101")
        .unidade("KG").quantidade(new BigDecimal("10.0000"))
        .valorUnitario(new BigDecimal("100.0000")).valorTotal(new BigDecimal("1000.00"))
        .build());
    return nota;
  }

  /** Grava pela aplicação e apaga a coluna: o estado de uma nota de antes do changeSet 0037. */
  private UUID notaLegada(long numero, String destinatario, String natureza) {
    UUID id = notaFiscalRepository.save(nota(numero, destinatario, natureza)).getId();
    jdbc.update("UPDATE nota_fiscal SET busca_normalizada = NULL, busca_versao = NULL"
        + " WHERE id = ?", id);
    return id;
  }

  private String busca(UUID id) {
    return jdbc.queryForObject("SELECT busca_normalizada FROM nota_fiscal WHERE id = ?",
        String.class, id);
  }

  private NotaFiscal nota(long numero, String destinatario, String natureza) {
    return NotaFiscal.builder()
        .numero(numero).serie(1).modelo("55")
        .naturezaOperacao(natureza)
        .dataEmissao(OffsetDateTime.now())
        .produtor(produtor)
        .destinatarioNome(destinatario).destinatarioCpfCnpj("12345678901")
        .destinatarioEndereco(EnderecoDestinatario.builder()
            .logradouro("Rua A").numero("1").municipio("Cuiaba")
            .codigoMunicipioIbge("5103403").uf("MT").cep("78000000").build())
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .build();
  }
}
