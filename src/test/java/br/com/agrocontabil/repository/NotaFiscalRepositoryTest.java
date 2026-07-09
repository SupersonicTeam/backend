package br.com.agrocontabil.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Testes de integração do repositório {@link NotaFiscalRepository}.
 *
 * <p>Utiliza H2 em memória (profile {@code test}). Como algumas entidades do projeto usam
 * {@code columnDefinition} com tipos exclusivos do PostgreSQL ({@code text[]}, {@code jsonb}),
 * o {@code ddl-auto} é desabilitado e o schema compatível com H2 é criado via {@code @Sql}.
 *
 * <p>Cenários cobertos:
 * <ul>
 *   <li>Persistência de {@link NotaFiscal} com um item em cascata</li>
 *   <li>Leitura e verificação de campos após flush/reload</li>
 *   <li>Itens em cascata associados à nota</li>
 *   <li>Paginação via {@link NotaFiscalRepository#findByProdutorId}</li>
 *   <li>{@link NotaFiscalRepository#findByIdAndProdutorId} com produtor incorreto
 *       retornando {@link Optional#empty()}</li>
 * </ul>
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
class NotaFiscalRepositoryTest {

  @Autowired
  private NotaFiscalRepository notaFiscalRepository;

  @Autowired
  private ProdutorRepository produtorRepository;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    notaFiscalRepository.deleteAll();
    produtorRepository.deleteAll();

    produtor = Produtor.builder()
        .nomeCompleto("Maria Agricultora")
        .cpfCnpj("98765432100")
        .build();
    produtor = produtorRepository.save(produtor);
  }

  /**
   * Persiste uma NotaFiscal com 1 item, relê do banco e verifica todos os campos
   * mapeados, incluindo o embedded de endereço e o item em cascata.
   */
  @Test
  @DisplayName("deve persistir NotaFiscal com item em cascata e recuperar todos os campos")
  void shouldPersistNotaFiscalWithItemAndRetrieveAllFields() {
    NotaFiscal nota = buildNotaFiscal();
    NotaFiscalItem item = buildItem();
    nota.addItem(item);

    notaFiscalRepository.saveAndFlush(nota);

    // Relê diretamente do banco limpando a sessão via clear implícito do saveAndFlush
    Optional<NotaFiscal> found = notaFiscalRepository.findById(nota.getId());
    assertThat(found).isPresent();

    NotaFiscal retrieved = found.get();
    assertThat(retrieved.getNumero()).isEqualTo(1L);
    assertThat(retrieved.getSerie()).isEqualTo(1);
    assertThat(retrieved.getModelo()).isEqualTo("55");
    assertThat(retrieved.getNaturezaOperacao()).isEqualTo("Venda de producao rural");
    assertThat(retrieved.getDestinatarioNome()).isEqualTo("Jose Comprador");
    assertThat(retrieved.getDestinatarioCpfCnpj()).isEqualTo("12345678901");
    assertThat(retrieved.getStatus()).isEqualTo(StatusNotaFiscal.RASCUNHO);
    assertThat(retrieved.getValorTotalProdutos()).isEqualByComparingTo("1000.00");
    assertThat(retrieved.getValorTotalNota()).isEqualByComparingTo("1000.00");
    assertThat(retrieved.getCreatedAt()).isNotNull();
    assertThat(retrieved.getUpdatedAt()).isNotNull();

    // Verifica embedded de endereço
    EnderecoDestinatario endereco = retrieved.getDestinatarioEndereco();
    assertThat(endereco).isNotNull();
    assertThat(endereco.getLogradouro()).isEqualTo("Rua das Flores");
    assertThat(endereco.getNumero()).isEqualTo("100");
    assertThat(endereco.getMunicipio()).isEqualTo("Cuiaba");
    assertThat(endereco.getCodigoMunicipioIbge()).isEqualTo("5103403");
    assertThat(endereco.getUf()).isEqualTo("MT");
    assertThat(endereco.getCep()).isEqualTo("78000000");

    // Verifica item em cascata
    assertThat(retrieved.getItens()).hasSize(1);
    NotaFiscalItem retrievedItem = retrieved.getItens().get(0);
    assertThat(retrievedItem.getNumeroItem()).isEqualTo(1);
    assertThat(retrievedItem.getDescricao()).isEqualTo("Soja em graos");
    assertThat(retrievedItem.getNcm()).isEqualTo("12010090");
    assertThat(retrievedItem.getCfop()).isEqualTo("6101");
    assertThat(retrievedItem.getUnidade()).isEqualTo("KG");
    assertThat(retrievedItem.getQuantidade()).isEqualByComparingTo("500.0000");
    assertThat(retrievedItem.getValorUnitario()).isEqualByComparingTo("2.0000");
    assertThat(retrievedItem.getValorTotal()).isEqualByComparingTo("1000.00");
  }

  /**
   * Verifica que {@link NotaFiscalRepository#findByProdutorId} retorna apenas as notas
   * do produtor correto e suporta paginação.
   */
  @Test
  @DisplayName("findByProdutorId deve retornar apenas as notas do produtor informado, paginadas")
  void findByProdutorIdShouldReturnOnlyNotasOfGivenProdutor() {
    notaFiscalRepository.saveAndFlush(buildNotaFiscal());

    // Produtor diferente — nota dele não deve aparecer na busca do primeiro produtor
    Produtor outroProd = Produtor.builder()
        .nomeCompleto("Carlos Fazendeiro")
        .cpfCnpj("11122233344")
        .build();
    outroProd = produtorRepository.save(outroProd);

    notaFiscalRepository.saveAndFlush(buildNotaFiscalForProdutor(outroProd, 2L));

    Page<NotaFiscal> page = notaFiscalRepository.findByProdutorId(
        produtor.getId(), PageRequest.of(0, 10));

    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent().get(0).getProdutor().getId()).isEqualTo(produtor.getId());
    assertThat(page.getContent().get(0).getNumero()).isEqualTo(1L);
  }

  /**
   * Verifica que {@link NotaFiscalRepository#findByIdAndProdutorId} retorna vazio
   * quando o ID da nota existe mas pertence a outro produtor.
   */
  @Test
  @DisplayName("findByIdAndProdutorId deve retornar empty quando o produtor nao corresponde")
  void findByIdAndProdutorIdShouldReturnEmptyWhenProdutorDoesNotMatch() {
    NotaFiscal nota = notaFiscalRepository.saveAndFlush(buildNotaFiscal());

    UUID produtorErradoId = UUID.randomUUID();
    Optional<NotaFiscal> result =
        notaFiscalRepository.findByIdAndProdutorId(nota.getId(), produtorErradoId);

    assertThat(result).isEmpty();
  }

  /**
   * Verifica que {@link NotaFiscalRepository#findByIdAndProdutorId} retorna a nota
   * quando tanto o ID quanto o produtor estão corretos.
   */
  @Test
  @DisplayName("findByIdAndProdutorId deve retornar a nota quando produtor e id estao corretos")
  void findByIdAndProdutorIdShouldReturnNotaWhenMatch() {
    NotaFiscal nota = notaFiscalRepository.saveAndFlush(buildNotaFiscal());

    Optional<NotaFiscal> result =
        notaFiscalRepository.findByIdAndProdutorId(nota.getId(), produtor.getId());

    assertThat(result).isPresent();
    assertThat(result.get().getId()).isEqualTo(nota.getId());
  }

  /**
   * Regressão da issue #51: {@link NotaFiscalRepository#somarCreditosPorPeriodo} deve considerar
   * apenas notas importadas <strong>AUTORIZADAS</strong>. Uma nota importada em
   * {@link StatusNotaFiscal#XML_GERADO} (XML de origem não autorizado) não pode gerar crédito —
   * simetria com o débito, que já exige AUTORIZADA.
   */
  @Test
  @DisplayName("somarCreditosPorPeriodo deve ignorar nota importada nao AUTORIZADA (issue #51)")
  void somarCreditosPorPeriodoShouldIgnoreNaoAutorizada() {
    OffsetDateTime emissao = OffsetDateTime.parse("2026-07-10T09:00:00-03:00");
    OffsetDateTime inicio = OffsetDateTime.parse("2026-07-01T00:00:00-03:00");
    OffsetDateTime fim = OffsetDateTime.parse("2026-08-01T00:00:00-03:00");

    // Importada AUTORIZADA — deve creditar.
    notaFiscalRepository.saveAndFlush(buildImportada(
        10L, StatusNotaFiscal.AUTORIZADA, emissao,
        new BigDecimal("100.00"), new BigDecimal("40.00")));
    // Importada XML_GERADO (nao autorizada) — NAO deve creditar.
    notaFiscalRepository.saveAndFlush(buildImportada(
        11L, StatusNotaFiscal.XML_GERADO, emissao,
        new BigDecimal("999.00"), new BigDecimal("999.00")));

    CreditoSaldoAgregado agregado =
        notaFiscalRepository.somarCreditosPorPeriodo(produtor.getId(), inicio, fim);

    assertThat(agregado.creditoIbs()).isEqualByComparingTo("100.00");
    assertThat(agregado.creditoCbs()).isEqualByComparingTo("40.00");
    assertThat(agregado.quantidadeNotas()).isEqualTo(1L);
  }

  /**
   * Issue Agrocontabil#71: {@link NotaFiscalRepository#somarValorNotasPorOrigem} deve somar o
   * valor total por origem (PROPRIA/IMPORTADA), considerando apenas notas AUTORIZADAS no período
   * — mesmo recorte de {@link #somarCreditosPorPeriodoShouldIgnoreNaoAutorizada}.
   */
  @Test
  @DisplayName("somarValorNotasPorOrigem soma o valor total por origem, so notas AUTORIZADAS")
  void somarValorNotasPorOrigemShouldSumByOrigemOnlyAutorizada() {
    OffsetDateTime emissao = OffsetDateTime.parse("2026-07-10T09:00:00-03:00");
    OffsetDateTime inicio = OffsetDateTime.parse("2026-07-01T00:00:00-03:00");
    OffsetDateTime fim = OffsetDateTime.parse("2026-08-01T00:00:00-03:00");

    // Saida (PROPRIA) autorizada — entra na soma.
    NotaFiscal saida = buildNotaFiscalForProdutor(produtor, 20L);
    saida.setDataEmissao(emissao);
    saida.setOrigem(OrigemNotaFiscal.PROPRIA);
    saida.setStatus(StatusNotaFiscal.AUTORIZADA);
    saida.setValorTotalNota(new BigDecimal("12500.00"));
    notaFiscalRepository.saveAndFlush(saida);

    // Entrada (IMPORTADA) autorizada — entra na soma.
    NotaFiscal entrada = buildImportada(
        21L, StatusNotaFiscal.AUTORIZADA, emissao, new BigDecimal("30.00"), new BigDecimal("270.00"));
    entrada.setValorTotalNota(new BigDecimal("3200.00"));
    notaFiscalRepository.saveAndFlush(entrada);

    // Entrada (IMPORTADA) nao autorizada — NAO deve entrar na soma.
    NotaFiscal entradaNaoAutorizada = buildImportada(
        22L, StatusNotaFiscal.XML_GERADO, emissao, new BigDecimal("1.00"), new BigDecimal("1.00"));
    entradaNaoAutorizada.setValorTotalNota(new BigDecimal("999.00"));
    notaFiscalRepository.saveAndFlush(entradaNaoAutorizada);

    ValorNotasAgregado agregado =
        notaFiscalRepository.somarValorNotasPorOrigem(produtor.getId(), inicio, fim);

    assertThat(agregado.valorPropria()).isEqualByComparingTo("12500.00");
    assertThat(agregado.valorImportada()).isEqualByComparingTo("3200.00");
  }

  /**
   * A unique {@code uk_nota_fiscal_produtor_numero_serie_modelo} (changeset 0002) é a única linha
   * de defesa contra nota duplicada — {@code NotaFiscalService.create} não checa duplicidade em
   * código. Antes de o schema de teste replicar esta constraint, dois inserts de mesma
   * {@code (produtor, numero, serie, modelo)} passavam em verde no H2 enquanto o PostgreSQL os
   * rejeitava (falso verde). Este teste guarda essa regra.
   */
  @Test
  @DisplayName("deve rejeitar nota duplicada por (produtor, numero, serie, modelo)")
  void shouldRejectDuplicateNotaByProdutorNumeroSerieModelo() {
    notaFiscalRepository.saveAndFlush(buildNotaFiscal()); // numero=1, serie=1, modelo=55

    NotaFiscal duplicada = buildNotaFiscal(); // mesmo (produtor, numero, serie, modelo)
    assertThatThrownBy(() -> notaFiscalRepository.saveAndFlush(duplicada))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // -------------------------------------------------------------------------
  // Helpers de construção
  // -------------------------------------------------------------------------

  private NotaFiscal buildImportada(
      Long numero,
      StatusNotaFiscal status,
      OffsetDateTime dataEmissao,
      BigDecimal creditoIbs,
      BigDecimal creditoCbs) {
    NotaFiscal nota = buildNotaFiscalForProdutor(this.produtor, numero);
    nota.setDataEmissao(dataEmissao);
    nota.setOrigem(OrigemNotaFiscal.IMPORTADA);
    nota.setStatus(status);
    nota.setEmitenteNome("Fornecedor Agro Ltda");
    nota.setEmitenteCpfCnpj("11222333000181");
    nota.setValorTotalCreditoIbs(creditoIbs);
    nota.setValorTotalCreditoCbs(creditoCbs);
    return nota;
  }

  private NotaFiscal buildNotaFiscal() {
    return buildNotaFiscalForProdutor(this.produtor, 1L);
  }

  private NotaFiscal buildNotaFiscalForProdutor(Produtor prod, Long numero) {
    EnderecoDestinatario endereco = EnderecoDestinatario.builder()
        .logradouro("Rua das Flores")
        .numero("100")
        .municipio("Cuiaba")
        .codigoMunicipioIbge("5103403")
        .uf("MT")
        .cep("78000000")
        .build();

    return NotaFiscal.builder()
        .numero(numero)
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.now())
        .produtor(prod)
        .destinatarioNome("Jose Comprador")
        .destinatarioCpfCnpj("12345678901")
        .destinatarioEndereco(endereco)
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .status(StatusNotaFiscal.RASCUNHO)
        .build();
  }

  private NotaFiscalItem buildItem() {
    return NotaFiscalItem.builder()
        .numeroItem(1)
        .descricao("Soja em graos")
        .ncm("12010090")
        .cfop("6101")
        .unidade("KG")
        .quantidade(new BigDecimal("500.0000"))
        .valorUnitario(new BigDecimal("2.0000"))
        .valorTotal(new BigDecimal("1000.00"))
        .build();
  }
}
