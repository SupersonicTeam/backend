package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.ProdutorTestFixtures;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.nfe.model.IbsCbs;
import br.com.agrocontabil.nfe.model.IbsCbsTot;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.nfe.model.Emit;
import br.com.agrocontabil.nfe.model.EnderEmit;
import br.com.agrocontabil.service.ChaveAcessoGenerator;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes do split estadual/municipal de IBS no {@link NotaFiscalNfeMapper}
 * (Sprint 4 — issue Agrocontabil#31, RN002).
 *
 * <p>Verifica que o mapper consome as parcelas <strong>reais</strong> gravadas no item pelo
 * {@code TributacaoService} ({@code aliquotaIbsUf}/{@code aliquotaIbsMun},
 * {@code valorIbsUf}/{@code valorIbsMun}) — em {@code pIBSUF}/{@code pIBSMun} e
 * {@code vIBSUF}/{@code vIBSMun} — em vez da antiga divisão fixa 50/50; e que os totais
 * {@code gIBS} somam essas parcelas por item.
 */
class NotaFiscalNfeMapperTest {

  private NotaFiscalNfeMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new NotaFiscalNfeMapper(new ChaveAcessoGenerator());
  }

  @Test
  @DisplayName("mapper deve emitir as parcelas UF/Mun reais do item (nao 50/50) — RN002")
  void shouldEmitRealSplitFromItem() {
    // Parcelas assimetricas: 75% estadual / 25% municipal — impossivel confundir com 50/50.
    NotaFiscalItem item = itemBase();
    item.setAliquotaIbs(new BigDecimal("0.0004"));
    item.setAliquotaIbsUf(new BigDecimal("0.0003"));
    item.setAliquotaIbsMun(new BigDecimal("0.0001"));
    item.setValorIbs(new BigDecimal("0.40"));
    item.setValorIbsUf(new BigDecimal("0.30"));
    item.setValorIbsMun(new BigDecimal("0.10"));

    NFe nfe = mapper.toNfe(notaCom(item));

    IbsCbs.GIbsCbs g = nfe.getInfNFe().getDet().get(0).getImposto().getIbsCbs().getGIBSCBS();
    // pIBSUF em percentual: 0.0003 * 100 = 0.0300; pIBSMun: 0.0001 * 100 = 0.0100.
    assertThat(g.getGIBSUF().getPIBSUF()).isEqualTo("0.0300");
    assertThat(g.getGIBSMun().getPIBSMun()).isEqualTo("0.0100");
    assertThat(g.getGIBSUF().getVIBSUF()).isEqualTo("0.30");
    assertThat(g.getGIBSMun().getVIBSMun()).isEqualTo("0.10");
    assertThat(g.getVIBS()).isEqualTo("0.40");
  }

  @Test
  @DisplayName("totais gIBS devem somar as parcelas UF/Mun reais dos itens — RN002")
  void totalsShouldSumItemSplits() {
    NotaFiscalItem item = itemBase();
    item.setAliquotaIbs(new BigDecimal("0.0004"));
    item.setAliquotaIbsUf(new BigDecimal("0.0003"));
    item.setAliquotaIbsMun(new BigDecimal("0.0001"));
    item.setValorIbs(new BigDecimal("0.40"));
    item.setValorIbsUf(new BigDecimal("0.30"));
    item.setValorIbsMun(new BigDecimal("0.10"));

    NotaFiscal nota = notaCom(item);
    nota.setValorTotalIbs(new BigDecimal("0.40"));
    nota.setValorTotalCbs(new BigDecimal("9.00"));

    NFe nfe = mapper.toNfe(nota);

    IbsCbsTot tot = nfe.getInfNFe().getTotal().getIbsCbsTot();
    assertThat(tot.getGIBS().getGIBSUF().getVIBSUF()).isEqualTo("0.30");
    assertThat(tot.getGIBS().getGIBSMun().getVIBSMun()).isEqualTo("0.10");
  }

  @Test
  @DisplayName("item legado sem parcelas: 2026 todo estadual, 2027 dividido 50/50 (#277)")
  void itemLegadoSemParcelasSegueAEsferaDoAno() {
    for (int ano : new int[] {2026, 2027}) {
      NotaFiscalItem item = itemBase();
      item.setAliquotaIbs(new BigDecimal("0.0010"));
      item.setValorIbs(new BigDecimal("1.00"));
      NotaFiscal nota = notaCom(item);
      // 31/12 23h em Brasilia = 01/01 02h UTC: vale o ano de Brasilia.
      nota.setDataEmissao(OffsetDateTime.of(ano + 1, 1, 1, 2, 0, 0, 0, ZoneOffset.UTC));
      nota.setValorTotalIbs(new BigDecimal("1.00"));
      nota.setValorTotalCbs(new BigDecimal("9.00"));

      NFe nfe = mapper.toNfe(nota);

      IbsCbs.GIbsCbs g = nfe.getInfNFe().getDet().get(0).getImposto().getIbsCbs().getGIBSCBS();
      IbsCbsTot tot = nfe.getInfNFe().getTotal().getIbsCbsTot();
      boolean dividido = ano >= 2027;
      assertThat(g.getGIBSUF().getVIBSUF()).as("ano %d", ano)
          .isEqualTo(dividido ? "0.50" : "1.00");
      assertThat(g.getGIBSMun().getVIBSMun()).as("ano %d", ano)
          .isEqualTo(dividido ? "0.50" : "0.00");
      assertThat(g.getGIBSMun().getPIBSMun()).as("ano %d", ano)
          .isEqualTo(dividido ? "0.0500" : "0.0000");
      assertThat(tot.getGIBS().getGIBSMun().getVIBSMun()).as("ano %d", ano)
          .isEqualTo(dividido ? "0.50" : "0.00");
    }
  }

  @Test
  @DisplayName(
      "dhEmi/dhSaiEnt com offset UTC devem serializar offset numerico, nunca o literal 'Z' — "
          + "XSD da NF-e (TDateTimeUTC) rejeita 'Z'")
  void shouldFormatUtcOffsetAsNumericNeverAsZLiteral() {
    // Simula o round-trip do timestamptz do Postgres, que sempre devolve OffsetDateTime
    // em UTC (offset zero) independente do offset gravado originalmente.
    NotaFiscalItem item = itemBase();
    NotaFiscal nota = notaCom(item);
    nota.setDataEmissao(OffsetDateTime.of(2026, 3, 10, 13, 0, 0, 0, ZoneOffset.UTC));
    nota.setDataSaida(OffsetDateTime.of(2026, 3, 10, 17, 0, 0, 0, ZoneOffset.UTC));

    NFe nfe = mapper.toNfe(nota);

    assertThat(nfe.getInfNFe().getIde().getDhEmi())
        .as("dhEmi nao pode conter o literal 'Z' — XSD exige offset numerico explicito")
        .doesNotContain("Z")
        .endsWith("+00:00");
    assertThat(nfe.getInfNFe().getIde().getDhSaiEnt())
        .as("dhSaiEnt nao pode conter o literal 'Z' — XSD exige offset numerico explicito")
        .doesNotContain("Z")
        .endsWith("+00:00");
  }


  @Test
  @DisplayName("XML carrega IE e endereco reais do emitente, com cMunFG do emitente (#88)")
  void xmlComEmitenteRealValidaXsdSemPlaceholders() throws Exception {
    NotaFiscal nota = notaCom(itemBase());
    nota.setProdutor(ProdutorTestFixtures.produtorCompleto());

    NFe nfe = mapper.toNfe(nota);

    Emit emit = nfe.getInfNFe().getEmit();
    EnderEmit end = emit.getEnderEmit();
    assertThat(emit.getIe()).isEqualTo(ProdutorTestFixtures.INSCRICAO_ESTADUAL);
    assertThat(end.getXLgr()).isEqualTo("Avenida Historiador Rubens de Mendonca");
    assertThat(end.getNro()).isEqualTo("1894");
    assertThat(end.getXBairro()).isEqualTo("Bosque da Saude");
    assertThat(end.getCMun()).isEqualTo(ProdutorTestFixtures.CODIGO_MUNICIPIO_IBGE);
    assertThat(end.getXMun()).isEqualTo("Cuiaba");
    assertThat(end.getUf()).isEqualTo("MT");
    assertThat(end.getCep()).isEqualTo("78050000");

    // cMunFG e o municipio do FATO GERADOR = o do EMITENTE, nao o do destinatario (MG).
    assertThat(nfe.getInfNFe().getIde().getCMunFG())
        .isEqualTo(ProdutorTestFixtures.CODIGO_MUNICIPIO_IBGE);

    // Nenhum placeholder textual pode sobrar no XML serializado. Sequencias numericas como
    // "0000000" nao entram nesta lista: a chave de acesso legitimamente zero-pada o nNF.
    String xml = new XmlGenerator().marshal(nfe);
    assertThat(xml)
        .doesNotContain("SEM ENDERECO")
        .doesNotContain("SEM LOGRADOURO")
        .doesNotContain("MUNICIPIO NAO INFORMADO")
        .doesNotContain("ISENTO");
    // A validacao contra o XSD 4.00 nao cabe aqui: o schema exige <Signature>, que so e
    // adicionada adiante no pipeline. Ela e coberta ponta a ponta por
    // GeracaoNotaFiscalServiceTest, que usa a mesma fixture de emitente.
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private NotaFiscalItem itemBase() {
    return NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(1)
        .descricao("Soja")
        .ncm("12019000")
        .cfop("6101")
        .unidade("KG")
        .quantidade(BigDecimal.ONE)
        .valorUnitario(new BigDecimal("1000.00"))
        .valorTotal(new BigDecimal("1000.00"))
        .baseCalculoIbsCbs(new BigDecimal("1000.00"))
        .aliquotaCbs(new BigDecimal("0.0090"))
        .valorCbs(new BigDecimal("9.00"))
        .cstIbsCbs("000")
        .cClassTrib("010100")
        .build();
  }

  private NotaFiscal notaCom(NotaFiscalItem item) {
    // Emitente completo (issue #88): IE e endereco reais sao pre-condicao da emissao.
    Produtor produtor = ProdutorTestFixtures.produtorCompleto();
    NotaFiscal nota =
        NotaFiscal.builder()
            .id(UUID.randomUUID())
            .numero(1L)
            .serie(1)
            .modelo("55")
            .naturezaOperacao("Venda de producao rural")
            .dataEmissao(OffsetDateTime.now())
            .produtor(produtor)
            .destinatarioNome("Comprador Teste")
            .destinatarioCpfCnpj("98765432100")
            .destinatarioEndereco(
                EnderecoDestinatario.builder()
                    .logradouro("Rua das Acacias")
                    .numero("250")
                    .uf("MG")
                    .municipio("Uberaba")
                    .codigoMunicipioIbge("3170206")
                    .build())
            .valorTotalProdutos(new BigDecimal("1000.00"))
            .valorTotalNota(new BigDecimal("1000.00"))
            .status(StatusNotaFiscal.RASCUNHO)
            .build();
    nota.addItem(item);
    return nota;
  }
}
