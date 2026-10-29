package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.CreditoSaldoResponse;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.AgrupamentoApuracao;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Autorização antes da leitura nas rotas de número do cliente (issue #255). */
@ExtendWith(MockitoExtension.class)
@DisplayName("CarteiraIndicadoresService (#255)")
class CarteiraIndicadoresServiceTest {

  private static final UUID PRODUTOR = UUID.randomUUID();

  @Mock private ProdutorEmContextoResolver produtorEmContexto;
  @Mock private ProdutorRepository produtorRepository;
  @Mock private ApuracaoService apuracaoService;
  @Mock private ApuracaoFaixaService apuracaoFaixaService;
  @Mock private RelatorioConformidadeService relatorioService;
  @Mock private CreditoService creditoService;
  @Mock private DashboardService dashboardService;
  @InjectMocks private CarteiraIndicadoresService service;

  @Test
  @DisplayName("autoriza o contador e so depois consulta, sempre pelo produtor do path")
  void autorizaAntesDeConsultar() {
    CreditoSaldoResponse saldo = new CreditoSaldoResponse("2026-09", BigDecimal.ONE,
        BigDecimal.ONE, BigDecimal.TWO, 1);
    when(creditoService.consultarSaldoDoProdutor(PRODUTOR, "2026-09")).thenReturn(saldo);

    assertThat(service.creditos(PRODUTOR, "2026-09")).isSameAs(saldo);

    InOrder ordem = inOrder(produtorEmContexto, creditoService);
    ordem.verify(produtorEmContexto).exigirContadorComAcesso(PRODUTOR);
    ordem.verify(creditoService).consultarSaldoDoProdutor(PRODUTOR, "2026-09");
  }

  @Test
  @DisplayName("fora da carteira: 404 sem tocar em nenhum servico de numero")
  void foraDaCarteiraNaoConsulta() {
    when(produtorEmContexto.exigirContadorComAcesso(PRODUTOR))
        .thenThrow(new ResourceNotFoundException("Produtor", PRODUTOR));

    assertThatThrownBy(() -> service.apuracaoDoMes(PRODUTOR, "2026-09", null, null, 6, null))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> service.apuracaoDaFaixa(PRODUTOR, null, null, 6,
        AgrupamentoApuracao.MES)).isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> service.relatorioConformidadePdf(PRODUTOR, "2026-09"))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> service.dashboard(PRODUTOR, null))
        .isInstanceOf(ResourceNotFoundException.class);

    verifyNoInteractions(apuracaoService, apuracaoFaixaService, relatorioService, creditoService,
        dashboardService, produtorRepository);
  }

  @Test
  @DisplayName("relatorio JSON e faixa: autorizacao antes; periodo + faixa conferido depois dela")
  void ordemNasDemaisRotas() {
    service.relatorioConformidade(PRODUTOR, "2026-09");
    service.apuracaoDaFaixa(PRODUTOR, "2026-01", "2026-06", null, null);
    service.apuracaoDoMes(PRODUTOR, "2026-09", null, null, null, null);

    InOrder ordem = inOrder(produtorEmContexto, relatorioService, apuracaoFaixaService,
        apuracaoService);
    ordem.verify(produtorEmContexto).exigirContadorComAcesso(PRODUTOR);
    ordem.verify(relatorioService).gerarDoProdutor(PRODUTOR, "2026-09");
    ordem.verify(produtorEmContexto).exigirContadorComAcesso(PRODUTOR);
    ordem.verify(apuracaoFaixaService).consultarDoProdutor(PRODUTOR, "2026-01", "2026-06", null,
        null);
    ordem.verify(produtorEmContexto).exigirContadorComAcesso(PRODUTOR);
    ordem.verify(apuracaoFaixaService).exigirSemFaixa(null, null, null, null);
    ordem.verify(apuracaoService).consultarParaProdutor(PRODUTOR, "2026-09");
  }

  @Test
  @DisplayName("PDF e dashboard recebem o cliente carregado (nome e UF no cabecalho)")
  void pdfComOCliente() {
    Produtor cliente = Produtor.builder().id(PRODUTOR).nomeCompleto("Cliente").estado("MT")
        .build();
    when(produtorRepository.findById(PRODUTOR)).thenReturn(Optional.of(cliente));
    when(relatorioService.gerarPdfDoProdutor(cliente, "2026-09")).thenReturn(new byte[] {1});

    assertThat(service.relatorioConformidadePdf(PRODUTOR, "2026-09")).containsExactly(1);
    InOrder ordem = inOrder(produtorEmContexto, produtorRepository);
    ordem.verify(produtorEmContexto).exigirContadorComAcesso(PRODUTOR);
    ordem.verify(produtorRepository).findById(PRODUTOR);
  }
}
