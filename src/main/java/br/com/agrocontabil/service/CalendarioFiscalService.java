package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.VencimentoFiscalResponse;
import br.com.agrocontabil.exception.JanelaCalendarioInvalidaException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.mapper.GuiaFiscalMapper;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monta o calendário fiscal do produtor (issue #74 — {@code GET
 * /api/calendario-fiscal/{produtorId}/vencimentos}).
 *
 * <p>Fecha a lacuna que já quebrava a tela de Calendário do Angular: o {@code CalendarioApi} do
 * frontend chama este endpoint sem fallback mock (erro direto se 404). Nesta entrega a única
 * fonte de vencimento é a {@link br.com.agrocontabil.model.GuiaFiscal} do produtor — reúsa
 * {@link GuiaFiscalRepository}, sem nova tabela. Outras fontes de prazo fiscal (obrigações,
 * declarações) ficam para quando o backend as modelar.
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
  private final Clock clock;

  /**
   * Constrói o serviço com o repositório de guias, o provedor do produtor autenticado e o
   * {@link Clock} do projeto ({@code America/Sao_Paulo}), que define o mês corrente da janela.
   */
  public CalendarioFiscalService(
      GuiaFiscalRepository guiaFiscalRepository,
      ProdutorEmContextoResolver produtorEmContexto,
      Clock clock) {
    this.guiaFiscalRepository = guiaFiscalRepository;
    this.produtorEmContexto = produtorEmContexto;
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
    produtorEmContexto.exigirAcesso(produtorId);

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

    return guiaFiscalRepository.findVencimentosNaJanela(produtorId, inicio, fim).stream()
        .map(GuiaFiscalMapper::toVencimento)
        .toList();
  }
}
