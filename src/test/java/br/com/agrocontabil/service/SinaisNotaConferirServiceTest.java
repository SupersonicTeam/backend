package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.repository.ItemParaSinal;
import br.com.agrocontabil.repository.NcmReferenciaRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.service.SinaisNotaConferirService.NotaParaSinal;
import br.com.agrocontabil.service.SinalNota.CodigoSinalNota;
import br.com.agrocontabil.service.SinalNota.Momento;
import br.com.agrocontabil.service.SinalNota.Motivo;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Sinais de nota a conferir (issue #276): tabela de casos, inclusive os que não podem disparar. */
class SinaisNotaConferirServiceTest {

  private static final UUID PRODUTOR = UUID.randomUUID();
  private static final UUID NOTA = UUID.randomUUID();
  private static final OffsetDateTime EMISSAO =
      OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.ofHours(-3));
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-02T15:00:00Z"), ZoneId.of("America/Sao_Paulo"));

  private NotaFiscalRepository notas;
  private NcmReferenciaRepository referencia;
  private ClassificacaoTributariaService classificacao;
  private SinaisNotaConferirService service;

  @BeforeEach
  void setUp() {
    notas = mock(NotaFiscalRepository.class);
    referencia = mock(NcmReferenciaRepository.class);
    classificacao = mock(ClassificacaoTributariaService.class);
    service = new SinaisNotaConferirService(notas, referencia, classificacao, CLOCK);
    when(referencia.existsByCodigo(anyString())).thenAnswer(i ->
        i.getArgument(0, String.class).equals("12019000")
            || i.getArgument(0, String.class).equals("10059010"));
  }

  @ParameterizedTest(name = "[{index}] {0} | CFOP {1} | NCM {2} | un {3} -> {4}")
  @CsvSource(delimiter = '|', nullValues = "NULO", value = {
      // descricao | cfop | ncm | unidade | motivo (unidade NULO = não informada)
      "Servico de colheita de soja | 5949 | 12019000 | NULO | CFOP_GENERICO",
      "Prestação de serviço - PLANTIO | 6949 | 12019000 | NULO | CFOP_GENERICO",
      "Frete da carga | 5101 | NULO | NULO | NCM_FORA_DA_REFERENCIA",
      "Aluguel de trator | 5101 | 87019000 | NULO | NCM_FORA_DA_REFERENCIA",
      "Hora máquina pulverização | 5949 | NULO | NULO | CFOP_GENERICO",
      "Mão-de-obra | 5949 | 12019000 | NULO | CFOP_GENERICO",
      "Fretes | 5949 | 12019000 | NULO | CFOP_GENERICO",
      "Operacao de plantio | 5949 | 12019000 | NULO | CFOP_GENERICO",
      "Servico de pulverizacao | 5949 | 12019000 | NULO | CFOP_GENERICO",
      "Semente de soja para plantio | 5949 | 12019000 | NULO | NULO",
      "Milho para semeadura | 5949 | NULO | NULO | NULO",
      "Soja em grão | 5949 | 12019000 | NULO | NULO",
      "Soja - remessa para armazenagem | 5949 | 12019000 | NULO | NULO",
      "Big bag para transporte | 5949 | 12019000 | NULO | NULO",
      "Bomba de pulverização | 5949 | 12019000 | NULO | NULO",
      "Servico de colheita | 5101 | 12019000 | NULO | NULO",
      "Transportadora Silva soja | 5101 | 10059010 | NULO | NULO",
      "NULO | 5949 | 12019000 | NULO | NULO",
      // #364: termos novos de serviço agrícola, cruzados com a unidade comercial e o NCM
      "Serviço de gradagem | 5101 | 12019000 | HA | UNIDADE_DE_SERVICO",
      "Colheita de soja | 5101 | 12019000 | HA | UNIDADE_DE_SERVICO",
      "Aração de área | 5101 | NULO | H | UNIDADE_DE_SERVICO",
      "Aplicação de adubo a lanço | 5101 | 31051000 | Hectare. | UNIDADE_DE_SERVICO",
      "Subsolagem | 5949 | 12019000 | KG | CFOP_GENERICO",
      "Destoca e desmatamento | 5101 | NULO | NULO | NCM_FORA_DA_REFERENCIA",
      "Secagem de grãos | 5101 | NULO | SC | NCM_FORA_DA_REFERENCIA",
      "Secagem de grãos | 5101 | 12019000 | HORA | UNIDADE_DE_SERVICO",
      "Calagem do talhão 4 | 5101 | NULO | NULO | NCM_FORA_DA_REFERENCIA",
      "Ensilagem de milho | 5949 | NULO | NULO | CFOP_GENERICO",
      // mercadoria que carrega o nome da operação: unidade de produto + NCM de mercadoria
      "Milho seco e limpo | 5101 | 10059010 | KG | NULO",
      "Milho seco e limpo | 5949 | 10059010 | SC | NULO",
      "Bico de pulverização | 5949 | NULO | UN | NULO",
      "Bico de pulverização | 5101 | 84248990 | UN | NULO",
      "Bag de colheita | 5949 | 39232990 | UN | NULO",
      "Adubo de plantio | 5949 | 31051000 | SC | NULO",
      "Calcário agrícola para calagem | 5949 | 25210000 | TON | NULO",
      "Semente de soja para plantio | 5949 | 12019000 | SC | NULO",
      // termo forte (serviço, frete, aluguel...) não é desfeito pela unidade de produto
      "Aluguel de trator | 5101 | 87019000 | UN | NCM_FORA_DA_REFERENCIA",
      "Serviço de colheita de soja | 5949 | 12019000 | UN | CFOP_GENERICO",
      // termo ambíguo sem NCM de mercadoria segue avisando, com ou sem unidade de produto
      "Colheita mecanizada | 5101 | NULO | NULO | NCM_FORA_DA_REFERENCIA",
      "Pulverização aérea | 5101 | NULO | UNIDADE | NCM_FORA_DA_REFERENCIA"
  })
  @DisplayName("PRODUTO_PARECE_SERVICO, com o motivo")
  void produtoPareceServico(String descricao, String cfop, String ncm, String unidade,
      Motivo motivo) {
    itens(comUnidade(item(1, descricao, cfop, ncm), unidade));

    assertThat(service.sinais(PRODUTOR, nota(StatusNotaFiscal.RASCUNHO, EMISSAO)))
        .extracting(SinalNota::motivo)
        .containsExactlyElementsOf(motivo == null ? List.of() : List.of(motivo));
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', nullValues = "NULO", value = {
      "ha|SERVICO", "HA.|SERVICO", "Hectare|SERVICO", "h|SERVICO", "Hora|SERVICO",
      "HRS|SERVICO", "diária|SERVICO", "DIARIA|SERVICO", "serv|SERVICO", "SV|SERVICO",
      "kg|PRODUTO", "SC|PRODUTO", "Saca|PRODUTO", "L|PRODUTO", "litro|PRODUTO", "Un.|PRODUTO",
      "UNIDADE|PRODUTO", "TON|PRODUTO", "CX|PRODUTO", "xyz|DESCONHECIDA", "NULO|DESCONHECIDA",
      "' '|DESCONHECIDA"})
  @DisplayName("#364: a unidade comercial aponta serviço, produto ou nada")
  void unidadeComercial(String unidade, String esperado) {
    assertThat(SinaisNotaConferirService.apontaPara(unidade == null ? null : unidade.replace("'", "")))
        .hasToString(esperado);
  }

  @Test
  @DisplayName("sinal traz item e CFOP; nota de compra ou de outro produtor (sem itens): nada")
  void escopo() {
    itens(item(1, "Soja", "5101", "12019000"), item(2, "Frete", "5949", "12019000"));
    assertThat(service.sinais(PRODUTOR, nota(StatusNotaFiscal.RASCUNHO, EMISSAO)))
        .singleElement().satisfies(s -> {
          assertThat(s.codigo()).isEqualTo(CodigoSinalNota.PRODUTO_PARECE_SERVICO);
          assertThat(s.momento()).isEqualTo(Momento.ANTES_DE_EMITIR);
          assertThat(s.numeroItem()).isEqualTo(2);
          assertThat(s.descricaoItem()).isEqualTo("Frete");
          assertThat(s.cfop()).isEqualTo("5949");
          assertThat(s.prazoCancelamento()).isNull();
        });

    assertThat(service.sinais(PRODUTOR, new NotaParaSinal(NOTA, OrigemNotaFiscal.IMPORTADA,
        StatusNotaFiscal.AUTORIZADA, EMISSAO, null, null))).isEmpty();
    UUID outro = UUID.randomUUID();
    when(notas.findItensParaSinais(NOTA, outro)).thenReturn(List.of());
    assertThat(service.sinais(outro, nota(StatusNotaFiscal.RASCUNHO, EMISSAO))).isEmpty();
  }

  @Test
  @DisplayName("ICMS_A_DEFINIR: item sem CST fora da venda interna a contribuinte, antes de emitir")
  void icmsADefinir() {
    itens(itemIcms(1, "5101", null, "MT", "133518276"), // padrao: 51, sem sinal
        itemIcms(2, "6101", null, "PR", "9071425318"), // interestadual sem CST
        itemIcms(3, "5101", null, "MT", null), // destinatario sem IE
        itemIcms(4, "6101", "41", "PR", "9071425318")); // informado

    assertThat(service.sinais(PRODUTOR, nota(StatusNotaFiscal.RASCUNHO, EMISSAO)))
        .filteredOn(s -> s.codigo() == CodigoSinalNota.ICMS_A_DEFINIR)
        .extracting(SinalNota::numeroItem, SinalNota::motivo, SinalNota::momento)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(2, Motivo.SEM_CST_ICMS, Momento.ANTES_DE_EMITIR),
            org.assertj.core.groups.Tuple.tuple(3, Motivo.SEM_CST_ICMS, Momento.ANTES_DE_EMITIR));
  }

  @Test
  @DisplayName("ICMS_PADRAO_A_CONFIRMAR: 51 do padrao fora de MT + soja, sem bloquear; so antes do XML")
  void icmsPadraoAConfirmar() {
    itens(new ItemParaSinal(1, "Soja em grao", "5101", "12019000", "KG", java.math.BigDecimal.ONE,
            null, null, TipoProdutor.PESSOA_FISICA, RegimeTributario.REAL, "MT", "MT", "1335", null),
        new ItemParaSinal(2, "Milho em grao", "5101", "10059010", "KG", java.math.BigDecimal.ONE,
            null, null, TipoProdutor.PESSOA_FISICA, RegimeTributario.REAL, "MT", "MT", "1335", null),
        new ItemParaSinal(3, "Milho em grao", "5101", "10059010", "KG", java.math.BigDecimal.ONE,
            null, "51", TipoProdutor.PESSOA_FISICA, RegimeTributario.REAL, "MT", "MT", "1335", null));

    assertThat(service.sinais(PRODUTOR, nota(StatusNotaFiscal.AGUARDANDO_APROVACAO, EMISSAO)))
        .extracting(SinalNota::codigo, SinalNota::numeroItem, SinalNota::motivo)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(
            CodigoSinalNota.ICMS_PADRAO_A_CONFIRMAR, 2, Motivo.PADRAO_NAO_CONFIRMADO));
    assertThat(service.sinais(PRODUTOR, nota(StatusNotaFiscal.XML_GERADO, EMISSAO))).isEmpty();
  }

  @Test
  @DisplayName("ICMS_A_DEFINIR so antes do XML: gerado, rejeitado ou autorizado, nada a combinar")
  void icmsADefinirSoAntesDoXml() {
    itens(itemIcms(1, "6101", null, "PR", "9071425318"),
        itemIcms(2, "7101", "40", "EX", null)); // exportacao com CST errado tambem barra

    for (StatusNotaFiscal status : List.of(StatusNotaFiscal.XML_GERADO,
        StatusNotaFiscal.REJEITADA, StatusNotaFiscal.AUTORIZADA)) {
      assertThat(service.sinais(PRODUTOR, nota(status, EMISSAO))).as("%s", status).isEmpty();
    }
    for (StatusNotaFiscal status : List.of(StatusNotaFiscal.RASCUNHO,
        StatusNotaFiscal.DEVOLVIDA, StatusNotaFiscal.AGUARDANDO_APROVACAO)) {
      assertThat(service.sinais(PRODUTOR, nota(status, EMISSAO))).as("%s", status)
          .extracting(SinalNota::numeroItem).containsExactly(1, 2);
    }
  }

  @ParameterizedTest
  @EnumSource(value = StatusNotaFiscal.class,
      names = {"ENVIANDO", "PENDENTE_SEFAZ", "CANCELANDO", "CANCELADA"})
  @DisplayName("em transito, pendente na SEFAZ ou cancelada: sem sinal (nada a fazer agora)")
  void semSinalPelaSituacao(StatusNotaFiscal status) {
    itens(item(1, "Frete", "5949", "12019000"));

    assertThat(service.sinais(PRODUTOR, nota(status, EMISSAO))).isEmpty();
  }

  @Test
  @DisplayName("autorizada: no prazo traz o instante-limite (emissao + 30 dias, com a hora); fora, nao")
  void prazoCancelamento() {
    itens(item(1, "Frete", "5949", "12019000"));
    OffsetDateTime emissao = OffsetDateTime.of(2026, 10, 20, 10, 0, 0, 0, ZoneOffset.ofHours(-3));

    SinalNota noPrazo = service.sinais(PRODUTOR, nota(StatusNotaFiscal.AUTORIZADA, emissao)).get(0);
    assertThat(noPrazo.momento()).isEqualTo(Momento.AUTORIZADA_NO_PRAZO);
    assertThat(noPrazo.prazoCancelamento()).isEqualTo(emissao.plusDays(30));

    SinalNota fora = service.sinais(PRODUTOR, nota(StatusNotaFiscal.AUTORIZADA,
        OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.ofHours(-3)))).get(0);
    assertThat(fora.momento()).isEqualTo(Momento.AUTORIZADA_FORA_DO_PRAZO);
    assertThat(fora.prazoCancelamento()).isNull();
  }

  @Test
  @DisplayName("listas: pontuacao e acento nas entradas casam; lista de termos vazia recusa subir")
  void listas() {
    SinaisNotaConferirService comHifen = new SinaisNotaConferirService(notas, referencia, classificacao, CLOCK,
        List.of("Hora/Máquina"), List.of());
    assertThat(comHifen.temTermoDeServico("hora maquina do trator")).isTrue();
    assertThatThrownBy(() -> new SinaisNotaConferirService(notas, referencia, classificacao, CLOCK,
        List.of(" ", "--"), List.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("termo de servico conferido antes da referencia de NCM (sem consulta a toa)")
  void semConsultaSemTermo() {
    itens(item(1, "Soja", "5101", "99999999"), item(2, "Frete", "5949", "99999999"));

    service.sinais(PRODUTOR, nota(StatusNotaFiscal.RASCUNHO, EMISSAO));

    verify(referencia, never()).existsByCodigo(anyString());
  }

  @ParameterizedTest
  @CsvSource(value = {"5101|true", "6102|true", "7101|true", "5901|false", "5949|false",
      " 5101 |true", "51|false"}, delimiter = '|', ignoreLeadingAndTrailingWhitespace = false)
  @DisplayName("CFOP de venda e x1xx; remessa nao e venda")
  void deVenda(String cfop, boolean venda) {
    assertThat(SinaisNotaConferirService.deVenda(item(1, "x", cfop, "1"))).isEqualTo(venda);
  }

  @Test
  @DisplayName("mediana: o do meio, ou a media dos dois do meio")
  void mediana() {
    assertThat(SinaisNotaConferirService.mediana(List.of(
        new java.math.BigDecimal("1"), new java.math.BigDecimal("2"),
        new java.math.BigDecimal("9")))).isEqualByComparingTo("2");
    assertThat(SinaisNotaConferirService.mediana(List.of(
        new java.math.BigDecimal("1"), new java.math.BigDecimal("2"),
        new java.math.BigDecimal("3"), new java.math.BigDecimal("10"))))
        .isEqualByComparingTo("2.5");
  }

  private NotaParaSinal nota(StatusNotaFiscal status, OffsetDateTime emissao) {
    return new NotaParaSinal(NOTA, OrigemNotaFiscal.PROPRIA, status, emissao, null, null);
  }

  @Test
  @DisplayName("CCLASSTRIB_NAO_ADMITIDO: cClassTrib que o NCM nao admite avisa antes do XML (#389)")
  void cClassTribNaoAdmitido() {
    ItemParaSinal soja = comCClassTrib(item(1, "Soja em grao", "5101", "12019000"), "200009");
    ItemParaSinal milho = comCClassTrib(item(2, "Milho em grao", "5101", "10059010"), "200003");
    ItemParaSinal semCodigo = item(3, "Soja em grao", "5101", "12019000");
    itens(soja, milho, semCodigo);
    when(classificacao.codigoNaoAdmitido(eq("200009"), eq("12019000"), any(LocalDate.class)))
        .thenReturn(Optional.of(new ClassificacaoTributariaService.CodigoNaoAdmitido(null,
            List.of("200036"))));
    when(classificacao.codigoNaoAdmitido(eq("200003"), eq("10059010"), any(LocalDate.class)))
        .thenReturn(Optional.empty());

    assertThat(service.sinais(PRODUTOR, nota(StatusNotaFiscal.RASCUNHO, EMISSAO)))
        .extracting(SinalNota::codigo, SinalNota::numeroItem, SinalNota::motivo)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(
            CodigoSinalNota.CCLASSTRIB_NAO_ADMITIDO, 1, Motivo.CODIGO_NAO_VALE_PARA_O_NCM));
    // Com o XML gerado a nota nao e editavel: sem o aviso "antes de emitir".
    assertThat(service.sinais(PRODUTOR, nota(StatusNotaFiscal.XML_GERADO, EMISSAO)))
        .extracting(SinalNota::codigo)
        .doesNotContain(CodigoSinalNota.CCLASSTRIB_NAO_ADMITIDO);
  }

  @Test
  @DisplayName("avisosDoRascunho: so o cClassTrib nao admitido, sem tocar nas outras regras (#389)")
  void avisosDoRascunho() {
    NotaFiscal nota = NotaFiscal.builder().origem(OrigemNotaFiscal.PROPRIA)
        .dataEmissao(EMISSAO).build();
    nota.addItem(NotaFiscalItem.builder().numeroItem(1).descricao("Soja").ncm("12019000")
        .cClassTrib("200009").build());
    nota.addItem(NotaFiscalItem.builder().numeroItem(2).descricao("Milho").ncm("10059010")
        .build());
    when(classificacao.codigoNaoAdmitido(eq("200009"), eq("12019000"), any(LocalDate.class)))
        .thenReturn(Optional.of(new ClassificacaoTributariaService.CodigoNaoAdmitido(null,
            List.of("200036"))));

    assertThat(service.avisosDoRascunho(nota)).extracting(SinalNota::numeroItem)
        .containsExactly(1);
    // Nenhuma consulta de nota, de item ou de referencia: e a checagem barata.
    verifyNoInteractions(notas);

    nota.setOrigem(OrigemNotaFiscal.IMPORTADA);
    assertThat(service.avisosDoRascunho(nota)).isEmpty();
  }

  private static ItemParaSinal comCClassTrib(ItemParaSinal i, String cClassTrib) {
    return new ItemParaSinal(i.numeroItem(), i.descricao(), i.cfop(), i.ncm(), i.unidade(),
        i.quantidade(), i.valorUnitario(), i.cstIcms(), i.tipoEmitente(), i.regimeEmitente(),
        i.ufEmitente(), i.ufDestinatario(), i.ieDestinatario(), cClassTrib);
  }

  private void itens(ItemParaSinal... itens) {
    when(notas.findItensParaSinais(NOTA, PRODUTOR)).thenReturn(List.of(itens));
  }

  /** Item com o CST do ICMS informado: as regras que não olham o ICMS não veem o sinal dele. */
  private static ItemParaSinal item(int numero, String descricao, String cfop, String ncm) {
    return new ItemParaSinal(numero, descricao, cfop, ncm, "KG", java.math.BigDecimal.ONE, null,
        "41", TipoProdutor.PESSOA_FISICA, RegimeTributario.REAL, "MT", "PR", "9071425318", null);
  }

  private static ItemParaSinal comUnidade(ItemParaSinal i, String unidade) {
    return new ItemParaSinal(i.numeroItem(), i.descricao(), i.cfop(), i.ncm(), unidade,
        i.quantidade(), i.valorUnitario(), i.cstIcms(), i.tipoEmitente(), i.regimeEmitente(),
        i.ufEmitente(), i.ufDestinatario(), i.ieDestinatario(), i.cClassTrib());
  }

  private static ItemParaSinal itemIcms(int numero, String cfop, String cstIcms, String ufDestino,
      String ieDestino) {
    return new ItemParaSinal(numero, "Soja em grao", cfop, "12019000", "KG",
        java.math.BigDecimal.ONE, null, cstIcms, TipoProdutor.PESSOA_FISICA,
        RegimeTributario.REAL, "MT", ufDestino, ieDestino, null);
  }
}
