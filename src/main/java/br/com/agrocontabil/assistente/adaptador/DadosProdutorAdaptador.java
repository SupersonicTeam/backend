package br.com.agrocontabil.assistente.adaptador;

import br.com.agrocontabil.assistente.porta.DadosProdutorPorta;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.dto.AlertaTributarioResponse;
import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.repository.CreditoSaldoAgregado;
import br.com.agrocontabil.repository.NotaResumo;
import br.com.agrocontabil.repository.TotalAgregado;
import br.com.agrocontabil.repository.VencimentoGuiaResumo;
import br.com.agrocontabil.service.AlertaTributarioService;
import br.com.agrocontabil.service.ApuracaoService;
import br.com.agrocontabil.service.ComprasInsumoResultado;
import br.com.agrocontabil.service.ConsultaDadosProdutorService;
import br.com.agrocontabil.service.CreditoService;
import br.com.agrocontabil.service.RetencaoService;
import java.time.LocalDate;
import java.util.List;
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
  private final AlertaTributarioService alertaService;
  private final RetencaoService retencaoService;
  private final TransactionTemplate transacao;

  /** Constrói o adaptador sobre os services existentes. */
  public DadosProdutorAdaptador(ConsultaDadosProdutorService consultaService,
      ApuracaoService apuracaoService, CreditoService creditoService,
      AlertaTributarioService alertaService, RetencaoService retencaoService,
      PlatformTransactionManager transactionManager, AssistenteProperties propriedades) {
    this.consultaService = consultaService;
    this.apuracaoService = apuracaoService;
    this.creditoService = creditoService;
    this.alertaService = alertaService;
    this.retencaoService = retencaoService;
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
  public List<String> ncmsComprados(
      UUID produtorId, String descricaoOuNcm, LocalDate inicio, LocalDate fim, int limite) {
    return comTimeout(() ->
        consultaService.ncmsComprados(produtorId, descricaoOuNcm, inicio, fim, limite));
  }

  @Override
  public Pagina<Alerta> alertas(UUID produtorId) {
    List<AlertaTributarioResponse> todos =
        comTimeout(() -> alertaService.listarAtivos(produtorId));
    return new Pagina<>(todos.stream()
        .limit(LIMITE_MAXIMO)
        .map(a -> new Alerta(a.tipo(), a.descricao(), a.dataLimite(), a.diasRestantes()))
        .toList(), todos.size());
  }

  @Override
  public Apuracao apuracao(UUID produtorId, String periodo) {
    ApuracaoResponse a =
        comTimeout(() -> apuracaoService.consultarParaProdutor(produtorId, periodo));
    return new Apuracao(a.periodo(), a.status(), a.debitoIbs(), a.debitoCbs(), a.creditoIbs(),
        a.creditoCbs(), a.saldoIbs(), a.saldoCbs(), a.totalNotas());
  }

  @Override
  public Retencoes retencoes(UUID produtorId, LocalDate inicio, LocalDate fim) {
    RetencaoService.ResumoRetencoes r =
        comTimeout(() -> retencaoService.resumir(produtorId, inicio, fim));
    return new Retencoes(r.tipoProdutor() == TipoProdutor.PESSOA_FISICA,
        funrural(r.retidoPeloAdquirente(), r.notasRetidas()),
        funrural(r.recolhidoPeloProdutor(), r.notasSemRetencao()),
        r.ibsSplitPayment(), r.cbsSplitPayment(), r.notasVendidas());
  }

  private static Funrural funrural(RetencaoService.Funrural f, long notas) {
    return new Funrural(notas, f.base(), f.previdencia(), f.rat(), f.senar(), f.total());
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
  public Totais totalNotas(UUID produtorId, Set<OrigemNotaFiscal> origens,
      Set<StatusNotaFiscal> status, LocalDate inicio, LocalDate fim) {
    return totais(comTimeout(() ->
        consultaService.totalizarNotas(produtorId, origens, status, inicio, fim)));
  }

  @Override
  public Totais totalVencimentosNaoPagos(UUID produtorId, LocalDate de, LocalDate ate) {
    return totais(comTimeout(() ->
        consultaService.totalizarVencimentosNaoPagos(produtorId, de, ate)));
  }

  private static Totais totais(TotalAgregado t) {
    return new Totais(t.quantidade(), t.valor());
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
