package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.mapper.AlertaTributarioMapper;
import br.com.agrocontabil.model.AlertaTributario;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusAlertaTributario;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.TipoAlertaTributario;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import br.com.agrocontabil.repository.AlertaTributarioRepository;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Regras do {@link AlertaTributarioService} (issue #140, RN010) sem banco. */
@ExtendWith(MockitoExtension.class)
class AlertaTributarioServiceTest {

  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
  private static final LocalDate HOJE = LocalDate.of(2026, 10, 14);

  @Mock private AlertaTributarioRepository alertaRepository;
  @Mock private GuiaFiscalRepository guiaRepository;
  @Mock private NotaFiscalRepository notaRepository;
  @Mock private AuthenticatedProdutorProvider produtorProvider;
  @Mock private br.com.agrocontabil.repository.ProdutorRepository produtorRepository;

  private AlertaTributarioService service;

  @BeforeEach
  void setUp() {
    // 23h50 de Brasilia ja e dia 15 em UTC: "hoje" tem de ser o de Brasilia.
    Clock clock = Clock.fixed(ZonedDateTime.of(HOJE.atTime(23, 50), ZONE).toInstant(), ZONE);
    service = new AlertaTributarioService(alertaRepository, guiaRepository, notaRepository,
        produtorRepository, produtorProvider, new AlertaTributarioMapper(),
        clock);
  }

  @Test
  @DisplayName("Janela de 'menos de 5 dias' e RN006 (emissao + 30) nos argumentos das consultas")
  void janelaEPrazoDeCancelamento() {
    service.gerarAlertas();

    verify(guiaRepository).findByStatusAndDataVencimentoBetween(
        StatusGuiaFiscal.PENDENTE, HOJE, HOJE.plusDays(4));
    // Prazo em [14/10, 18/10] = emissao em [14/09, 18/09], fim exclusivo em 19/09.
    verify(notaRepository).findPropriasAutorizadasEmitidasEntre(
        LocalDate.of(2026, 9, 14).atStartOfDay(ZONE).toOffsetDateTime(),
        LocalDate.of(2026, 9, 19).atStartOfDay(ZONE).toOffsetDateTime());
    // Dia 14: o mes fecha dia 31, fora da janela.
    verify(notaRepository, never()).findProdutoresComNotaAutorizadaEntre(any(), any());
  }

  @Test
  @DisplayName("Alerta ja existente nao e gravado de novo; o novo nasce ATIVO e sem PII")
  void idempotenteEDescricaoSemObservacao() {
    Produtor produtor = Produtor.builder().id(UUID.randomUUID()).build();
    GuiaFiscal jaAlertada = guia(produtor, HOJE.plusDays(1), "100.00");
    GuiaFiscal nova = guia(produtor, HOJE.plusDays(3), "1234.5");
    nova.setObservacoes("CPF 123.456.789-00 do socio, ligar antes");
    when(guiaRepository.findByStatusAndDataVencimentoBetween(any(), any(), any()))
        .thenReturn(List.of(jaAlertada, nova));
    when(alertaRepository.existsByTipoAndReferenciaIdAndDataLimite(
        TipoAlertaTributario.VENCIMENTO_GUIA, jaAlertada.getId(), HOJE.plusDays(1)))
        .thenReturn(true);

    AlertaTributarioService.ResumoAlertas resumo = service.gerarAlertas();

    ArgumentCaptor<AlertaTributario> salvo = ArgumentCaptor.forClass(AlertaTributario.class);
    verify(alertaRepository).save(salvo.capture());
    assertThat(salvo.getValue().getReferenciaId()).isEqualTo(nova.getId());
    assertThat(salvo.getValue().getStatus()).isEqualTo(StatusAlertaTributario.ATIVO);
    // A observacao livre do contador nunca vira descricao (chega a Ceres e ao modelo).
    assertThat(salvo.getValue().getDescricao()).isEqualTo("Guia de FUNRURAL de R$ 1.234,50")
        .doesNotContain("CPF", "socio");
    assertThat(resumo.criados()).isEqualTo(1);
  }

  @Test
  @DisplayName("Prazo de cancelamento: data = emissao (Brasilia) + 30; NFA rotulada como NFA")
  void prazoDeCancelamentoDaNfa() {
    UUID produtorId = UUID.randomUUID();
    UUID notaId = UUID.randomUUID();
    // 23h30 de Brasilia do dia 15/09 (ja 16/09 em UTC): o prazo conta do dia de Brasilia.
    OffsetDateTime emissao = OffsetDateTime.parse("2026-09-15T23:30:00-03:00");
    when(notaRepository.findPropriasAutorizadasEmitidasEntre(any(), any())).thenReturn(List.of(
        new br.com.agrocontabil.repository.PrazoCancelamentoCandidato(notaId, produtorId, 7L, 1,
            br.com.agrocontabil.model.TipoDocumentoFiscal.NFA, emissao)));
    when(produtorRepository.getReferenceById(produtorId))
        .thenReturn(Produtor.builder().id(produtorId).build());

    service.gerarAlertas();

    ArgumentCaptor<AlertaTributario> salvo = ArgumentCaptor.forClass(AlertaTributario.class);
    verify(alertaRepository).save(salvo.capture());
    assertThat(salvo.getValue().getDataLimite()).isEqualTo(LocalDate.of(2026, 10, 15));
    assertThat(salvo.getValue().getDescricao()).isEqualTo("Prazo para cancelar a NFA 7, série 1");
    assertThat(salvo.getValue().getProdutor().getId()).isEqualTo(produtorId);
  }

  @Test
  @DisplayName("Na semana do fechamento, quem tem nota autorizada no mes entra")
  void semanaDoFechamento() {
    Clock dia28 = Clock.fixed(
        ZonedDateTime.of(LocalDate.of(2026, 10, 28).atTime(9, 0), ZONE).toInstant(), ZONE);
    AlertaTributarioService noDia28 = new AlertaTributarioService(alertaRepository,
        guiaRepository, notaRepository, produtorRepository, produtorProvider,
        new AlertaTributarioMapper(), dia28);

    noDia28.gerarAlertas();

    // Candidatos pelas notas autorizadas do mes: o mes corrente nao tem linha de apuracao (#53).
    verify(notaRepository).findProdutoresComNotaAutorizadaEntre(
        LocalDate.of(2026, 10, 1).atStartOfDay(ZONE).toOffsetDateTime(),
        LocalDate.of(2026, 11, 1).atStartOfDay(ZONE).toOffsetDateTime());
    verify(alertaRepository).resolverObsoletos(
        any(LocalDate.class), org.mockito.ArgumentMatchers.eq("2026-10"),
        any(OffsetDateTime.class));
  }

  private static GuiaFiscal guia(Produtor produtor, LocalDate vencimento, String valor) {
    return GuiaFiscal.builder()
        .id(UUID.randomUUID())
        .produtor(produtor)
        .tipoGuia(TipoGuiaFiscal.FUNRURAL)
        .dataVencimento(vencimento)
        .valor(new BigDecimal(valor))
        .status(StatusGuiaFiscal.PENDENTE)
        .build();
  }
}
