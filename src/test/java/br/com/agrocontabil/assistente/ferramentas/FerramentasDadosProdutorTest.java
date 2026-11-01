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
import br.com.agrocontabil.assistente.porta.DadosProdutorPorta;
import br.com.agrocontabil.assistente.porta.DadosProdutorPorta.Pagina;
import br.com.agrocontabil.assistente.FonteDado;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Apuracao;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.ComprasInsumo;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Creditos;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Guia;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Notas;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Vencimentos;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

/**
 * Testes unitários das funções da Ceres (issues #142 e #143): produtor sempre do contexto,
 * parâmetros validados antes de qualquer consulta, fonte registrada, falha fechada sem contexto.
 */
@ExtendWith(MockitoExtension.class)
class FerramentasDadosProdutorTest {

  private static final UUID PRODUTOR = UUID.randomUUID();
  private static final LocalDate HOJE = LocalDate.of(2026, 9, 29);

  @Mock private DadosProdutorPorta dados;

  private FerramentasDadosProdutor ferramentas;
  private ContextoAssistente contexto;
  private ToolContext toolContext;

  @BeforeEach
  void setUp() {
    ferramentas = new FerramentasDadosProdutor(dados);
    contexto = new ContextoAssistente(PRODUTOR, HOJE);
    toolContext = new ToolContext(contexto.comoToolContext());
  }

  @Test
  @DisplayName("Compras por descricao: produtor do contexto, valores formatados, fonte registrada")
  void comprasPorDescricao() {
    when(dados.comprasPorDescricao(
        PRODUTOR, "fertilizante", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)))
        .thenReturn(new DadosProdutorPorta.ComprasInsumo(new BigDecimal("12345.67"), 3, List.of(
            new DadosProdutorPorta.CompraUnidade("KG", new BigDecimal("1500.0000"),
                new BigDecimal("12345.67")))));

    ComprasInsumo compras = ferramentas.totalComprasPorInsumo(
        "  fertilizante ", "2026-01-01", "2026-06-30", toolContext);

