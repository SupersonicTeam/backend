package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.VencimentoFiscalResponse;
import br.com.agrocontabil.exception.JanelaCalendarioInvalidaException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.mapper.GuiaFiscalMapper;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
import br.com.agrocontabil.security.UsuarioAutenticado;
import br.com.agrocontabil.security.UsuarioAutenticadoProvider;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monta o calendário fiscal do produtor (issue #74 — {@code GET
 * /api/calendario-fiscal/{produtorId}/vencimentos}).
 *
 * <p>Fecha a lacuna que já quebrava a tela de Calendário do Angular: o {@code CalendarioApi} do
 * frontend chama este endpoint sem fallback mock (erro direto se 404). Fontes: as guias do produtor
 * ({@code tipo = "imposto"}) e, desde a issue #181, as ocorrências das obrigações do catálogo dos
 * escritórios que o atendem mais o fechamento da apuração ({@code tipo = "obrigacao"},
 * {@link ObrigacaoFiscalService}). O produtor vê as obrigações de todos os escritórios ativos dele;
 * o contador, só as do próprio escritório (o catálogo de outro escritório não é dele).
 *
 * <p><strong>Anti-IDOR:</strong> o {@code produtorId} do path precisa ser acessível pela identidade
 * autenticada — o próprio produtor, ou um produtor da carteira do contador (issue #93). Quem decide
 * é o {@link ProdutorEmContextoResolver}; caso contrário, 404 (nunca 403 — não revela existência),
 * mesmo padrão usado em toda a API.
 */
@Service
@Transactional(readOnly = true)
public class CalendarioFiscalService {

  /**
   * Meses antes e depois do mês corrente cobertos pela janela padrão (issue #103). O Angular
   * carrega o calendário uma vez e navega os meses no cliente; ±12 meses cobrem essa navegação sem
   * devolver o histórico inteiro.
   */
  public static final int MESES_JANELA_PADRAO = 12;

  /** Tamanho máximo, em meses, de uma janela pedida com {@code de}/{@code ate} (issue #103). */
  public static final int MESES_JANELA_MAXIMA = 36;

  private final GuiaFiscalRepository guiaFiscalRepository;
  private final ProdutorEmContextoResolver produtorEmContexto;
  private final UsuarioAutenticadoProvider usuarioProvider;
  private final VinculoCarteiraRepository vinculoRepository;
  private final ObrigacaoFiscalService obrigacaoService;
  private final Clock clock;

  /**
   * Constrói o serviço com o repositório de guias, o resolvedor do produtor-alvo, as fontes das
   * obrigações (#181) e o {@link Clock} do projeto ({@code America/Sao_Paulo}).
   */
  public CalendarioFiscalService(
      GuiaFiscalRepository guiaFiscalRepository,
      ProdutorEmContextoResolver produtorEmContexto,
      UsuarioAutenticadoProvider usuarioProvider,
      VinculoCarteiraRepository vinculoRepository,
      ObrigacaoFiscalService obrigacaoService,
      Clock clock) {
    this.guiaFiscalRepository = guiaFiscalRepository;
    this.produtorEmContexto = produtorEmContexto;
    this.usuarioProvider = usuarioProvider;
    this.vinculoRepository = vinculoRepository;
    this.obrigacaoService = obrigacaoService;
    this.clock = clock;
  }

  /**
   * Retorna os vencimentos fiscais do produtor numa janela de datas, mapeados a partir das guias.
   *
   * <p><strong>Janela (issue #103):</strong> antes devolvia todas as guias do produtor, sem limite.
   * {@code de} ausente vira o 1º dia do mês corrente − {@value #MESES_JANELA_PADRAO} meses;
   * {@code ate} ausente vira o último dia do mês corrente + {@value #MESES_JANELA_PADRAO} meses
   * (fuso {@code America/Sao_Paulo}). Os dois extremos são inclusivos.
   *
   * @param produtorId identificador do produtor; deve ser acessivel pela identidade autenticada
   * @param de         primeiro dia da janela, ou {@code null} para o padrão
   * @param ate        último dia da janela, ou {@code null} para o padrão
   * @return vencimentos do produtor na janela, mais recentes primeiro (vazia se nenhum)
   * @throws ResourceNotFoundException se o produtorId não for acessível pela identidade
   *     autenticada (anti-IDOR — 404, nunca 403)
   * @throws JanelaCalendarioInvalidaException se {@code de} for posterior a {@code ate} ou a
   *     janela passar de {@value #MESES_JANELA_MAXIMA} meses (400)
   */
  public List<VencimentoFiscalResponse> listarVencimentos(
      UUID produtorId, LocalDate de, LocalDate ate) {
    Produtor produtor = produtorEmContexto.resolver(produtorId);

    YearMonth mesCorrente = YearMonth.now(clock);
    LocalDate inicio = de != null ? de : mesCorrente.minusMonths(MESES_JANELA_PADRAO).atDay(1);
    LocalDate fim = ate != null ? ate : mesCorrente.plusMonths(MESES_JANELA_PADRAO).atEndOfMonth();
    if (inicio.isAfter(fim)) {
      throw JanelaCalendarioInvalidaException.inicioDepoisDoFim(inicio, fim);
    }
    // until(MONTHS) conta meses completos: nao estoura em datas extremas (plusMonths estouraria) e
    // segue a semantica de fim de mes (29/02 -> 28/02 de 3 anos depois ainda cabe em 36 meses).
    if (inicio.until(fim, ChronoUnit.MONTHS) >= MESES_JANELA_MAXIMA) {
      throw JanelaCalendarioInvalidaException.excedeMaximo(inicio, fim, MESES_JANELA_MAXIMA);
    }

    List<VencimentoFiscalResponse> itens = new ArrayList<>(
        guiaFiscalRepository.findVencimentosNaJanela(produtorId, inicio, fim).stream()
            .map(GuiaFiscalMapper::toVencimento)
            .toList());
    UsuarioAutenticado usuario = usuarioProvider.get();
    List<UUID> escritorios = usuario.isContador()
        ? List.of(usuario.contador().getEscritorioId())
        : vinculoRepository.findEscritoriosAtivosDoProdutor(produtorId);
    itens.addAll(obrigacaoService.ocorrencias(produtor, escritorios, inicio, fim));
    // Mais recente primeiro, como a lista de guias ja vinha (findVencimentosNaJanela).
    itens.sort(Comparator.comparing(VencimentoFiscalResponse::dataVencimento).reversed()
        .thenComparing(VencimentoFiscalResponse::tipo)
        .thenComparing(VencimentoFiscalResponse::id));
    return itens;
  }
}
