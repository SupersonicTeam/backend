package br.com.agrocontabil.assistente.adaptador;

import br.com.agrocontabil.assistente.porta.DadosProdutorPorta;
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
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * Implementa a {@link DadosProdutorPorta} sobre os services do backend (issue #141).
 *
 * <p>Só traduz: chama o service certo e converte o resultado para os records da porta. Nenhuma
 * regra fiscal nem de acesso vive aqui — o recorte (entradas autorizadas, competência em
 * {@code America/Sao_Paulo}) e o filtro por produtor estão nos services e nas queries. O
 * {@code ArquiteturaAssistenteTest} impede este adaptador de pular os services e ir direto ao
 * repository.
 */
@Component
public class DadosProdutorAdaptador implements DadosProdutorPorta {

  private final ConsultaDadosProdutorService consultaService;
  private final ApuracaoService apuracaoService;
  private final CreditoService creditoService;

  /** Constrói o adaptador sobre os services existentes. */
  public DadosProdutorAdaptador(ConsultaDadosProdutorService consultaService,
      ApuracaoService apuracaoService, CreditoService creditoService) {
    this.consultaService = consultaService;
    this.apuracaoService = apuracaoService;
    this.creditoService = creditoService;
  }

  @Override
  public ComprasInsumo comprasPorNcm(
      UUID produtorId, String prefixoNcm, LocalDate inicio, LocalDate fim) {
    return compras(consultaService.comprasPorNcm(produtorId, prefixoNcm, inicio, fim));
  }

  @Override
  public ComprasInsumo comprasPorDescricao(
      UUID produtorId, String termo, LocalDate inicio, LocalDate fim) {
    return compras(consultaService.comprasPorDescricao(produtorId, termo, inicio, fim));
  }

  @Override
  public Apuracao apuracao(UUID produtorId, String periodo) {
    ApuracaoResponse a = apuracaoService.consultarParaProdutor(produtorId, periodo);
    return new Apuracao(a.periodo(), a.status(), a.debitoIbs(), a.debitoCbs(), a.creditoIbs(),
        a.creditoCbs(), a.saldoIbs(), a.saldoCbs(), a.totalNotas());
  }

  @Override
  public Creditos creditos(UUID produtorId, LocalDate inicio, LocalDate fim) {
    CreditoSaldoAgregado c = creditoService.somarCreditosNoIntervalo(produtorId, inicio, fim);
    return new Creditos(c.creditoIbs(), c.creditoCbs(), c.quantidadeNotas());
  }

  @Override
  public Pagina<Nota> notas(UUID produtorId, Set<OrigemNotaFiscal> origens,
      Set<StatusNotaFiscal> status, LocalDate inicio, LocalDate fim, int limite) {
    Page<NotaResumo> pagina =
        consultaService.listarNotas(produtorId, origens, status, inicio, fim, limite);
    return new Pagina<>(pagina.getContent().stream()
        .map(n -> new Nota(n.numero(), n.serie(), n.dataEmissao(), n.origem(), n.status(),
            n.valorTotal(), n.valorIbs(), n.valorCbs()))
        .toList(), pagina.getTotalElements());
  }

  @Override
  public Pagina<Vencimento> vencimentosNaoPagos(
      UUID produtorId, LocalDate de, LocalDate ate, int limite) {
    Page<VencimentoGuiaResumo> pagina =
        consultaService.vencimentosNaoPagos(produtorId, de, ate, limite);
    return new Pagina<>(pagina.getContent().stream()
        .map(g -> new Vencimento(g.tipo(), g.dataVencimento(), g.valor(), g.status()))
        .toList(), pagina.getTotalElements());
  }

  private static ComprasInsumo compras(ComprasInsumoResultado r) {
    return new ComprasInsumo(r.valorTotal(), r.quantidadeNotas(), r.porUnidade().stream()
        .map(u -> new CompraUnidade(u.unidade(), u.quantidade(), u.valorTotal()))
        .toList());
  }
}
