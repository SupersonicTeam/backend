package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.AlertaTributarioResponse;
import br.com.agrocontabil.mapper.AlertaTributarioMapper;
import br.com.agrocontabil.model.AlertaTributario;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusAlertaTributario;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.TipoAlertaTributario;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.repository.AlertaTributarioRepository;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.PrazoCancelamentoCandidato;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alertas tributários proativos (issue #140, RN010): avisa o produtor quando falta
 * <strong>menos de {@value #DIAS_ANTECEDENCIA} dias</strong> — de hoje até hoje + 4, em
 * {@code America/Sao_Paulo} — para:
 * <ul>
 *   <li>o vencimento de uma guia fiscal pendente;</li>
 *   <li>o fechamento automático da apuração do mês (último dia, RN004), para quem tem nota
 *       autorizada no mês;</li>
 *   <li>o fim do prazo de cancelamento de uma NF-e própria autorizada (emissão + 30 dias, RN006,
 *       a mesma constante do {@link SefazService}).</li>
 * </ul>
 *
 * <p>O job ({@code AlertaTributarioJob}) grava os alertas e resolve os obsoletos; a leitura confere
 * de novo, na consulta, que o motivo ainda existe (ver {@link AlertaTributarioRepository}).
 */
@Service
public class AlertaTributarioService {

  /** "Faltar menos de 5 dias": o prazo cai entre hoje e hoje + 4. */
  public static final int DIAS_ANTECEDENCIA = 5;

  /** Maior lista que a tela e a Ceres recebem. */
  public static final int LIMITE_LISTA = 50;

  private static final Logger log = LoggerFactory.getLogger(AlertaTributarioService.class);
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
  private static final Locale PT_BR = Locale.of("pt", "BR");

  private final AlertaTributarioRepository alertaRepository;
  private final GuiaFiscalRepository guiaRepository;
  private final NotaFiscalRepository notaRepository;
  private final ProdutorRepository produtorRepository;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final AlertaTributarioMapper mapper;
  private final Clock clock;

  /** Constrói o serviço sobre os repositórios dos recursos que geram alerta. */
  public AlertaTributarioService(AlertaTributarioRepository alertaRepository,
      GuiaFiscalRepository guiaRepository, NotaFiscalRepository notaRepository,
      ProdutorRepository produtorRepository,
      AuthenticatedProdutorProvider produtorProvider,
      AlertaTributarioMapper mapper, Clock clock) {
    this.alertaRepository = alertaRepository;
    this.guiaRepository = guiaRepository;
    this.notaRepository = notaRepository;
    this.produtorRepository = produtorRepository;
    this.produtorProvider = produtorProvider;
    this.mapper = mapper;
    this.clock = clock;
  }

  /**
   * Resolve os alertas obsoletos e grava os novos. Idempotente: rodar de novo no mesmo dia não
   * duplica (e a chave única da tabela é a rede de segurança).
   *
   * <p>Uma transação para a execução inteira, com teto próprio
   * ({@code app.alertas.transaction-timeout}, padrão 600 s): o padrão da aplicação (30 s) não
   * cobre a varredura de todos os produtores, mesmo motivo do {@code GuiaFiscalStatusSyncJob}. O
   * volume é limitado pela janela de cinco dias.
   *
   * @return quantos foram criados e quantos resolvidos
   */
  @Transactional(timeoutString = "${app.alertas.transaction-timeout:600}")
  public ResumoAlertas gerarAlertas() {
    LocalDate hoje = LocalDate.now(clock.withZone(ZONE));
    LocalDate ultimoDia = hoje.plusDays(DIAS_ANTECEDENCIA - 1);
    OffsetDateTime agora = OffsetDateTime.now(clock.withZone(ZONE));

    int resolvidos =
        alertaRepository.resolverObsoletos(hoje, YearMonth.from(hoje).toString(), agora);
    int criados = 0;

    for (GuiaFiscal guia : guiaRepository.findByStatusAndDataVencimentoBetween(
        StatusGuiaFiscal.PENDENTE, hoje, ultimoDia)) {
      criados += criar(guia.getProdutor(), TipoAlertaTributario.VENCIMENTO_GUIA, guia.getId(),
          guia.getDataVencimento(),
          "Guia de " + guia.getTipoGuia().name() + " de " + moeda(guia.getValor()), agora);
    }

    // RN006: emissao + 30 dias. Prazo em [hoje, ultimoDia] = emissao 30 dias antes de cada um.
    OffsetDateTime emitidaDesde = hoje.minusDays(SefazService.PRAZO_CANCELAMENTO_DIAS)
        .atStartOfDay(ZONE).toOffsetDateTime();
    OffsetDateTime emitidaAte = ultimoDia.plusDays(1)
        .minusDays(SefazService.PRAZO_CANCELAMENTO_DIAS)
        .atStartOfDay(ZONE).toOffsetDateTime();
    for (PrazoCancelamentoCandidato nota : notaRepository.findPropriasAutorizadasEmitidasEntre(
        emitidaDesde, emitidaAte)) {
      LocalDate prazo = nota.dataEmissao().atZoneSameInstant(ZONE).toLocalDate()
          .plusDays(SefazService.PRAZO_CANCELAMENTO_DIAS);
      String documento = nota.tipoDocumento() == TipoDocumentoFiscal.NFA ? "NFA" : "NF-e";
      // Referencia sem consulta: o alerta so precisa do id do produtor para a FK.
      criados += criar(produtorRepository.getReferenceById(nota.produtorId()),
          TipoAlertaTributario.PRAZO_CANCELAMENTO_NFE, nota.id(), prazo,
          "Prazo para cancelar a " + documento + " " + nota.numero() + ", serie " + nota.serie(),
          agora);
    }

    // RN004: a apuracao fecha no ultimo dia do mes. Candidatos: quem tem nota autorizada no mes.
    // Nao da para partir de apuracao_mensal: o mes corrente e projecao ao vivo (#53) e a linha so
    // nasce no proprio fechamento. A referencia e o produtor (uma apuracao por produtor e mes).
    YearMonth mes = YearMonth.from(hoje);
    LocalDate fechamento = mes.atEndOfMonth();
    if (!fechamento.isAfter(ultimoDia)) {
      for (UUID produtorId : notaRepository.findProdutoresComNotaAutorizadaEntre(
          mes.atDay(1).atStartOfDay(ZONE).toOffsetDateTime(),
          fechamento.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime())) {
        criados += criar(produtorRepository.getReferenceById(produtorId),
            TipoAlertaTributario.FECHAMENTO_APURACAO, produtorId, fechamento,
            "Fechamento da apuracao de " + mes, agora);
      }
    }

    log.info("Alertas tributarios: {} criado(s), {} resolvido(s)", criados, resolvidos);
    return new ResumoAlertas(criados, resolvidos);
  }

  /**
   * Alertas do produtor autenticado ({@code GET /api/alertas}). Contador recebe 403 no provider:
   * o endpoint ainda não recebe produtor-alvo (mesma regra do dashboard, issue #93).
   *
   * @return no máximo {@link #LIMITE_LISTA} alertas, do mais urgente ao mais distante
   */
  @Transactional(readOnly = true)
  public List<AlertaTributarioResponse> listarDoProdutorAutenticado() {
    return listarAtivos(produtorProvider.get().getId());
  }

  /**
   * Alertas ativos e válidos do produtor, do mais urgente ao mais distante.
   *
   * @param produtorId produtor já autorizado por quem chama (sessão ou contexto da Ceres) —
   *                   nunca vindo do cliente sem passar pela autorização
   * @return no máximo {@link #LIMITE_LISTA} alertas
   */
  @Transactional(readOnly = true)
  public List<AlertaTributarioResponse> listarAtivos(UUID produtorId) {
    LocalDate hoje = LocalDate.now(clock.withZone(ZONE));
    return alertaRepository.listarAtivos(produtorId, hoje, YearMonth.from(hoje).toString(),
            PageRequest.of(0, LIMITE_LISTA))
        .stream()
        .map(alerta -> mapper.toResponse(alerta, hoje))
        .toList();
  }

  private int criar(Produtor produtor, TipoAlertaTributario tipo, UUID referenciaId,
      LocalDate dataLimite, String descricao, OffsetDateTime agora) {
    if (alertaRepository.existsByTipoAndReferenciaIdAndDataLimite(tipo, referenciaId, dataLimite)) {
      return 0;
    }
    alertaRepository.save(AlertaTributario.builder()
        .produtor(produtor)
        .tipo(tipo)
        .referenciaId(referenciaId)
        .dataLimite(dataLimite)
        .descricao(descricao)
        .status(StatusAlertaTributario.ATIVO)
        .criadoEm(agora)
        .build());
    return 1;
  }

  private static String moeda(BigDecimal valor) {
    BigDecimal v = valor == null ? BigDecimal.ZERO : valor.setScale(2, RoundingMode.HALF_UP);
    return "R$ " + new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(PT_BR)).format(v);
  }

  /**
   * Resultado de uma execução do job.
   *
   * @param criados    alertas novos
   * @param resolvidos alertas que deixaram de valer
   */
  public record ResumoAlertas(int criados, int resolvidos) {}
}
