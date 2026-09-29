package br.com.agrocontabil.assistente.ferramentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.ContextoAssistenteAusenteException;
import br.com.agrocontabil.assistente.FonteDado;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Apuracao;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.ComprasInsumo;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Creditos;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Notas;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Vencimentos;
import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import br.com.agrocontabil.repository.CompraInsumoAgregado;
import br.com.agrocontabil.repository.CreditoSaldoAgregado;
import br.com.agrocontabil.repository.NotaResumo;
import br.com.agrocontabil.repository.VencimentoGuiaResumo;
import br.com.agrocontabil.service.ApuracaoService;
import br.com.agrocontabil.service.ComprasInsumoResultado;
import br.com.agrocontabil.service.ConsultaDadosProdutorService;
import br.com.agrocontabil.service.CreditoService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Testes unitários das funções da Ceres (issues #142 e #143): produtor sempre do contexto,
 * parâmetros validados antes de qualquer consulta, fonte registrada, falha fechada sem contexto.
 */
@ExtendWith(MockitoExtension.class)
class FerramentasDadosProdutorTest {

  private static final UUID PRODUTOR = UUID.randomUUID();
  private static final LocalDate HOJE = LocalDate.of(2026, 9, 29);

  @Mock private ConsultaDadosProdutorService consultaService;
  @Mock private ApuracaoService apuracaoService;
  @Mock private CreditoService creditoService;

  private FerramentasDadosProdutor ferramentas;
  private ContextoAssistente contexto;
  private ToolContext toolContext;

  @BeforeEach
  void setUp() {
    ferramentas = new FerramentasDadosProdutor(consultaService, apuracaoService, creditoService);
    contexto = new ContextoAssistente(PRODUTOR, HOJE);
    toolContext = new ToolContext(contexto.comoToolContext());
  }

  @Test
  @DisplayName("Compras por descricao: produtor do contexto, valores formatados, fonte registrada")
  void comprasPorDescricao() {
    when(consultaService.comprasPorDescricao(
        PRODUTOR, "fertilizante", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)))
        .thenReturn(new ComprasInsumoResultado(new BigDecimal("12345.67"), 3, List.of(
            new CompraInsumoAgregado("KG", new BigDecimal("1500.0000"),
                new BigDecimal("12345.67")))));

    ComprasInsumo compras = ferramentas.totalComprasPorInsumo(
        "  fertilizante ", "2026-01-01", "2026-06-30", toolContext);

    assertThat(compras.valorTotal()).isEqualTo("R$ 12.345,67");
    assertThat(compras.quantidadeNotas()).isEqualTo(3);
    assertThat(compras.criterio()).isEqualTo("DESCRICAO");
    assertThat(compras.porUnidade()).singleElement()
        .satisfies(u -> assertThat(u.quantidade()).isEqualTo("1.500"));
    assertThat(contexto.fontes()).containsExactly(new FonteDado("totalComprasPorInsumo", Map.of(
        "descricaoOuNcm", "fertilizante", "dataInicio", "2026-01-01", "dataFim", "2026-06-30")));
  }

  @Test
  @DisplayName("NCM com pontos vira prefixo so de digitos")
  void comprasPorNcmComPontos() {
    when(consultaService.comprasPorNcm(eq(PRODUTOR), eq("38089324"), any(), any()))
        .thenReturn(new ComprasInsumoResultado(BigDecimal.ZERO, 0, List.of()));

    ComprasInsumo compras = ferramentas.totalComprasPorInsumo(
        "3808.93.24", "2026-01-01", "2026-01-31", toolContext);

    assertThat(compras.criterio()).isEqualTo("NCM");
    assertThat(compras.valorTotal()).isEqualTo("R$ 0,00");
  }

  @Test
  @DisplayName("Parametros invalidos sao recusados ANTES de qualquer consulta")
  void parametrosInvalidosNaoConsultam() {
    assertThatThrownBy(() -> ferramentas.totalComprasPorInsumo(
        "adubo", "01/01/2026", "2026-06-30", toolContext))
        .isInstanceOf(ParametroFerramentaInvalidoException.class)
        .hasMessageContaining("AAAA-MM-DD");
    assertThatThrownBy(() -> ferramentas.totalComprasPorInsumo(
        "adubo", "2026-06-30", "2026-01-01", toolContext))
        .isInstanceOf(ParametroFerramentaInvalidoException.class);
    assertThatThrownBy(() -> ferramentas.totalComprasPorInsumo(
        "adubo", "2020-01-01", "2026-01-01", toolContext))
        .isInstanceOf(ParametroFerramentaInvalidoException.class)
        .hasMessageContaining("24 meses");
    assertThatThrownBy(() -> ferramentas.totalComprasPorInsumo(
        "ab", "2026-01-01", "2026-01-31", toolContext))
        .isInstanceOf(ParametroFerramentaInvalidoException.class);
    assertThatThrownBy(() -> ferramentas.consultarApuracao("julho", toolContext))
        .isInstanceOf(ParametroFerramentaInvalidoException.class);
    assertThatThrownBy(() -> ferramentas.listarNotas(null, null, null, null, 50, toolContext))
        .isInstanceOf(ParametroFerramentaInvalidoException.class);
    assertThatThrownBy(() -> ferramentas.listarNotas("TODAS", null, null, null, null, toolContext))
        .isInstanceOf(ParametroFerramentaInvalidoException.class);
    assertThatThrownBy(() -> ferramentas.proximosVencimentos(365, toolContext))
        .isInstanceOf(ParametroFerramentaInvalidoException.class);

    verifyNoInteractions(consultaService, apuracaoService, creditoService);
    assertThat(contexto.fontes()).isEmpty();
  }

  @Test
  @DisplayName("Sem ContextoAssistente no ToolContext: falha fechado, nenhuma consulta")
  void semContextoFalhaFechado() {
    ToolContext vazio = new ToolContext(Map.of());
    ToolContext comProdutorForjado = new ToolContext(
        Map.of(ContextoAssistente.CHAVE, PRODUTOR.toString()));

    assertThatThrownBy(() -> ferramentas.consultarApuracao("2026-07", vazio))
        .isInstanceOf(ContextoAssistenteAusenteException.class);
    assertThatThrownBy(() -> ferramentas.consultarCreditos("2026-01-01", "2026-01-31",
        comProdutorForjado))
        .isInstanceOf(ContextoAssistenteAusenteException.class);
    assertThatThrownBy(() -> ferramentas.proximosVencimentos(30, null))
        .isInstanceOf(ContextoAssistenteAusenteException.class);

    verifyNoInteractions(consultaService, apuracaoService, creditoService);
  }

  @Test
  @DisplayName("Apuracao reaproveita o ApuracaoService com o produtor do contexto")
  void apuracaoReaproveitaService() {
    when(apuracaoService.consultarParaProdutor(PRODUTOR, "2026-07")).thenReturn(
        new ApuracaoResponse("2026-07", "ABERTO", new BigDecimal("150.00"),
            new BigDecimal("1350.00"), new BigDecimal("30.00"), new BigDecimal("270.00"),
            new BigDecimal("120.00"), new BigDecimal("1080.00"), 2, null));

    Apuracao apuracao = ferramentas.consultarApuracao(" 2026-07 ", toolContext);

    assertThat(apuracao.saldoIbs()).isEqualTo("R$ 120,00");
    assertThat(apuracao.saldoCbs()).isEqualTo("R$ 1.080,00");
    assertThat(contexto.fontes()).extracting(FonteDado::funcao).containsExactly("consultarApuracao");
  }

  @Test
  @DisplayName("Creditos: total e a soma feita no backend, nao pelo modelo")
  void creditos() {
    when(creditoService.somarCreditosNoIntervalo(
        PRODUTOR, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 29)))
        .thenReturn(new CreditoSaldoAgregado(new BigDecimal("30.00"), new BigDecimal("270.00"), 4));

    Creditos creditos = ferramentas.consultarCreditos("2026-01-01", "2026-09-29", toolContext);

    assertThat(creditos.creditoTotal()).isEqualTo("R$ 300,00");
    assertThat(creditos.quantidadeNotas()).isEqualTo(4);
  }

  @Test
  @DisplayName("Listar notas: padroes (90 dias, 5 notas), SAIDA = notas proprias, sem PII")
  void listarNotasComPadroes() {
    OffsetDateTime emissao = OffsetDateTime.of(2026, 9, 10, 10, 0, 0, 0, ZoneOffset.ofHours(-3));
    when(consultaService.listarNotas(PRODUTOR, EnumSet.of(OrigemNotaFiscal.PROPRIA),
        EnumSet.allOf(StatusNotaFiscal.class), HOJE.minusDays(90), HOJE, 5))
        .thenReturn(new PageImpl<>(List.of(new NotaResumo(12L, 1, emissao,
            OrigemNotaFiscal.PROPRIA, StatusNotaFiscal.AUTORIZADA, new BigDecimal("1000.00"),
            new BigDecimal("1.00"), new BigDecimal("9.00"))), PageRequest.of(0, 5), 7));

    Notas notas = ferramentas.listarNotas("saida", null, null, null, null, toolContext);

    assertThat(notas.totalEncontrado()).isEqualTo(7);
    assertThat(notas.notas()).singleElement().satisfies(n -> {
      assertThat(n.tipo()).isEqualTo("SAIDA");
      assertThat(n.dataEmissao()).isEqualTo("10/09/2026");
      assertThat(n.valorTotal()).isEqualTo("R$ 1.000,00");
    });
  }

  @Test
  @DisplayName("Proximos vencimentos: janela a partir de hoje do contexto, padrao 30 dias")
  void proximosVencimentos() {
    when(consultaService.vencimentosNaoPagos(eq(PRODUTOR), eq(HOJE), eq(HOJE.plusDays(30)),
        anyInt()))
        .thenReturn(new PageImpl<>(List.of(new VencimentoGuiaResumo(TipoGuiaFiscal.ICMS,
            LocalDate.of(2026, 10, 10), new BigDecimal("1250.00"), StatusGuiaFiscal.PENDENTE))));

    Vencimentos vencimentos = ferramentas.proximosVencimentos(null, toolContext);

    assertThat(vencimentos.guias()).singleElement()
        .satisfies(g -> assertThat(g.vencimento()).isEqualTo("10/10/2026"));
    verify(consultaService).vencimentosNaoPagos(PRODUTOR, HOJE, HOJE.plusDays(30),
        ConsultaDadosProdutorService.LIMITE_MAXIMO);
  }
}
