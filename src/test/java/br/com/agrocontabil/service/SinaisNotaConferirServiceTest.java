package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.ItemParaSinal;
import br.com.agrocontabil.repository.NcmReferenciaRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.service.SinaisNotaConferirService.NotaParaSinal;
import br.com.agrocontabil.service.SinalNota.CodigoSinalNota;
import br.com.agrocontabil.service.SinalNota.Momento;
import br.com.agrocontabil.service.SinalNota.Motivo;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
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
      // descricao                        | cfop | ncm      | motivo
      "Servico de colheita de soja        | 5949 | 12019000 | CFOP_GENERICO",
      "Prestação de serviço - PLANTIO     | 6949 | 12019000 | CFOP_GENERICO",
      "Frete da carga                     | 5101 | NULO     | NCM_FORA_DA_REFERENCIA",
      "Aluguel de trator                  | 5101 | 87019000 | NCM_FORA_DA_REFERENCIA",
      "Hora máquina pulverização          | 5949 | NULO     | CFOP_GENERICO",
      "Mão-de-obra                        | 5949 | 12019000 | CFOP_GENERICO",
      "Fretes                             | 5949 | 12019000 | CFOP_GENERICO",
      "Operacao de plantio                | 5949 | 12019000 | CFOP_GENERICO",
      "Servico de pulverizacao            | 5949 | 12019000 | CFOP_GENERICO",
      "Semente de soja para plantio       | 5949 | 12019000 | NULO",
      "Milho para semeadura               | 5949 | NULO     | NULO",
      "Soja em grão                       | 5949 | 12019000 | NULO",
      "Soja - remessa para armazenagem    | 5949 | 12019000 | NULO",
      "Big bag para transporte            | 5949 | 12019000 | NULO",
      "Bomba de pulverização              | 5949 | 12019000 | NULO",
      "Servico de colheita                | 5101 | 12019000 | NULO",
      "Transportadora Silva soja          | 5101 | 10059010 | NULO",
      "NULO                               | 5949 | 12019000 | NULO"
  })
  @DisplayName("PRODUTO_PARECE_SERVICO, com o motivo")
  void produtoPareceServico(String descricao, String cfop, String ncm, Motivo motivo) {
    itens(item(1, descricao, cfop, ncm));

    assertThat(service.sinais(PRODUTOR, nota(StatusNotaFiscal.RASCUNHO, EMISSAO)))
        .extracting(SinalNota::motivo)
        .containsExactlyElementsOf(motivo == null ? List.of() : List.of(motivo));
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
        StatusNotaFiscal.AUTORIZADA, EMISSAO))).isEmpty();
    UUID outro = UUID.randomUUID();
    when(notas.findItensParaSinais(NOTA, outro)).thenReturn(List.of());
    assertThat(service.sinais(outro, nota(StatusNotaFiscal.RASCUNHO, EMISSAO))).isEmpty();
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
    SinaisNotaConferirService comHifen = new SinaisNotaConferirService(notas, referencia, CLOCK,
        List.of("Hora/Máquina"), List.of());
    assertThat(comHifen.temTermoDeServico("hora maquina do trator")).isTrue();
    assertThatThrownBy(() -> new SinaisNotaConferirService(notas, referencia, CLOCK,
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

  private NotaParaSinal nota(StatusNotaFiscal status, OffsetDateTime emissao) {
    return new NotaParaSinal(NOTA, OrigemNotaFiscal.PROPRIA, status, emissao);
  }

  private void itens(ItemParaSinal... itens) {
    when(notas.findItensParaSinais(NOTA, PRODUTOR)).thenReturn(List.of(itens));
  }

  private static ItemParaSinal item(int numero, String descricao, String cfop, String ncm) {
    return new ItemParaSinal(numero, descricao, cfop, ncm);
  }
}
