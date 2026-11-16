package br.com.agrocontabil.service;

import static br.com.agrocontabil.support.DadosCeresFixture.saida;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.service.SinaisNotaConferirService.NotaParaSinal;
import br.com.agrocontabil.service.SinalNota.CodigoSinalNota;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Teste diferencial dos sinais em lote (issue #579): para as mesmas notas, {@code sinaisEmLote}
 * tem de devolver exatamente o que {@code sinais} devolve, nota a nota. É o que separa "a mesma
 * regra, lida de outro jeito" de "refatorei e mudei a regra": há ao menos um caso de cada sinal,
 * nota de outro produtor, cancelada, importada e fora do prazo, e os limites do lote (tamanho do
 * lote e teto da referência) são baixados para exercitar a divisão em partes e a leitura por nota.
 *
 * <p>As notas do seed da demonstração (os rascunhos de milho a destinatário sem IE, que dão o
 * {@code ICMS_A_DEFINIR} da banca) não são repetidas aqui: o {@code
 * ResumoNoSeedDemonstracaoIntegrationTest} as exercita pelo resumo, que passa pelo lote.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Sinais em lote: o mesmo resultado da nota avulsa (#579)")
class SinaisEmLoteIntegrationTest {

  private static final ZoneId BRT = ZoneId.of("America/Sao_Paulo");

  @Autowired private SinaisNotaConferirService service;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private TestDatabaseCleaner cleaner;

  private Produtor produtorA;
  private Produtor produtorB;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    produtorA = produtorRepository.save(Produtor.builder().nomeCompleto("Fazenda A")
        .cpfCnpj("98765432100").estado("MT").tipoProdutor(TipoProdutor.PESSOA_FISICA).build());
    produtorB = produtorRepository.save(Produtor.builder().nomeCompleto("Fazenda B")
        .cpfCnpj("11122233344").estado("PR").tipoProdutor(TipoProdutor.PESSOA_FISICA).build());
  }

  @Test
  @DisplayName("um caso de cada sinal e as notas sem sinal: lote = avulsa")
  void igualAAvulsa() {
    List<NotaParaSinal> notas = cenario();

    Map<UUID, List<SinalNota>> lote = service.sinaisEmLote(produtorA.getId(), notas);

    assertIgual(notas, lote);
    // O cenario cobre todos os sinais: um sinal novo sem caso aqui reprova o teste.
    Set<CodigoSinalNota> vistos = EnumSet.noneOf(CodigoSinalNota.class);
    lote.values().forEach(sinais -> sinais.forEach(s -> vistos.add(s.codigo())));
    assertThat(vistos).containsExactlyInAnyOrder(CodigoSinalNota.values());
    // E ha notas sem sinal, que nao podem ganhar um.
    assertThat(lote.values()).anyMatch(List::isEmpty);
  }

  @Test
  @DisplayName("lote dividido em partes de 3 notas: o mesmo resultado")
  void divididoEmPartes() {
    List<NotaParaSinal> notas = cenario();
    service.notasPorLote = 3;
    try {
      assertIgual(notas, service.sinaisEmLote(produtorA.getId(), notas));
    } finally {
      service.notasPorLote = SinaisNotaConferirService.NOTAS_POR_LOTE;
    }
  }

  @Test
  @DisplayName("referencia acima do teto do lote: lida por nota, o mesmo resultado")
  void referenciaTruncada() {
    List<NotaParaSinal> notas = cenario();
    service.tetoReferenciaEmLote = 2;
    try {
      assertIgual(notas, service.sinaisEmLote(produtorA.getId(), notas));
    } finally {
      service.tetoReferenciaEmLote = SinaisNotaConferirService.TETO_REFERENCIA_EM_LOTE;
    }
  }

  @Test
  @DisplayName("referencia cortada pelo teto: serve a nota com as linhas do limite, a outra le as suas")
  void referenciaTruncadaComLinhasSuficientes() {
    // 205 vendas de milho de 100/kg (mais antigas que as candidatas), em dias distintos.
    for (int i = 1; i <= 205; i++) {
      NotaFiscal venda = saida(produtorA, diasAtras(20 + i), "Milho em grao", "10059010",
          "10000.00");
      venda.getItens().get(0).setValorUnitario(BigDecimal.valueOf(100 + i % 7));
      notaFiscalRepository.save(venda);
    }
    NotaFiscal recente = rascunho(saida(produtorA, diasAtras(1), "Milho em grao", "10059010",
        "145000.00"));
    NotaFiscal antiga = rascunho(saida(produtorA, diasAtras(215), "Milho em grao", "10059010",
        "145000.00"));
    List<NotaParaSinal> notas = List.of(paraSinal(recente), paraSinal(antiga));
    service.tetoReferenciaEmLote = 203;
    try {
      Map<UUID, List<SinalNota>> lote = service.sinaisEmLote(produtorA.getId(), notas);
      assertIgual(notas, lote);
      // Nao e um caso vazio: o preco de 1450 contra ~100 e sinal nas duas.
      assertThat(lote.get(recente.getId())).extracting(SinalNota::codigo)
          .contains(CodigoSinalNota.VALOR_UNITARIO_FORA_PADRAO);
      assertThat(lote.get(antiga.getId())).extracting(SinalNota::codigo)
          .contains(CodigoSinalNota.VALOR_UNITARIO_FORA_PADRAO);
    } finally {
      service.tetoReferenciaEmLote = SinaisNotaConferirService.TETO_REFERENCIA_EM_LOTE;
    }
  }

  @Test
  @DisplayName("lote vazio devolve vazio")
  void vazio() {
    assertThat(service.sinaisEmLote(produtorA.getId(), List.of())).isEmpty();
  }

  private void assertIgual(List<NotaParaSinal> notas, Map<UUID, List<SinalNota>> lote) {
    assertThat(lote.keySet()).containsExactlyElementsOf(notas.stream().map(NotaParaSinal::id)
        .toList());
    for (NotaParaSinal nota : notas) {
      assertThat(lote.get(nota.id()))
          .as("sinais da nota %s (%s)", nota.id(), nota.status())
          .isEqualTo(service.sinais(produtorA.getId(), nota));
    }
  }

  /** As notas de A (e uma de B, que a avulsa e o lote ignoram igual), com um caso de cada sinal. */
  private List<NotaParaSinal> cenario() {
    List<NotaParaSinal> notas = new ArrayList<>();
    // Referencia do valor: 6 vendas de milho a 145 nos ultimos meses (nao candidatas).
    for (int i = 1; i <= 6; i++) {
      notaFiscalRepository.save(saida(produtorA, diasAtras(35 + 10 * i), "Milho em grao",
          "10059010", "145.00"));
    }
    // Vendas de B com outro preco: nunca entram na referencia de A.
    for (int i = 1; i <= 6; i++) {
      notaFiscalRepository.save(saida(produtorB, diasAtras(35 + 10 * i), "Milho em grao",
          "10059010", "145000.00"));
    }
    // VALOR_UNITARIO_FORA_PADRAO.
    notas.add(paraSinal(rascunho(saida(produtorA, diasAtras(1), "Milho em grao", "10059010",
        "145000.00"))));
    // A janela e de cada nota: a de 50 dias atras ve 5 referencias (sinal), a de 70 ve so 3 (nada),
    // embora a uniao das janelas tenha as 6.
    notas.add(paraSinal(notaFiscalRepository.save(saida(produtorA, diasAtras(50),
        "Milho em grao", "10059010", "145000.00"))));
    notas.add(paraSinal(notaFiscalRepository.save(saida(produtorA, diasAtras(70),
        "Milho em grao", "10059010", "145000.00"))));
    // PRODUTO_PARECE_SERVICO (CFOP generico).
    NotaFiscal servico = saida(produtorA, diasAtras(2), "Servico de colheita", "12019000",
        "800.00");
    servico.getItens().get(0).setCfop("5949");
    notas.add(paraSinal(rascunho(servico)));
    // POSSIVEL_DUPLICIDADE: duas notas iguais no mesmo dia, uma autorizada e outra rascunho.
    OffsetDateTime dia = diasAtras(3);
    notas.add(paraSinal(notaFiscalRepository.save(saida(produtorA, dia, "Soja em grao",
        "12019000", "5000.00"))));
    notas.add(paraSinal(rascunho(saida(produtorA, dia.plusHours(2), "Soja em grao", "12019000",
        "5000.00"))));
    // Mesmo destinatario, mesmo dia e outro valor: nao e duplicidade.
    notas.add(paraSinal(rascunho(saida(produtorA, dia.plusHours(1), "Soja em grao", "12019000",
        "5001.00"))));
    // ICMS_A_DEFINIR: sem CST, destinatario sem IE.
    NotaFiscal semCst = saida(produtorA, diasAtras(4), "Milho em grao", "10059010", "145.00");
    semCst.getItens().get(0).setCstIcms(null);
    semCst.getItens().get(0).setCfop("5101");
    notas.add(paraSinal(rascunho(semCst)));
    // ICMS_PADRAO_A_CONFIRMAR: sem CST, venda interna a destinatario com IE, par fora da lista.
    NotaFiscal padrao = saida(produtorA, diasAtras(5), "Milho em grao", "10059010", "145.00");
    padrao.getItens().get(0).setCstIcms(null);
    padrao.getItens().get(0).setCfop("5101");
    padrao.setDestinatarioIe("123456789");
    notas.add(paraSinal(rascunho(padrao)));
    // CCLASSTRIB_NAO_ADMITIDO: cClassTrib de medicamento na soja.
    NotaFiscal codigo = saida(produtorA, diasAtras(6), "Soja em grao", "12019000", "900.00");
    codigo.getItens().get(0).setCClassTrib("200009");
    notas.add(paraSinal(rascunho(codigo)));
    // Sem sinal: autorizada no prazo, devolvida limpa, fora do prazo, cancelada, importada.
    notas.add(paraSinal(notaFiscalRepository.save(saida(produtorA, diasAtras(7), "Algodao",
        "52010020", "700.00"))));
    notas.add(paraSinal(devolvida(saida(produtorA, diasAtras(8), "Trigo", "10019900",
        "300.00"))));
    notas.add(paraSinal(notaFiscalRepository.save(saida(produtorA, diasAtras(60), "Soja em grao",
        "12019000", "5000.00"))));
    NotaFiscal cancelada = saida(produtorA, diasAtras(9), "Soja em grao", "12019000", "5000.00");
    cancelada.setStatus(StatusNotaFiscal.CANCELADA);
    notas.add(paraSinal(notaFiscalRepository.save(cancelada)));
    NotaFiscal importada = saida(produtorA, diasAtras(10), "Servico de frete", "12019000",
        "100.00");
    importada.setOrigem(OrigemNotaFiscal.IMPORTADA);
    notas.add(paraSinal(notaFiscalRepository.save(importada)));
    // Nota de B pedida como se fosse de A: sem itens para A, sem sinal nos dois caminhos.
    notas.add(paraSinal(rascunho(saida(produtorB, diasAtras(1), "Milho em grao", "10059010",
        "145000.00"))));
    return notas;
  }

  private NotaFiscal rascunho(NotaFiscal nota) {
    nota.setStatus(StatusNotaFiscal.RASCUNHO);
    return notaFiscalRepository.save(nota);
  }

  private NotaFiscal devolvida(NotaFiscal nota) {
    nota.setStatus(StatusNotaFiscal.DEVOLVIDA);
    return notaFiscalRepository.save(nota);
  }

  private static NotaParaSinal paraSinal(NotaFiscal nota) {
    return new NotaParaSinal(nota.getId(), nota.getOrigem(), nota.getStatus(),
        nota.getDataEmissao(), nota.getDestinatarioCpfCnpj(), nota.getValorTotalNota());
  }

  private static OffsetDateTime diasAtras(int dias) {
    return OffsetDateTime.now(BRT).truncatedTo(ChronoUnit.DAYS).withHour(10).minusDays(dias);
  }
}
