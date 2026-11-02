package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NcmReferenciaRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.service.SinalNota.CodigoSinalNota;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Sinais de nota a conferir (issue #276): tabela de casos, inclusive os que não podem disparar. */
class SinaisNotaConferirServiceTest {

  private static final UUID PRODUTOR = UUID.randomUUID();
  private static final UUID NOTA = UUID.randomUUID();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-02T15:00:00Z"), ZoneId.of("America/Sao_Paulo"));

  private NotaFiscalRepository notas;
  private NcmReferenciaRepository referencia;
  private SinaisNotaConferirService service;

  @BeforeEach
  void setUp() {
    notas = mock(NotaFiscalRepository.class);
    referencia = mock(NcmReferenciaRepository.class);
    service = new SinaisNotaConferirService(notas, referencia, CLOCK);
    when(referencia.existsByCodigo(anyString())).thenAnswer(i ->
        i.getArgument(0, String.class).equals("12019000")
            || i.getArgument(0, String.class).equals("10059010"));
  }

  @ParameterizedTest(name = "[{index}] {0} | CFOP {1} | NCM {2} -> {3}")
  @CsvSource(delimiter = '|', nullValues = "NULO", value = {
      // descricao                        | cfop | ncm      | dispara
      "Servico de colheita de soja        | 5949 | 12019000 | true",
      "Prestação de serviço - PLANTIO     | 6949 | 12019000 | true",
      "Frete da carga                     | 5101 | NULO     | true",
      "Aluguel de trator                  | 5101 | 87019000 | true",
      "Hora máquina pulverização          | 5949 | NULO     | true",
      "Mão de obra                        | 5949 | 12019000 | true",
      "Semente de soja para plantio       | 5949 | 12019000 | false",
      "Milho para semeadura               | 5949 | NULO     | false",
      "Soja em grão                       | 5949 | 12019000 | false",
      "Servico de colheita                | 5101 | 12019000 | false",
      "Transportadora Silva soja          | 5101 | 10059010 | false",
      "Fretes                             | 5949 | 12019000 | false",
      "NULO                               | 5949 | 12019000 | false"
  })
  @DisplayName("PRODUTO_PARECE_SERVICO")
  void produtoPareceServico(String descricao, String cfop, String ncm, boolean dispara) {
    nota(OrigemNotaFiscal.PROPRIA, StatusNotaFiscal.RASCUNHO, item(1, descricao, cfop, ncm));

    assertThat(service.sinais(PRODUTOR, NOTA)).hasSize(dispara ? 1 : 0);
  }

  @Test
  @DisplayName("sinal traz item, CFOP e NCM; nota de compra, de outro produtor ou inexistente: nada")
  void escopo() {
    nota(OrigemNotaFiscal.PROPRIA, StatusNotaFiscal.RASCUNHO,
        item(2, "Frete", "5949", "12019000"), item(1, "Soja", "5101", "12019000"));
    assertThat(service.sinais(PRODUTOR, NOTA)).singleElement().satisfies(s -> {
      assertThat(s.codigo()).isEqualTo(CodigoSinalNota.PRODUTO_PARECE_SERVICO);
      assertThat(s.numeroItem()).isEqualTo(2);
      assertThat(s.descricaoItem()).isEqualTo("Frete");
      assertThat(s.cfop()).isEqualTo("5949");
      assertThat(s.prazoCancelamento()).isNull();
    });

    nota(OrigemNotaFiscal.IMPORTADA, StatusNotaFiscal.AUTORIZADA,
        item(1, "Frete", "5949", "12019000"));
    assertThat(service.sinais(PRODUTOR, NOTA)).isEmpty();

    when(notas.findByIdAndProdutorIdComGrafoDeEmissao(any(), any())).thenReturn(Optional.empty());
    assertThat(service.sinais(UUID.randomUUID(), NOTA)).isEmpty();
  }

  @Test
  @DisplayName("nota autorizada no prazo traz o ultimo dia para cancelar (RN006); fora do prazo, nao")
  void prazoCancelamento() {
    NotaFiscal autorizada = nota(OrigemNotaFiscal.PROPRIA, StatusNotaFiscal.AUTORIZADA,
        item(1, "Frete", "5949", "12019000"));
    autorizada.setDataEmissao(OffsetDateTime.of(2026, 10, 20, 10, 0, 0, 0, ZoneOffset.ofHours(-3)));
    assertThat(service.sinais(PRODUTOR, NOTA).get(0).prazoCancelamento())
        .isEqualTo(LocalDate.of(2026, 11, 19));

    autorizada.setDataEmissao(OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.ofHours(-3)));
    assertThat(service.sinais(PRODUTOR, NOTA).get(0).prazoCancelamento()).isNull();
  }

  private NotaFiscal nota(OrigemNotaFiscal origem, StatusNotaFiscal status,
      NotaFiscalItem... itens) {
    NotaFiscal nota = NotaFiscal.builder().origem(origem).status(status)
        .dataEmissao(OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.ofHours(-3)))
        .build();
    for (NotaFiscalItem item : itens) {
      nota.addItem(item);
    }
    when(notas.findByIdAndProdutorIdComGrafoDeEmissao(NOTA, PRODUTOR))
        .thenReturn(Optional.of(nota));
    return nota;
  }

  private static NotaFiscalItem item(int numero, String descricao, String cfop, String ncm) {
    return NotaFiscalItem.builder().numeroItem(numero).descricao(descricao).cfop(cfop).ncm(ncm)
        .build();
  }
}
