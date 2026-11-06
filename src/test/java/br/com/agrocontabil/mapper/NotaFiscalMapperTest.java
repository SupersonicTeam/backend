package br.com.agrocontabil.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.dto.NotaFiscalItemRequest;
import br.com.agrocontabil.dto.NotaFiscalItemResponse;
import br.com.agrocontabil.dto.NotaFiscalRequest;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitarios do {@link NotaFiscalMapper}.
 *
 * <p>Nao usa contexto Spring — mapper e uma classe de metodos estaticos.
 * Cobre a logica de calculo dos totais, arredondamento HALF_UP, modelo fixo "55",
 * status RASCUNHO e conversao de enderecos nulos/nao-nulos.
 *
 * <p>Cenarios cobertos:
 * <ul>
 *   <li>toEntity: campos mapeados, modelo "55", status RASCUNHO, serie padrao</li>
 *   <li>toEntity: valorTotal por item com arredondamento HALF_UP em 2dp</li>
 *   <li>toEntity: valorTotalProdutos = soma dos valorTotal dos itens</li>
 *   <li>toEntity: valorTotalNota == valorTotalProdutos (Sprint 2, sem impostos)</li>
 *   <li>toEntity: endereco nulo (destinatarioEndereco null) mapeado como null</li>
 *   <li>toEntity: endereco preenchido mapeado corretamente</li>
 *   <li>toEntity: produtor autenticado vinculado corretamente</li>
 *   <li>toResponse: todos os campos de NotaFiscal mapeados para NotaFiscalResponse</li>
 *   <li>toResponse: itens mapeados com valorTotal correto</li>
 *   <li>toResponse: endereco nulo permanece nulo na response</li>
 *   <li>toItemEntityList: converte lista de requests em entidades sem associacao com nota</li>
 * </ul>
 */
class NotaFiscalMapperTest {

  private Produtor produtor;
  private UUID produtorId;

  @BeforeEach
  void setUp() {
    produtorId = UUID.randomUUID();
    produtor = Produtor.builder()
        .id(produtorId)
        .nomeCompleto("Murilo Produtor")
        .cpfCnpj("12345678901")
        .build();
  }

  // -------------------------------------------------------------------------
  // toEntity — campos basicos, modelo, status, serie
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("toResponse (#409): compra importada leva origem e emitente; propria, origem e emitente nulo")
  void toResponseTrazOrigemEEmitente() {
    NotaFiscal propria = NotaFiscalMapper.toEntity(
        buildRequest(List.of(buildItem("1", "5.00"))), produtor);
    NotaFiscal importada = NotaFiscalMapper.toEntity(
        buildRequest(List.of(buildItem("1", "5.00"))), produtor);
    importada.setOrigem(br.com.agrocontabil.model.OrigemNotaFiscal.IMPORTADA);
    importada.setEmitenteNome("Posto Fornecedor Ltda");
    importada.setEmitenteCpfCnpj("12345678000195");

    var respostaPropria = NotaFiscalMapper.toResponse(propria);
    var respostaImportada = NotaFiscalMapper.toResponse(importada);

    assertThat(respostaPropria.origem())
        .isEqualTo(br.com.agrocontabil.model.OrigemNotaFiscal.PROPRIA);
    assertThat(respostaPropria.emitenteNome()).isNull();
    assertThat(respostaPropria.emitenteCpfCnpj()).isNull();
    assertThat(respostaImportada.origem())
        .isEqualTo(br.com.agrocontabil.model.OrigemNotaFiscal.IMPORTADA);
    assertThat(respostaImportada.emitenteNome()).isEqualTo("Posto Fornecedor Ltda");
    assertThat(respostaImportada.emitenteCpfCnpj()).isEqualTo("12345678000195");
  }

  @Test
  @DisplayName("toEntity deve fixar modelo '55' independente do request")
  void toEntityShouldFixModeloAs55() {
    NotaFiscalRequest request = buildRequest(List.of(buildItem("10", "1.00")));

    NotaFiscal nota = NotaFiscalMapper.toEntity(request, produtor);

    assertThat(nota.getModelo()).isEqualTo("55");
  }