    assertThat(compras.valorTotal()).isEqualTo("R$ 12.345,67");
    assertThat(compras.quantidadeNotas()).isEqualTo(3);
    assertThat(compras.criterio()).isEqualTo("DESCRICAO");
    assertThat(compras.observacao()).isNull();
    // #170: consulta com resultado conta para o MISTO.
    assertThat(contexto.nenhumDadoEncontrado()).isFalse();
    assertThat(compras.porUnidade()).singleElement()
        .satisfies(u -> assertThat(u.quantidade()).isEqualTo("1.500"));
    assertThat(contexto.fontes()).containsExactly(new FonteDado("totalComprasPorInsumo", Map.of(
        "descricaoOuNcm", "fertilizante", "dataInicio", "2026-01-01", "dataFim", "2026-06-30")));
  }

  @Test
  @DisplayName("NCM com pontos vira prefixo so de digitos")
  void comprasPorNcmComPontos() {
    when(dados.comprasPorNcm(eq(PRODUTOR), eq("38089324"), any(), any()))
        .thenReturn(new DadosProdutorPorta.ComprasInsumo(BigDecimal.ZERO, 0, List.of()));

    ComprasInsumo compras = ferramentas.totalComprasPorInsumo(
        "3808.93.24", "2026-01-01", "2026-01-31", toolContext);

    assertThat(compras.criterio()).isEqualTo("NCM");
    assertThat(compras.valorTotal()).isEqualTo("R$ 0,00");
    // Zero nao e "nao comprou": a observacao orienta o modelo a dizer que nada foi encontrado.
    assertThat(compras.observacao()).isEqualTo(FerramentasDadosProdutor.NADA_POR_NCM);
    // #170: consulta vazia nao conta como dado encontrado.
    assertThat(contexto.nenhumDadoEncontrado()).isTrue();
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

    verifyNoInteractions(dados);
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

    verifyNoInteractions(dados);
  }

  @Test
  @DisplayName("Apuracao pela porta, com o produtor do contexto")
  void apuracaoPelaPorta() {
    when(dados.apuracao(PRODUTOR, "2026-07")).thenReturn(
        new DadosProdutorPorta.Apuracao("2026-07", "ABERTO", new BigDecimal("150.00"),
            new BigDecimal("1350.00"), new BigDecimal("30.00"), new BigDecimal("270.00"),
            new BigDecimal("120.00"), new BigDecimal("1080.00"), 2));

    Apuracao apuracao = ferramentas.consultarApuracao(" 2026-07 ", toolContext);

    assertThat(apuracao.saldoIbs()).isEqualTo("R$ 120,00");
    assertThat(apuracao.saldoCbs()).isEqualTo("R$ 1.080,00");
    assertThat(contexto.fontes()).extracting(FonteDado::funcao).containsExactly("consultarApuracao");
  }

  @Test
  @DisplayName("Creditos: total e a soma feita no backend, nao pelo modelo")
  void creditos() {
    when(dados.creditos(PRODUTOR, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 29)))
        .thenReturn(new DadosProdutorPorta.Creditos(
            new BigDecimal("30.00"), new BigDecimal("270.00"), 4));

    Creditos creditos = ferramentas.consultarCreditos("2026-01-01", "2026-09-29", toolContext);

    assertThat(creditos.creditoTotal()).isEqualTo("R$ 300,00");
    assertThat(creditos.quantidadeNotas()).isEqualTo(4);
  }

  @Test
  @DisplayName("Listar notas: padroes (90 dias, 5 notas), SAIDA = notas proprias, sem PII")
  void listarNotasComPadroes() {
    OffsetDateTime emissao = OffsetDateTime.of(2026, 9, 10, 10, 0, 0, 0, ZoneOffset.ofHours(-3));
    when(dados.notas(PRODUTOR, EnumSet.of(OrigemNotaFiscal.PROPRIA),
        EnumSet.allOf(StatusNotaFiscal.class), HOJE.minusDays(90), HOJE, 5))
        .thenReturn(new Pagina<>(List.of(new DadosProdutorPorta.Nota(12L, 1, emissao,
            OrigemNotaFiscal.PROPRIA, StatusNotaFiscal.AUTORIZADA, new BigDecimal("1000.00"),
            new BigDecimal("1.00"), new BigDecimal("9.00"))), 7));
    when(dados.totalNotas(PRODUTOR, EnumSet.of(OrigemNotaFiscal.PROPRIA),
        EnumSet.of(StatusNotaFiscal.AUTORIZADA), HOJE.minusDays(90), HOJE))
        .thenReturn(new DadosProdutorPorta.Totais(4, new BigDecimal("8765.40")));

    Notas notas = ferramentas.listarNotas("saida", null, null, null, null, toolContext);

    assertThat(notas.totalEncontrado()).isEqualTo(7);
    // #169: sem filtro de situacao a soma fica nas autorizadas, e o modelo e avisado disso.
    assertThat(notas.valorTotal()).isEqualTo("R$ 8.765,40");
    // Contagem do mesmo conjunto da soma (4 autorizadas), nao das 7 do filtro de listagem.
    assertThat(notas.quantidadeNoTotal()).isEqualTo(4);
    // Sem datas o periodo e dos ultimos 90 dias, e ele vai junto para a resposta dizer qual e.
    assertThat(notas.dataInicio()).isEqualTo("01/07/2026");
    assertThat(notas.dataFim()).isEqualTo("29/09/2026");
    assertThat(notas.abrangenciaTotal())
        .isEqualTo(FerramentasDadosProdutor.TOTAL_SO_AUTORIZADAS)
        .doesNotContainPattern("\\d");
    assertThat(notas.notas()).singleElement().satisfies(n -> {
      assertThat(n.tipo()).isEqualTo("SAIDA");
      assertThat(n.dataEmissao()).isEqualTo("10/09/2026");
      assertThat(n.valorTotal()).isEqualTo("R$ 1.000,00");
    });
  }

  @Test
  @DisplayName("Proximos vencimentos: janela a partir de hoje do contexto, padrao 30 dias")
  void proximosVencimentos() {
    when(dados.vencimentosNaoPagos(eq(PRODUTOR), eq(HOJE), eq(HOJE.plusDays(30)), anyInt()))
        .thenReturn(new Pagina<>(List.of(new DadosProdutorPorta.Vencimento(TipoGuiaFiscal.ICMS,
            LocalDate.of(2026, 10, 10), new BigDecimal("1250.00"), StatusGuiaFiscal.PENDENTE)),
            12));
    when(dados.totalVencimentosNaoPagos(PRODUTOR, HOJE, HOJE.plusDays(30)))
        .thenReturn(new DadosProdutorPorta.Totais(12, new BigDecimal("15050.55")));

    Vencimentos vencimentos = ferramentas.proximosVencimentos(null, toolContext);

    assertThat(vencimentos.guias()).singleElement()
        .satisfies(g -> assertThat(g.vencimento()).isEqualTo("10/10/2026"));
    // #169: o total cobre as 12 guias da janela, nao so a que coube na lista.
    assertThat(vencimentos.totalEncontrado()).isEqualTo(12);
    assertThat(vencimentos.valorTotal()).isEqualTo("R$ 15.050,55");
    verify(dados).vencimentosNaoPagos(PRODUTOR, HOJE, HOJE.plusDays(30),
        DadosProdutorPorta.LIMITE_MAXIMO);
  }

  @Test
  @DisplayName("#169: com filtro de situacao, a soma e do proprio filtro")
  void listarNotasComSituacaoSomaOFiltro() {
    when(dados.notas(PRODUTOR, EnumSet.allOf(OrigemNotaFiscal.class),
        EnumSet.of(StatusNotaFiscal.REJEITADA), HOJE.minusDays(90), HOJE, 5))
        .thenReturn(new Pagina<>(List.of(), 3));
    when(dados.totalNotas(PRODUTOR, EnumSet.allOf(OrigemNotaFiscal.class),
        EnumSet.of(StatusNotaFiscal.REJEITADA), HOJE.minusDays(90), HOJE))
        .thenReturn(new DadosProdutorPorta.Totais(3, new BigDecimal("300.00")));

    Notas notas = ferramentas.listarNotas(null, "rejeitada", null, null, null, toolContext);

    assertThat(notas.valorTotal()).isEqualTo("R$ 300,00");
    assertThat(notas.abrangenciaTotal()).isEqualTo(FerramentasDadosProdutor.TOTAL_DO_FILTRO);
  }

  @Test
  @DisplayName("#169: janela sem guia devolve total zero, sem erro")
  void proximosVencimentosSemGuia() {
    when(dados.vencimentosNaoPagos(eq(PRODUTOR), eq(HOJE), eq(HOJE.plusDays(7)), anyInt()))
        .thenReturn(new Pagina<>(List.of(), 0));
    when(dados.totalVencimentosNaoPagos(PRODUTOR, HOJE, HOJE.plusDays(7)))
        .thenReturn(new DadosProdutorPorta.Totais(0, BigDecimal.ZERO.setScale(2)));

    Vencimentos vencimentos = ferramentas.proximosVencimentos(7, toolContext);

    assertThat(vencimentos.totalEncontrado()).isZero();
    assertThat(vencimentos.valorTotal()).isEqualTo("R$ 0,00");
  }

  @Test
  @DisplayName("#140: listarAlertas usa o produtor do contexto e formata o prazo")
  void listarAlertas() {
    when(dados.alertas(PRODUTOR)).thenReturn(new Pagina<>(List.of(new DadosProdutorPorta.Alerta(
        "VENCIMENTO_GUIA", "Guia de FUNRURAL de R$ 1.840,55", HOJE.plusDays(2), 2)), 1));

    RespostasFerramentas.Alertas alertas = ferramentas.listarAlertas(toolContext);

    assertThat(alertas.totalEncontrado()).isEqualTo(1);
    assertThat(alertas.alertas()).singleElement().satisfies(a -> {
      assertThat(a.prazo()).isEqualTo("01/10/2026");
      assertThat(a.diasRestantes()).isEqualTo(2);
    });
    assertThat(contexto.fontes()).extracting(FonteDado::funcao).containsExactly("listarAlertas");
    assertThat(contexto.nenhumDadoEncontrado()).isFalse();
  }

  @Test
  @DisplayName("#140: sem alertas, a consulta conta como vazia (frase fixa no MISTO)")
  void listarAlertasVazio() {
    when(dados.alertas(PRODUTOR)).thenReturn(new Pagina<>(List.of(), 0));

    assertThat(ferramentas.listarAlertas(toolContext).alertas()).isEmpty();
    assertThat(contexto.nenhumDadoEncontrado()).isTrue();
  }

  @Test
  @DisplayName("#140: sem contexto, listarAlertas falha fechado e nao consulta nada")
  void listarAlertasSemContexto() {
    assertThatThrownBy(() -> ferramentas.listarAlertas(new ToolContext(Map.of())))
        .isInstanceOf(ContextoAssistenteAusenteException.class);
    verifyNoInteractions(dados);
  }

  @Test
  @DisplayName("#145: retencoes do periodo - Funrural retido e sem retencao, split payment, avisos")
  void retencoes() {
    when(dados.retencoes(PRODUTOR, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 31)))
        .thenReturn(new DadosProdutorPorta.Retencoes(true,
            new DadosProdutorPorta.Funrural(1, new BigDecimal("144000.00"),
                new BigDecimal("1728.00"), new BigDecimal("144.00"), new BigDecimal("288.00"),
                new BigDecimal("2160.00")),
            new DadosProdutorPorta.Funrural(1, new BigDecimal("10000.00"),
                new BigDecimal("120.00"), new BigDecimal("10.00"), new BigDecimal("20.00"),
                new BigDecimal("150.00")),
            new DadosProdutorPorta.Funrural(1, new BigDecimal("5000.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            new BigDecimal("159.00"), new BigDecimal("1431.00"), 3));

    RespostasFerramentas.Retencoes r =
        ferramentas.consultarRetencoes("2026-01-01", "2026-08-31", toolContext);

    assertThat(r.vendasAutorizadas()).isEqualTo(3);
    assertThat(r.funruralRetidoPeloComprador().total()).isEqualTo("R$ 2.160,00");
    assertThat(r.funruralRetidoPeloComprador().aliquota())
        .isEqualTo(FerramentasDadosProdutor.ALIQUOTA_FUNRURAL);
    assertThat(r.funruralSemRetencao().base()).isEqualTo("R$ 10.000,00");
    assertThat(r.exportacao().aliquota()).isEqualTo(FerramentasDadosProdutor.FUNRURAL_EXPORTACAO);
    assertThat(r.splitPaymentTotal()).isEqualTo("R$ 1.590,00");
    // Houve exportacao: o aviso da imunidade vem primeiro, depois os da pessoa fisica.
    assertThat(r.avisos()).first().isEqualTo(FerramentasDadosProdutor.AVISO_EXPORTACAO);
    assertThat(r.avisos()).containsAll(FerramentasDadosProdutor.AVISOS_PESSOA_FISICA);
    assertThat(contexto.fontes()).singleElement().satisfies(f -> {
      assertThat(f.funcao()).isEqualTo("consultarRetencoes");
      assertThat(f.parametros())
          .containsEntry("dataInicio", "2026-01-01").containsEntry("dataFim", "2026-08-31");
    });
    assertThat(contexto.nenhumDadoEncontrado()).isFalse();
  }

  @Test
  @DisplayName("#145: produtor PJ - Funrural 'nao se aplica', so o aviso do split payment segue")
  void retencoesProdutorPj() {
    DadosProdutorPorta.Funrural zero = new DadosProdutorPorta.Funrural(0, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    when(dados.retencoes(PRODUTOR, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
        .thenReturn(new DadosProdutorPorta.Retencoes(false, zero, zero, zero, BigDecimal.ZERO,
            BigDecimal.ZERO, 0));

    RespostasFerramentas.Retencoes r =
        ferramentas.consultarRetencoes("2026-08-01", "2026-08-31", toolContext);

    assertThat(r.funruralRetidoPeloComprador().aliquota())
        .isEqualTo(FerramentasDadosProdutor.FUNRURAL_NAO_SE_APLICA);
    assertThat(r.avisos()).isEqualTo(FerramentasDadosProdutor.AVISOS_OUTROS);
    assertThat(contexto.nenhumDadoEncontrado()).isTrue();
  }

  @Test
  @DisplayName("#145: periodo invalido nao consulta")
  void retencoesPeriodoInvalido() {
    assertThatThrownBy(() -> ferramentas.consultarRetencoes("2026-09-01", "2026-08-01",
        toolContext)).isInstanceOf(ParametroFerramentaInvalidoException.class);
    verifyNoInteractions(dados);
  }

  private void guias(LocalDate de, LocalDate ate, String total, long quantidade,
      DadosProdutorPorta.Vencimento... itens) {
    when(dados.vencimentosNaoPagos(PRODUTOR, de, ate, DadosProdutorPorta.LIMITE_MAXIMO))
        .thenReturn(new Pagina<>(List.of(itens), quantidade));
    when(dados.totalVencimentosNaoPagos(PRODUTOR, de, ate))
        .thenReturn(new DadosProdutorPorta.Totais(quantidade, new BigDecimal(total)));
  }

  private static DadosProdutorPorta.Vencimento guia(TipoGuiaFiscal tipo, LocalDate data,
      String valor, StatusGuiaFiscal status) {
    return new DadosProdutorPorta.Vencimento(tipo, data, new BigDecimal(valor), status);
  }

  @Test
  @DisplayName("#259: debito pendente = nao pagas vencidas antes de hoje, sem limite para tras")
  void guiasEmAtraso() {
    guias(FerramentasDadosProdutor.SEM_LIMITE_PASSADO, HOJE.minusDays(1), "1680.00", 2,
        guia(TipoGuiaFiscal.DARF, LocalDate.of(2026, 7, 15), "430.00", StatusGuiaFiscal.VENCIDA),
        guia(TipoGuiaFiscal.ICMS, LocalDate.of(2026, 9, 5), "1250.00",
            StatusGuiaFiscal.PENDENTE));

    RespostasFerramentas.GuiasEmAtraso atraso = ferramentas.guiasEmAtraso(toolContext);

    assertThat(atraso.emAtraso().valorTotal()).isEqualTo("R$ 1.680,00");
    assertThat(atraso.emAtraso().quantidade()).isEqualTo(2);
    assertThat(atraso.emAtraso().ate()).isEqualTo("28/09/2026");
    // Pela data: a PENDENTE vencida sai como VENCIDA (o job das 1h ainda nao a marcou).
    assertThat(atraso.emAtraso().guias()).extracting(Guia::situacao)
        .containsExactly("VENCIDA", "VENCIDA");
    assertThat(atraso.proximaGuia()).isNull();
    assertThat(atraso.observacao()).isNull();
    assertThat(contexto.fontes()).singleElement()
        .satisfies(f -> assertThat(f.funcao()).isEqualTo("guiasEmAtraso"));
  }

  @Test
  @DisplayName("#259: sem atraso, a frase traz o recorte e a proxima guia vem junto")
  void guiasEmAtrasoVazio() {
    guias(FerramentasDadosProdutor.SEM_LIMITE_PASSADO, HOJE.minusDays(1), "0.00", 0);
    when(dados.vencimentosNaoPagos(PRODUTOR, HOJE, HOJE.plusYears(1), 1))
        .thenReturn(new Pagina<>(List.of(guia(TipoGuiaFiscal.GPS, HOJE, "310.00",
            StatusGuiaFiscal.PENDENTE)), 4));

    RespostasFerramentas.GuiasEmAtraso atraso = ferramentas.guiasEmAtraso(toolContext);

    assertThat(atraso.emAtraso().valorTotal()).isEqualTo("R$ 0,00");
    assertThat(atraso.observacao()).isEqualTo(
        "Nao encontrei guias em atraso (nao pagas com vencimento ate 28/09/2026).");
    // Vence hoje: e a proxima, nao atraso.
    assertThat(atraso.proximaGuia().vencimento()).isEqualTo("29/09/2026");
  }

  @Test
  @DisplayName("#257: este mes = atraso de qualquer mes + nao pagas de hoje ao fim do mes")
  void vencimentosDoMesCorrente() {
    guias(FerramentasDadosProdutor.SEM_LIMITE_PASSADO, HOJE.minusDays(1), "1680.00", 2);
    guias(HOJE, LocalDate.of(2026, 9, 30), "4108.00", 3);

    RespostasFerramentas.VencimentosDoMes mes = ferramentas.vencimentosDoMes(null, toolContext);

    assertThat(mes.mes()).isEqualTo("2026-09");
    assertThat(mes.emAtraso().valorTotal()).isEqualTo("R$ 1.680,00");
    assertThat(mes.aVencer().valorTotal()).isEqualTo("R$ 4.108,00");
    assertThat(mes.aVencer().de()).isEqualTo("29/09/2026");
    assertThat(mes.valorTotal()).isEqualTo("R$ 5.788,00");
    assertThat(mes.quantidade()).isEqualTo(5);
    assertThat(contexto.fontes()).singleElement()
        .satisfies(f -> assertThat(f.parametros()).containsEntry("mes", "2026-09"));
  }

  @Test
  @DisplayName("#257: mes futuro so tem as do mes; passado so as que ficaram sem pagar")
  void vencimentosDeOutroMes() {
    guias(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "640.00", 1);
    guias(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "0.00", 0);

    RespostasFerramentas.VencimentosDoMes novembro =
        ferramentas.vencimentosDoMes("2026-11", toolContext);
    RespostasFerramentas.VencimentosDoMes julho =
        ferramentas.vencimentosDoMes(" 2026-07 ", toolContext);

    assertThat(novembro.emAtraso()).isNull();
    assertThat(novembro.valorTotal()).isEqualTo("R$ 640,00");
    assertThat(julho.aVencer()).isNull();
    assertThat(julho.valorTotal()).isEqualTo("R$ 0,00");
    assertThat(julho.observacao())
        .isEqualTo("Nao encontrei guias para pagar (sem guias nao pagas de 01/07/2026 a "
            + "31/07/2026).");
  }

  @Test
  @DisplayName("#257: mes invalido ou longe demais nao consulta nada")
  void vencimentosDoMesInvalido() {
    assertThatThrownBy(() -> ferramentas.vencimentosDoMes("2026-13", toolContext))
        .isInstanceOf(ParametroFerramentaInvalidoException.class);
    assertThatThrownBy(() -> ferramentas.vencimentosDoMes("2028-01", toolContext))
        .isInstanceOf(ParametroFerramentaInvalidoException.class);
    verifyNoInteractions(dados);
  }

  @Test
  @DisplayName("#256: nota pelo numero, situacao explicada, sinais vazios (para a #276)")
  void consultarNota() {
    when(dados.notasPorNumero(PRODUTOR, 107)).thenReturn(new Pagina<>(List.of(
        new DadosProdutorPorta.NotaDetalhe(
            107, 1, OffsetDateTime.of(2026, 9, 8, 12, 0, 0, 0, ZoneOffset.ofHours(-3)),
            OrigemNotaFiscal.PROPRIA, StatusNotaFiscal.DEVOLVIDA, new BigDecimal("12100.00"),
            BigDecimal.ZERO, BigDecimal.ZERO, "Corrija a natureza.",
            List.of(new DadosProdutorPorta.Inconsistencia("C", "campo", "msg", "sug")))), 4));

    RespostasFerramentas.NotaConsultada nota = ferramentas.consultarNota(107, toolContext);

    // Quantas existem, nao so as listadas.
    assertThat(nota.encontradas()).isEqualTo(4);
    assertThat(nota.observacao()).isNull();
    assertThat(nota.notas()).singleElement().satisfies(n -> {
      assertThat(n.tipo()).isEqualTo("SAIDA");
      assertThat(n.situacao()).isEqualTo("DEVOLVIDA");
      assertThat(n.explicacao()).contains("contador").contains("nao e rejeicao");
      assertThat(n.valorTotal()).isEqualTo("R$ 12.100,00");
      assertThat(n.motivoDevolucao()).isEqualTo("Corrija a natureza.");
      assertThat(n.inconsistencias()).singleElement()
          .satisfies(i -> assertThat(i.sugestao()).isEqualTo("sug"));
      assertThat(n.sinais()).isEmpty();
    });
    assertThat(contexto.fontes()).singleElement()
        .satisfies(f -> assertThat(f.parametros()).containsEntry("numero", "107"));
  }

  @Test
  @DisplayName("#256: numero inexistente ou de outro produtor responde igual, sem revelar nada")
  void consultarNotaInexistente() {
    when(dados.notasPorNumero(PRODUTOR, 5)).thenReturn(new Pagina<>(List.of(), 0));

    RespostasFerramentas.NotaConsultada nota = ferramentas.consultarNota(5, toolContext);

    assertThat(nota.encontradas()).isZero();
    assertThat(nota.observacao()).isEqualTo("Nao encontrei a nota 5 nas suas notas.");
  }

  @Test
  @DisplayName("#256: numero invalido nao consulta")
  void consultarNotaInvalida() {
    assertThatThrownBy(() -> ferramentas.consultarNota(0, toolContext))
        .isInstanceOf(ParametroFerramentaInvalidoException.class);
    assertThatThrownBy(() -> ferramentas.consultarNota(null, toolContext))
        .isInstanceOf(ParametroFerramentaInvalidoException.class);
    verifyNoInteractions(dados);
  }

  @Test
  @DisplayName("#254: nota em aberto sem datas olha 24 meses, nao so 90 dias")
  void listarNotasDevolvidasSemPrazo() {
    when(dados.notas(PRODUTOR, EnumSet.allOf(OrigemNotaFiscal.class),
        EnumSet.of(StatusNotaFiscal.DEVOLVIDA), HOJE.minusDays(731), HOJE, 5))
        .thenReturn(new Pagina<>(List.of(), 0));
    when(dados.totalNotas(PRODUTOR, EnumSet.allOf(OrigemNotaFiscal.class),
        EnumSet.of(StatusNotaFiscal.DEVOLVIDA), HOJE.minusDays(731), HOJE))
        .thenReturn(new DadosProdutorPorta.Totais(0, BigDecimal.ZERO));

    Notas notas = ferramentas.listarNotas(null, "DEVOLVIDA", null, null, null, toolContext);

    assertThat(notas.dataInicio()).isEqualTo(FormatoBr.data(HOJE.minusDays(731)));
  }

  @Test
  @DisplayName("#254: toda situacao da nota tem explicacao e aparece na descricao de listarNotas")
  void todaSituacaoExplicada() {
    for (StatusNotaFiscal status : StatusNotaFiscal.values()) {
      assertThat(FerramentasDadosProdutor.EXPLICACAO_SITUACAO).as(status.name())
          .containsKey(status);
      assertThat(FerramentasDadosProdutor.DESCRICAO_SITUACAO).as(status.name())
          .containsPattern("\\b" + status.name() + "\\b");
    }
    // Devolvida (contador) e rejeitada (SEFAZ) nao se confundem.
    assertThat(FerramentasDadosProdutor.EXPLICACAO_SITUACAO.get(StatusNotaFiscal.REJEITADA))
        .contains("SEFAZ");
  }

  @Test
  @DisplayName("#248: vendas por produto, sem datas = do 1o de janeiro ate hoje")
  void vendasPorProduto() {
    when(dados.vendasDeProduto(PRODUTOR, "soja", false, LocalDate.of(2026, 1, 1), HOJE))
        .thenReturn(new DadosProdutorPorta.ComprasInsumo(new BigDecimal("257850.00"), 3,
            List.of(new DadosProdutorPorta.CompraUnidade("KG", new BigDecimal("105000"),
                new BigDecimal("257850.00")))));

    RespostasFerramentas.VendasProduto vendas =
        ferramentas.totalVendasPorProduto("  soja ", null, null, toolContext);

    assertThat(vendas.valorTotal()).isEqualTo("R$ 257.850,00");
    assertThat(vendas.dataInicio()).isEqualTo("01/01/2026");
    assertThat(vendas.criterio()).isEqualTo("DESCRICAO");
    assertThat(vendas.observacao()).isNull();
    assertThat(contexto.fontes()).singleElement().satisfies(f -> {
      assertThat(f.funcao()).isEqualTo("totalVendasPorProduto");
      assertThat(f.parametros()).containsEntry("descricaoOuNcm", "soja")
          .containsEntry("dataInicio", "2026-01-01");
    });
  }

  @Test
  @DisplayName("#248: por NCM e sem venda, a frase fala com o usuario")
  void vendasPorNcmVazio() {
    when(dados.vendasDeProduto(PRODUTOR, "1201", true, LocalDate.of(2026, 8, 1),
        LocalDate.of(2026, 8, 31)))
        .thenReturn(new DadosProdutorPorta.ComprasInsumo(BigDecimal.ZERO, 0, List.of()));

    RespostasFerramentas.VendasProduto vendas = ferramentas.totalVendasPorProduto(
        "12.01", "2026-08-01", "2026-08-31", toolContext);

    assertThat(vendas.criterio()).isEqualTo("NCM");
    assertThat(vendas.observacao()).isEqualTo(FerramentasDadosProdutor.NADA_VENDIDO);
  }

  @Test
  @DisplayName("#248: termo curto ou periodo invertido nao consulta")
  void vendasInvalidas() {
    assertThatThrownBy(() -> ferramentas.totalVendasPorProduto("so", null, null, toolContext))
        .isInstanceOf(ParametroFerramentaInvalidoException.class);
    assertThatThrownBy(() -> ferramentas.totalVendasPorProduto(
        "soja", "2026-09-01", "2026-08-01", toolContext))
        .isInstanceOf(ParametroFerramentaInvalidoException.class);
    verifyNoInteractions(dados);
  }

  @Test
  @DisplayName("#203: todo texto que as funcoes devolvem pode ir ao usuario como esta")
  void textosDasFuncoesSaoParaOUsuario() throws Exception {
    // O prompt manda repassar a observacao, e o modelo copia: recado ao modelo vira resposta.
    List<String> textos = new ArrayList<>();
    for (Field campo : FerramentasDadosProdutor.class.getDeclaredFields()) {
      if (!Modifier.isStatic(campo.getModifiers())) {
        continue;
      }
      campo.setAccessible(true);
      Object valor = campo.get(null);
      if (valor instanceof String texto) {
        textos.add(texto);
      } else if (valor instanceof List<?> lista) {
        lista.stream().filter(String.class::isInstance).map(String.class::cast).forEach(textos::add);
      } else if (valor instanceof Map<?, ?> mapa) {
        mapa.values().stream().filter(String.class::isInstance).map(String.class::cast)
            .forEach(textos::add);
      }
    }
    assertThat(textos).contains(FerramentasDadosProdutor.NADA_POR_DESCRICAO,
        FerramentasDadosProdutor.NADA_POR_NCM, FerramentasDadosProdutor.AVISO_EXPORTACAO);
    for (String texto : textos) {
      String normalizado = Normalizer.normalize(texto, Normalizer.Form.NFD)
          .replaceAll("\\p{M}+", "").toLowerCase(java.util.Locale.ROOT);
      assertThat(normalizado).as(texto).doesNotContain(
          "usuario", "diga ", "em vez de", "modelo", "repasse", "responda", "afirmar");
    }
  }

  @Test
  @DisplayName("#203: busca vazia por descricao fala com o usuario, sem digito")
  void nadaPorDescricaoParaOUsuario() {
    assertThat(FerramentasDadosProdutor.NADA_POR_DESCRICAO)
        .startsWith("Nao encontrei compras").contains("codigo NCM").doesNotContainPattern("\\d");
    assertThat(FerramentasDadosProdutor.NADA_POR_NCM)
        .startsWith("Nao encontrei compras").doesNotContainPattern("\\d");
  }
}
