package br.com.agrocontabil.assistente.adaptador;

import br.com.agrocontabil.assistente.porta.DadosProdutorPorta;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.CreditoSaldoAgregado;
import br.com.agrocontabil.repository.NotaResumo;
import br.com.agrocontabil.repository.VencimentoGuiaResumo;
import br.com.agrocontabil.service.ApuracaoService;
import br.com.agrocontabil.service.ComprasInsumoResultado;
import br.com.agrocontabil.service.ConsultaDadosProdutorService;
import br.com.agrocontabil.service.CreditoService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Implementa a {@link DadosProdutorPorta} sobre os services do backend (issue #141).
 *
 * <p>Só traduz: chama o service certo e converte o resultado para os records da porta. Nenhuma
 * regra fiscal nem de acesso vive aqui — o recorte (entradas autorizadas, competência em
 * {@code America/Sao_Paulo}) e o filtro por produtor estão nos services e nas queries. O
 * {@code ArquiteturaAssistenteTest} impede este adaptador de pular os services e ir direto ao
 * repository.
 *
 * <p><strong>Timeout por função (issue #157):</strong> cada consulta roda numa transação curta com
 * timeout de {@code app.assistente.timeout-funcao}. A transação do service entra nela
 * ({@code REQUIRED}) e o Spring aplica o tempo restante como timeout das queries — uma consulta
 * lenta é cancelada no banco em vez de prender a conexão. A falha volta ao modelo como mensagem
 * genérica pelo {@code ProcessadorErroFerramenta}. O adaptador é o único lugar do módulo onde isso
 * é permitido: a transação cobre só a consulta, nunca a chamada ao modelo.
 */
@Component
public class DadosProdutorAdaptador implements DadosProdutorPorta {

  private final ConsultaDadosProdutorService consultaService;
  private final ApuracaoService apuracaoService;
  private final CreditoService creditoService;
  private final TransactionTemplate transacao;

  /** Constrói o adaptador sobre os services existentes. */
  public DadosProdutorAdaptador(ConsultaDadosProdutorService consultaService,
      ApuracaoService apuracaoService, CreditoService creditoService,
      PlatformTransactionManager transactionManager, AssistenteProperties propriedades) {
    this.consultaService = consultaService;
    this.apuracaoService = apuracaoService;
    this.creditoService = creditoService;
    this.transacao = new TransactionTemplate(transactionManager);
    this.transacao.setTimeout((int) propriedades.getTimeoutFuncao().toSeconds());
  }

  @Override
  public ComprasInsumo comprasPorNcm(
      UUID produtorId, String prefixoNcm, LocalDate inicio, LocalDate fim) {
    return comTimeout(() ->
        compras(consultaService.comprasPorNcm(produtorId, prefixoNcm, inicio, fim)));
  }

  @Override
  public ComprasInsumo comprasPorDescricao(
      UUID produtorId, String termo, LocalDate inicio, LocalDate fim) {
    return comTimeout(() ->
        compras(consultaService.comprasPorDescricao(produtorId, termo, inicio, fim)));
  }

  @Override
  public Apuracao apuracao(UUID produtorId, String periodo) {
    ApuracaoResponse a =
        comTimeout(() -> apuracaoService.consultarParaProdutor(produtorId, periodo));
    return new Apuracao(a.periodo(), a.status(), a.debitoIbs(), a.debitoCbs(), a.creditoIbs(),
        a.creditoCbs(), a.saldoIbs(), a.saldoCbs(), a.totalNotas());
  }

  @Override
  public Creditos creditos(UUID produtorId, LocalDate inicio, LocalDate fim) {
    CreditoSaldoAgregado c =
        comTimeout(() -> creditoService.somarCreditosNoIntervalo(produtorId, inicio, fim));
    return new Creditos(c.creditoIbs(), c.creditoCbs(), c.quantidadeNotas());
  }

  @Override
  public Pagina<Nota> notas(UUID produtorId, Set<OrigemNotaFiscal> origens,
      Set<StatusNotaFiscal> status, LocalDate inicio, LocalDate fim, int limite) {
    Page<NotaResumo> pagina = comTimeout(() ->
        consultaService.listarNotas(produtorId, origens, status, inicio, fim, limite));
    return new Pagina<>(pagina.getContent().stream()
        .map(n -> new Nota(n.numero(), n.serie(), n.dataEmissao(), n.origem(), n.status(),
            n.valorTotal(), n.valorIbs(), n.valorCbs()))
        .toList(), pagina.getTotalElements());
  }

  @Override
  public BigDecimal valorTotalNotas(UUID produtorId, Set<OrigemNotaFiscal> origens,
      Set<StatusNotaFiscal> status, LocalDate inicio, LocalDate fim) {
    return comTimeout(() -> consultaService.somarNotas(produtorId, origens, status, inicio, fim));
  }

  @Override
  public BigDecimal valorTotalVencimentosNaoPagos(UUID produtorId, LocalDate de, LocalDate ate) {
    return comTimeout(() -> consultaService.somarVencimentosNaoPagos(produtorId, de, ate));
  }

  @Override
  public Pagina<Vencimento> vencimentosNaoPagos(
      UUID produtorId, LocalDate de, LocalDate ate, int limite) {
    Page<VencimentoGuiaResumo> pagina =
        comTimeout(() -> consultaService.vencimentosNaoPagos(produtorId, de, ate, limite));
    return new Pagina<>(pagina.getContent().stream()
        .map(g -> new Vencimento(g.tipo(), g.dataVencimento(), g.valor(), g.status()))
        .toList(), pagina.getTotalElements());
  }

  private <T> T comTimeout(Supplier<T> consulta) {
    return transacao.execute(status -> consulta.get());
  }

  private static ComprasInsumo compras(ComprasInsumoResultado r) {
    return new ComprasInsumo(r.valorTotal(), r.quantidadeNotas(), r.porUnidade().stream()
        .map(u -> new CompraUnidade(u.unidade(), u.quantidade(), u.valorTotal()))
        .toList());
  }
}