  @Test
  @DisplayName("toEntity deve definir status inicial como RASCUNHO")
  void toEntityShouldSetStatusAsRascunho() {
    NotaFiscalRequest request = buildRequest(List.of(buildItem("1", "5.00")));

    NotaFiscal nota = NotaFiscalMapper.toEntity(request, produtor);

    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.RASCUNHO);
  }

  @Test
  @DisplayName("toEntity deve usar serie 1 como padrao quando serie e null no request")
  void toEntityShouldDefaultSerieToOneWhenNull() {
    NotaFiscalRequest request = new NotaFiscalRequest(
        1L, null, "Venda de producao rural",
        OffsetDateTime.now(), null, null,
        "Comprador", "12345678901",
        null, null,
        List.of(buildItem("1", "10.00")));

    NotaFiscal nota = NotaFiscalMapper.toEntity(request, produtor);

    assertThat(nota.getSerie()).isEqualTo(1);
  }

  @Test
  @DisplayName("toEntity deve preservar serie informada quando serie nao e null no request")
  void toEntityShouldPreserveSerieWhenProvided() {
    NotaFiscalRequest request = new NotaFiscalRequest(
        1L, 3, "Venda de producao rural",
        OffsetDateTime.now(), null, null,
        "Comprador", "12345678901",
        null, null,
        List.of(buildItem("1", "10.00")));

    NotaFiscal nota = NotaFiscalMapper.toEntity(request, produtor);

    assertThat(nota.getSerie()).isEqualTo(3);
  }

  @Test
  @DisplayName("toEntity deve vincular o produtor passado como parametro")
  void toEntityShouldLinkProvidedProdutor() {
    NotaFiscalRequest request = buildRequest(List.of(buildItem("1", "10.00")));

    NotaFiscal nota = NotaFiscalMapper.toEntity(request, produtor);

    assertThat(nota.getProdutor()).isSameAs(produtor);
    assertThat(nota.getProdutor().getId()).isEqualTo(produtorId);
  }

  // -------------------------------------------------------------------------
  // toEntity — calculo de valorTotal por item com arredondamento HALF_UP
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("toEntity deve calcular valorTotal do item como quantidade * valorUnitario HALF_UP 2dp")
  void toEntityShouldCalculateItemValueWithHalfUpRounding() {
    // 3 * 1.005 = 3.015 → HALF_UP 2dp = 3.02
    NotaFiscalItemRequest item = buildItem("3", "1.005");
    NotaFiscalRequest request = buildRequest(List.of(item));

    NotaFiscal nota = NotaFiscalMapper.toEntity(request, produtor);

    assertThat(nota.getItens()).hasSize(1);
    assertThat(nota.getItens().get(0).getValorTotal()).isEqualByComparingTo("3.02");
  }

  @Test
  @DisplayName("toEntity deve arredondar .5 para cima com HALF_UP (0.5 * 0.1 = 0.05 → 0.05)")
  void toEntityHalfUpRoundingAtExactHalf() {
    // 1 * 0.055 = 0.055 → HALF_UP 2dp = 0.06
    NotaFiscalItemRequest item = buildItem("1", "0.055");
    NotaFiscalRequest request = buildRequest(List.of(item));

    NotaFiscal nota = NotaFiscalMapper.toEntity(request, produtor);

    assertThat(nota.getItens().get(0).getValorTotal()).isEqualByComparingTo("0.06");
  }

  @Test
  @DisplayName("toEntity deve calcular valorTotal exato sem arredondamento para valores inteiros")
  void toEntityShouldCalculateExactValueForWholeNumbers() {
    // 500 * 2.00 = 1000.00
    NotaFiscalItemRequest item = buildItem("500", "2.00");
    NotaFiscalRequest request = buildRequest(List.of(item));

    NotaFiscal nota = NotaFiscalMapper.toEntity(request, produtor);

    assertThat(nota.getItens().get(0).getValorTotal()).isEqualByComparingTo("1000.00");
  }

  // -------------------------------------------------------------------------
  // toEntity — valorTotalProdutos e valorTotalNota
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("toEntity deve calcular valorTotalProdutos como soma dos valorTotal dos itens")
  void toEntityShouldCalculateTotalProdutosAsSumOfItemTotals() {
    // Item 1: 10 * 2.50 = 25.00
    // Item 2: 5 * 4.00 = 20.00
    // Total = 45.00
    NotaFiscalItemRequest item1 = buildItem("10", "2.50");
    NotaFiscalItemRequest item2 = buildItem("5", "4.00");
    NotaFiscalRequest request = buildRequest(List.of(item1, item2));

    NotaFiscal nota = NotaFiscalMapper.toEntity(request, produtor);

    assertThat(nota.getValorTotalProdutos()).isEqualByComparingTo("45.00");
  }

  @Test
  @DisplayName("toEntity deve igualar valorTotalNota a valorTotalProdutos (Sprint 2 sem impostos)")
  void toEntityShouldSetValorTotalNotaEqualToValorTotalProdutos() {
    NotaFiscalRequest request = buildRequest(List.of(buildItem("100", "5.75")));

    NotaFiscal nota = NotaFiscalMapper.toEntity(request, produtor);

    assertThat(nota.getValorTotalNota()).isEqualByComparingTo(nota.getValorTotalProdutos());
  }

  @Test
  @DisplayName("toEntity com multiplos itens deve somar corretamente tres itens distintos")
  void toEntityShouldSumThreeItemsCorrectly() {
    // 1*10 + 2*5 + 3*3.33 = 10 + 10 + 9.99 = 29.99
    NotaFiscalItemRequest i1 = buildItem("1", "10.00");
    NotaFiscalItemRequest i2 = buildItem("2", "5.00");
    NotaFiscalItemRequest i3 = buildItem("3", "3.33");
    NotaFiscalRequest request = buildRequest(List.of(i1, i2, i3));

    NotaFiscal nota = NotaFiscalMapper.toEntity(request, produtor);

    assertThat(nota.getValorTotalProdutos()).isEqualByComparingTo("29.99");
    assertThat(nota.getValorTotalNota()).isEqualByComparingTo("29.99");
  }

  // -------------------------------------------------------------------------
  // toEntity — endereco null e preenchido
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("toEntity deve mapear destinatarioEndereco como null quando request traz null")
  void toEntityShouldMapNullEnderecoAsNull() {
    NotaFiscalRequest request = buildRequest(List.of(buildItem("1", "10.00")));
    // buildRequest ja passa endereco null

    NotaFiscal nota = NotaFiscalMapper.toEntity(request, produtor);

    assertThat(nota.getDestinatarioEndereco()).isNull();
  }

  @Test
  @DisplayName("toEntity deve mapear todos os campos do endereco quando preenchido")
  void toEntityShouldMapEnderecoFieldsCorrectly() {
    NotaFiscalRequest.EnderecoDestinatarioRequest enderecoReq =
        new NotaFiscalRequest.EnderecoDestinatarioRequest(
            "Rua das Flores", "100", "Cuiaba", "5103403", "MT", "78000000");
    NotaFiscalRequest request = new NotaFiscalRequest(
        1L, 1, "Venda de producao rural",
        OffsetDateTime.now(), null, null,
        "Comprador", "12345678901",
        null, enderecoReq,
        List.of(buildItem("1", "10.00")));

    NotaFiscal nota = NotaFiscalMapper.toEntity(request, produtor);

    EnderecoDestinatario endereco = nota.getDestinatarioEndereco();
    assertThat(endereco).isNotNull();
    assertThat(endereco.getLogradouro()).isEqualTo("Rua das Flores");
    assertThat(endereco.getNumero()).isEqualTo("100");
    assertThat(endereco.getMunicipio()).isEqualTo("Cuiaba");
    assertThat(endereco.getCodigoMunicipioIbge()).isEqualTo("5103403");
    assertThat(endereco.getUf()).isEqualTo("MT");
    assertThat(endereco.getCep()).isEqualTo("78000000");
  }

  // -------------------------------------------------------------------------
  // toResponse — mapeamento completo da entidade
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("toResponse deve mapear todos os campos escalares da entidade corretamente")
  void toResponseShouldMapAllScalarFields() {
    UUID notaId = UUID.randomUUID();
    OffsetDateTime dataEmissao = OffsetDateTime.now();
    OffsetDateTime createdAt = OffsetDateTime.now();
    OffsetDateTime updatedAt = OffsetDateTime.now();

    NotaFiscal nota = NotaFiscal.builder()
        .id(notaId)
        .chaveAcesso(null)
        .numero(42L)
        .serie(2)
        .modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(dataEmissao)
        .dataSaida(null)
        .produtor(produtor)
        .destinatarioNome("Comprador X")
        .destinatarioCpfCnpj("12345678901234")
        .destinatarioIe("IE-001")
        .destinatarioEndereco(null)
        .valorTotalProdutos(new BigDecimal("250.00"))
        .valorTotalNota(new BigDecimal("250.00"))
        .status(StatusNotaFiscal.RASCUNHO)
        .createdAt(createdAt)
        .updatedAt(updatedAt)
        .build();

    NotaFiscalResponse response = NotaFiscalMapper.toResponse(nota);

    assertThat(response.id()).isEqualTo(notaId);
    assertThat(response.chaveAcesso()).isNull();
    assertThat(response.numero()).isEqualTo(42L);
    assertThat(response.serie()).isEqualTo(2);
    assertThat(response.modelo()).isEqualTo("55");
    assertThat(response.naturezaOperacao()).isEqualTo("Venda de producao rural");
    assertThat(response.status()).isEqualTo(StatusNotaFiscal.RASCUNHO);
    assertThat(response.dataEmissao()).isEqualTo(dataEmissao);
    assertThat(response.dataSaida()).isNull();
    assertThat(response.produtorId()).isEqualTo(produtorId);
    assertThat(response.destinatarioNome()).isEqualTo("Comprador X");
    assertThat(response.destinatarioCpfCnpj()).isEqualTo("12345678901234");
    assertThat(response.destinatarioIe()).isEqualTo("IE-001");
    assertThat(response.destinatarioEndereco()).isNull();
    assertThat(response.valorTotalProdutos()).isEqualByComparingTo("250.00");
    assertThat(response.valorTotalNota()).isEqualByComparingTo("250.00");
    assertThat(response.createdAt()).isEqualTo(createdAt);
    assertThat(response.updatedAt()).isEqualTo(updatedAt);
  }

  @Test
  @DisplayName("toResponse deve mapear itens da nota com valorTotal correto")
  void toResponseShouldMapItemsWithCorrectValorTotal() {
    NotaFiscalItem item = NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(1)
        .codigoProduto("P001")
        .descricao("Soja em graos")
        .ncm("12019000")
        .cfop("6101")
        .unidade("KG")
        .quantidade(new BigDecimal("500.0000"))
        .valorUnitario(new BigDecimal("2.5000"))
        .valorTotal(new BigDecimal("1250.00"))
        .createdAt(OffsetDateTime.now())
        .updatedAt(OffsetDateTime.now())
        .build();

    NotaFiscal nota = buildNotaComItem(item);

    NotaFiscalResponse response = NotaFiscalMapper.toResponse(nota);

    assertThat(response.itens()).hasSize(1);
    NotaFiscalItemResponse itemResponse = response.itens().get(0);
    assertThat(itemResponse.descricao()).isEqualTo("Soja em graos");
    assertThat(itemResponse.quantidade()).isEqualByComparingTo("500.0000");
    assertThat(itemResponse.valorUnitario()).isEqualByComparingTo("2.5000");
    assertThat(itemResponse.valorTotal()).isEqualByComparingTo("1250.00");
    assertThat(itemResponse.codigoProduto()).isEqualTo("P001");
  }

  @Test
  @DisplayName("toResponse deve retornar endereco preenchido quando entidade tem endereco")
  void toResponseShouldMapEnderecoWhenPresent() {
    EnderecoDestinatario endereco = EnderecoDestinatario.builder()
        .logradouro("Av. Central")
        .numero("200")
        .municipio("Sorriso")
        .codigoMunicipioIbge("5107925")
        .uf("MT")
        .cep("78890000")
        .build();

    NotaFiscal nota = buildNotaComItem(buildItemEntity());
    nota.setDestinatarioEndereco(endereco);

    NotaFiscalResponse response = NotaFiscalMapper.toResponse(nota);

    assertThat(response.destinatarioEndereco()).isNotNull();
    assertThat(response.destinatarioEndereco().logradouro()).isEqualTo("Av. Central");
    assertThat(response.destinatarioEndereco().municipio()).isEqualTo("Sorriso");
    assertThat(response.destinatarioEndereco().uf()).isEqualTo("MT");
    assertThat(response.destinatarioEndereco().cep()).isEqualTo("78890000");
  }

  @Test
  @DisplayName("toResponse com lista de itens vazia deve retornar lista vazia na response")
  void toResponseWithNoItemsShouldReturnEmptyItemList() {
    NotaFiscal nota = NotaFiscal.builder()
        .id(UUID.randomUUID())
        .numero(1L)
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Venda")
        .dataEmissao(OffsetDateTime.now())
        .produtor(produtor)
        .destinatarioNome("Comprador")
        .destinatarioCpfCnpj("12345678901")
        .destinatarioEndereco(null)
        .valorTotalProdutos(BigDecimal.ZERO)
        .valorTotalNota(BigDecimal.ZERO)
        .status(StatusNotaFiscal.RASCUNHO)
        .build();

    NotaFiscalResponse response = NotaFiscalMapper.toResponse(nota);

    assertThat(response.itens()).isEmpty();
  }

  // -------------------------------------------------------------------------
  // toItemEntityList — conversao de lista de requests em entidades
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("toItemEntityList deve converter cada request em entidade com valorTotal calculado")
  void toItemEntityListShouldConvertEachRequestWithCalculatedTotal() {
    NotaFiscalItemRequest i1 = buildItem("10", "5.00");
    NotaFiscalItemRequest i2 = buildItem("3", "3.333");

    List<NotaFiscalItem> result = NotaFiscalMapper.toItemEntityList(List.of(i1, i2));

    assertThat(result).hasSize(2);
    // 10 * 5.00 = 50.00
    assertThat(result.get(0).getValorTotal()).isEqualByComparingTo("50.00");
    // 3 * 3.333 = 9.999 → HALF_UP 2dp = 10.00
    assertThat(result.get(1).getValorTotal()).isEqualByComparingTo("10.00");
  }

  @Test
  @DisplayName("toItemEntityList deve retornar itens sem associacao com nota (notaFiscal null)")
  void toItemEntityListShouldReturnItemsWithNoNotaFiscalAssociation() {
    List<NotaFiscalItem> result =
        NotaFiscalMapper.toItemEntityList(List.of(buildItem("1", "1.00")));

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getNotaFiscal()).isNull();
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private NotaFiscalRequest buildRequest(List<NotaFiscalItemRequest> itens) {
    return new NotaFiscalRequest(
        1L,
        1,
        "Venda de producao rural",
        OffsetDateTime.now(),
        null,
        null,
        "Comprador Teste",
        "12345678901",
        null,
        null,
        itens);
  }

  /**
   * Cria um {@link NotaFiscalItemRequest} simples com o numeroItem=1 padrao.
   * quantidade e valorUnitario sao passados como strings para facilitar casos de borda.
   */
  private NotaFiscalItemRequest buildItem(String quantidade, String valorUnitario) {
    return new NotaFiscalItemRequest(
        1,
        null,
        "Soja em graos",
        "12019000",
        "6101",
        "KG",
        new BigDecimal(quantidade),
        new BigDecimal(valorUnitario),
        null, null, null);
  }

  private NotaFiscalItem buildItemEntity() {
    return NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(1)
        .descricao("Soja em graos")
        .ncm("12019000")
        .cfop("6101")
        .unidade("KG")
        .quantidade(new BigDecimal("100.0000"))
        .valorUnitario(new BigDecimal("3.0000"))
        .valorTotal(new BigDecimal("300.00"))
        .createdAt(OffsetDateTime.now())
        .updatedAt(OffsetDateTime.now())
        .build();
  }

  private NotaFiscal buildNotaComItem(NotaFiscalItem item) {
    NotaFiscal nota = NotaFiscal.builder()
        .id(UUID.randomUUID())
        .numero(1L)
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.now())
        .produtor(produtor)
        .destinatarioNome("Comprador")
        .destinatarioCpfCnpj("12345678901")
        .destinatarioEndereco(null)
        .valorTotalProdutos(item.getValorTotal())
        .valorTotalNota(item.getValorTotal())
        .status(StatusNotaFiscal.RASCUNHO)
        .createdAt(OffsetDateTime.now())
        .updatedAt(OffsetDateTime.now())
        .build();
    nota.addItem(item);
    return nota;
  }
}
