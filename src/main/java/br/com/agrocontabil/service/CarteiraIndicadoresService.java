package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.dto.CreditoSaldoResponse;
import br.com.agrocontabil.dto.DashboardResponse;
import br.com.agrocontabil.dto.RelatorioConformidadeResponse;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.AgrupamentoApuracao;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Leituras de número de um cliente pelo escritório (issue #255), em
 * {@code /api/contador/produtores/{produtorId}/...}: apuração, relatório de conformidade, créditos
 * e dashboard.
 *
 * <p>Mesmo desenho do {@link CarteiraNotasService}: a autorização vem primeiro e sempre pelo
 * {@link ProdutorEmContextoResolver#exigirContadorComAcesso} (produtor 403; fora da carteira
 * ativa, vínculo suspenso ou encerrado 404); só depois a consulta, delegada às variantes
 * {@code ...DoProdutor} dos services do produtor — a regra de cálculo é uma só para as duas rotas.
 */
@Service
public class CarteiraIndicadoresService {

  private final ProdutorEmContextoResolver produtorEmContexto;
  private final ProdutorRepository produtorRepository;
  private final ApuracaoService apuracaoService;
  private final ApuracaoFaixaService apuracaoFaixaService;
  private final RelatorioConformidadeService relatorioService;
  private final CreditoService creditoService;
  private final DashboardService dashboardService;

  public CarteiraIndicadoresService(
      ProdutorEmContextoResolver produtorEmContexto,
      ProdutorRepository produtorRepository,
      ApuracaoService apuracaoService,
      ApuracaoFaixaService apuracaoFaixaService,
      RelatorioConformidadeService relatorioService,
      CreditoService creditoService,
      DashboardService dashboardService) {
    this.produtorEmContexto = produtorEmContexto;
    this.produtorRepository = produtorRepository;
    this.apuracaoService = apuracaoService;
    this.apuracaoFaixaService = apuracaoFaixaService;
    this.relatorioService = relatorioService;
    this.creditoService = creditoService;
    this.dashboardService = dashboardService;
  }

  /**
   * Apuração de um mês do cliente, como {@code GET /api/apuracao?periodo=}: {@code periodo} vem
   * sozinho (com faixa junto → 400 {@code FAIXA_APURACAO_INVALIDA}). A combinação só é conferida
   * depois da autorização, para quem está fora da carteira receber 404 e não 400.
   *
   * @param produtorId  cliente da carteira ativa
   * @param de          início da faixa (deve ser nulo)
   * @param ate         fim da faixa (deve ser nulo)
   * @param ultimos     atalho de faixa (deve ser nulo)
   * @param agrupamento agrupamento (deve ser nulo)
   * @param periodo     mês {@code YYYY-MM}
   * @return a apuração do mês
   */
  public ApuracaoResponse apuracaoDoMes(UUID produtorId, String periodo, String de, String ate,
      Integer ultimos, AgrupamentoApuracao agrupamento) {
    produtorEmContexto.exigirContadorComAcesso(produtorId);
    apuracaoFaixaService.exigirSemFaixa(de, ate, ultimos, agrupamento);
    return apuracaoService.consultarParaProdutor(produtorId, periodo);
  }

  /**
   * Apuração de uma faixa de meses do cliente, como {@code GET /api/apuracao?de=&ate=} ou
   * {@code ?ultimos=}, opcionalmente agrupada.
   *
   * @param produtorId  cliente da carteira ativa
   * @param de          primeiro mês ({@code YYYY-MM})
   * @param ate         último mês ({@code YYYY-MM})
   * @param ultimos     6, 12, 36 ou 60
   * @param agrupamento {@code MES} (padrão), {@code TRIMESTRE}, {@code SEMESTRE} ou {@code ANO}
   * @return um item por grupo, em ordem crescente
   */
  public List<ApuracaoResponse> apuracaoDaFaixa(UUID produtorId, String de, String ate,
      Integer ultimos, AgrupamentoApuracao agrupamento) {
    produtorEmContexto.exigirContadorComAcesso(produtorId);
    return apuracaoFaixaService.consultarDoProdutor(produtorId, de, ate, ultimos, agrupamento);
  }

  /**
   * Relatório de conformidade do cliente no mês (JSON).
   *
   * @param produtorId cliente da carteira ativa
   * @param periodo    mês {@code YYYY-MM}
   * @return débitos × créditos × saldo
   */
  public RelatorioConformidadeResponse relatorioConformidade(UUID produtorId, String periodo) {
    produtorEmContexto.exigirContadorComAcesso(produtorId);
    return relatorioService.gerarDoProdutor(produtorId, periodo);
  }

  /**
   * Relatório de conformidade do cliente no mês, em PDF.
   *
   * @param produtorId cliente da carteira ativa
   * @param periodo    mês {@code YYYY-MM}
   * @return bytes do PDF
   */
  public byte[] relatorioConformidadePdf(UUID produtorId, String periodo) {
    return relatorioService.gerarPdfDoProdutor(cliente(produtorId), periodo);
  }

  /**
   * Saldo de crédito de IBS/CBS do cliente no mês.
   *
   * @param produtorId cliente da carteira ativa
   * @param periodo    mês {@code YYYY-MM}
   * @return saldo do período
   */
  public CreditoSaldoResponse creditos(UUID produtorId, String periodo) {
    produtorEmContexto.exigirContadorComAcesso(produtorId);
    return creditoService.consultarSaldoDoProdutor(produtorId, periodo);
  }

  /**
   * Dashboard do cliente no mês (ou no corrente).
   *
   * @param produtorId cliente da carteira ativa
   * @param periodo    mês {@code YYYY-MM}; nulo = mês corrente
   * @return o painel do cliente
   */
  public DashboardResponse dashboard(UUID produtorId, String periodo) {
    return dashboardService.montarDoProdutor(cliente(produtorId), periodo);
  }

  /** Autoriza e carrega o cliente (o PDF e o dashboard levam nome, tipo e UF dele). */
  private Produtor cliente(UUID produtorId) {
    produtorEmContexto.exigirContadorComAcesso(produtorId);
    return produtorRepository.findById(produtorId)
        .orElseThrow(() -> new ResourceNotFoundException("Produtor", produtorId));
  }
}
