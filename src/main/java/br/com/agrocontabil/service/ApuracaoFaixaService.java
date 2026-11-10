package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.exception.FaixaApuracaoInvalidaException;
import br.com.agrocontabil.model.AgrupamentoApuracao;
import br.com.agrocontabil.model.ApuracaoMensal;
import br.com.agrocontabil.model.StatusApuracao;
import br.com.agrocontabil.repository.ApuracaoMensalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Apuração de uma faixa de meses do produtor autenticado (issue #179 — {@code GET /api/apuracao}
 * com {@code de}/{@code ate} ou {@code ultimos}).
 *
 * <p>Mesmo critério da consulta de um mês ({@link ApuracaoService#consultar(String)}): mês
 * consolidado devolve o que foi persistido; mês não consolidado, a projeção ao vivo das notas. A
 * diferença é o custo: <strong>uma</strong> consulta para as apurações persistidas da faixa e
 * <strong>uma</strong> agregação {@code GROUP BY mês} para os meses que faltam, nunca uma consulta
 * por mês (60 meses seriam 120 agregações). Mês sem nota entra com zeros e {@code totalNotas = 0}:
 * a faixa não tem buraco.
 */
@Service
@Transactional(readOnly = true)
public class ApuracaoFaixaService {

  /** Teto da faixa: 5 anos, a mesma guarda legal dos documentos fiscais. */
  static final int MESES_MAXIMO = 60;

  /** Atalhos de {@code ultimos}: semestre, 1, 3 e 5 anos. */
  static final Set<Integer> ULTIMOS_PERMITIDOS = Set.of(6, 12, 36, 60);

  private static final BigDecimal ZERO = ApuracaoMes.valor(null);

  private final ApuracaoMensalRepository apuracaoRepository;
  private final NotaFiscalRepository notaFiscalRepository;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final Clock clock;

  /** Constrói o serviço com os repositórios, o produtor autenticado e o relógio de Brasília. */
  public ApuracaoFaixaService(
      ApuracaoMensalRepository apuracaoRepository,
      NotaFiscalRepository notaFiscalRepository,
      AuthenticatedProdutorProvider produtorProvider,
      Clock clock) {
    this.apuracaoRepository = apuracaoRepository;
    this.notaFiscalRepository = notaFiscalRepository;
    this.produtorProvider = produtorProvider;
    this.clock = clock;
  }

  /**
   * Recusa faixa junto com {@code periodo}: a consulta de um mês só tem um significado.
   *
   * @throws FaixaApuracaoInvalidaException se algum parâmetro de faixa veio junto
   */
  public void exigirSemFaixa(String de, String ate, Integer ultimos,
      AgrupamentoApuracao agrupamento) {
    if (de != null || ate != null || ultimos != null || agrupamento != null) {
      throw new FaixaApuracaoInvalidaException("periodo",
          "Use 'periodo' sozinho ou uma faixa ('de'/'ate' ou 'ultimos'), não os dois.",
          "Para um mês, informe só 'periodo'; para vários, 'de'/'ate' ou 'ultimos'.", null,
          "'periodo' OU 'de'/'ate' OU 'ultimos'");
    }
  }

  /**
   * Apuração da faixa, mês a mês ou agrupada, em ordem crescente.
   *
   * @param de          primeiro mês ({@code YYYY-MM}), junto com {@code ate}
   * @param ate         último mês ({@code YYYY-MM}), junto com {@code de}
   * @param ultimos     atalho: 6, 12, 36 ou 60 meses terminando no mês corrente
   * @param agrupamento {@code MES} (padrão), {@code TRIMESTRE}, {@code SEMESTRE} ou {@code ANO}
   * @return um item por grupo
   * @throws FaixaApuracaoInvalidaException combinação, ordem, tamanho ou atalho inválidos (400)
   */
  public List<ApuracaoResponse> consultar(String de, String ate, Integer ultimos,
      AgrupamentoApuracao agrupamento) {
    YearMonth[] faixa = resolverFaixa(de, ate, ultimos);
    return consultarFaixa(produtorProvider.get().getId(), faixa, agrupamento);
  }

  /**
   * Mesma consulta de {@link #consultar}, para um produtor já autorizado pelo chamador — a apuração
   * de um cliente da carteira (issue #255).
   *
   * <p><strong>Contrato de segurança:</strong> {@code produtorId} tem de ter passado pelo
   * {@code ProdutorEmContextoResolver} antes; aqui ele é só o filtro das consultas.
   *
   * @param produtorId  produtor já autorizado
   * @param de          primeiro mês ({@code YYYY-MM}), junto com {@code ate}
   * @param ate         último mês ({@code YYYY-MM}), junto com {@code de}
   * @param ultimos     atalho: 6, 12, 36 ou 60 meses terminando no mês corrente
   * @param agrupamento {@code MES} (padrão), {@code TRIMESTRE}, {@code SEMESTRE} ou {@code ANO}
   * @return um item por grupo
   * @throws FaixaApuracaoInvalidaException combinação, ordem, tamanho ou atalho inválidos (400)
   */
  public List<ApuracaoResponse> consultarDoProdutor(UUID produtorId, String de, String ate,
      Integer ultimos, AgrupamentoApuracao agrupamento) {
    return consultarFaixa(produtorId, resolverFaixa(de, ate, ultimos), agrupamento);
  }

  private List<ApuracaoResponse> consultarFaixa(UUID produtorId, YearMonth[] faixa,
      AgrupamentoApuracao agrupamento) {
    List<ApuracaoResponse> meses = mesesDaFaixa(produtorId, faixa[0], faixa[1]);
    AgrupamentoApuracao grupo = agrupamento != null ? agrupamento : AgrupamentoApuracao.MES;
    return grupo == AgrupamentoApuracao.MES ? meses : agrupar(meses, grupo, faixa[0], faixa[1]);
  }

  // -------------------------------------------------------------------------

  private YearMonth[] resolverFaixa(String de, String ate, Integer ultimos) {
    if (ultimos != null) {
      if (de != null || ate != null) {
        throw new FaixaApuracaoInvalidaException("ultimos",
            "Use 'ultimos' ou 'de'/'ate', não os dois.",
            "Informe só 'ultimos' (6, 12, 36 ou 60) ou só 'de'/'ate'.", String.valueOf(ultimos),
            "'ultimos' OU 'de'/'ate'");
      }
      if (!ULTIMOS_PERMITIDOS.contains(ultimos)) {
        throw new FaixaApuracaoInvalidaException("ultimos",
            "'ultimos' aceita 6, 12, 36 ou 60 meses.",
            "Para outra faixa, informe 'de'/'ate' (até 60 meses).", String.valueOf(ultimos),
            "6, 12, 36, 60");
      }
      YearMonth atual = YearMonth.now(clock);
      return new YearMonth[] {atual.minusMonths(ultimos - 1L), atual};
    }
    if (de == null || ate == null) {
      String faltando = de == null ? "de" : "ate";
      throw new FaixaApuracaoInvalidaException(faltando,
          "Informe 'periodo', a faixa completa ('de' e 'ate') ou 'ultimos'.",
          "Ex.: '?de=2026-01&ate=2026-06' ou '?ultimos=6'.", null, "YYYY-MM");
    }
    YearMonth inicio = YearMonth.parse(de);
    YearMonth fim = YearMonth.parse(ate);
    if (inicio.isAfter(fim)) {
      throw new FaixaApuracaoInvalidaException("de",
          "Faixa inválida: 'de' (%s) é posterior a 'ate' (%s).".formatted(de, ate),
          "Informe 'de' anterior ou igual a 'ate'.", de, "até " + ate);
    }
    if (inicio.until(fim, ChronoUnit.MONTHS) + 1 > MESES_MAXIMO) {
      throw new FaixaApuracaoInvalidaException("ate",
          "Faixa inválida: de %s a %s passa do máximo de %d meses."
              .formatted(de, ate, MESES_MAXIMO),
          "Consulte faixas de até %d meses ou agrupe por ano.".formatted(MESES_MAXIMO), ate,
          "até " + inicio.plusMonths(MESES_MAXIMO - 1L));
    }
    return new YearMonth[] {inicio, fim};
  }

  /**
   * Um item por mês da faixa: persistido se consolidado; senão, a agregação ao vivo; sem nota,
   * zeros. A agregação só cobre do primeiro ao último mês não consolidado.
   */
  private List<ApuracaoResponse> mesesDaFaixa(UUID produtorId, YearMonth de, YearMonth ate) {
    Map<YearMonth, ApuracaoMensal> persistidas = new HashMap<>();
    for (ApuracaoMensal a : apuracaoRepository.findByProdutorIdAndPeriodoBetweenOrderByPeriodo(
        produtorId, de.toString(), ate.toString())) {
      persistidas.put(YearMonth.parse(a.getPeriodo()), a);
    }

    YearMonth primeiroAberto = null;
    YearMonth ultimoAberto = null;
    for (YearMonth mes = de; !mes.isAfter(ate); mes = mes.plusMonths(1)) {
      if (!persistidas.containsKey(mes)) {
        primeiroAberto = primeiroAberto == null ? mes : primeiroAberto;
        ultimoAberto = mes;
      }
    }
    Map<YearMonth, Object[]> aoVivo = primeiroAberto == null
        ? Map.of() : agregarPorMes(produtorId, primeiroAberto, ultimoAberto);

    YearMonth atual = YearMonth.now(clock);
    List<ApuracaoResponse> meses = new ArrayList<>();
    for (YearMonth mes = de; !mes.isAfter(ate); mes = mes.plusMonths(1)) {
      ApuracaoMensal persistida = persistidas.get(mes);
      Object[] linha = aoVivo.get(mes);
      meses.add(persistida != null
          ? ApuracaoMes.daPersistida(persistida, atual)
          : linha == null
              ? ApuracaoMes.aoVivo(mes, null, null, null, null, 0, atual)
              : ApuracaoMes.aoVivo(mes, linha[2], linha[3], linha[4], linha[5],
                  ((Number) linha[6]).longValue(), atual));
    }
    return meses;
  }

  private Map<YearMonth, Object[]> agregarPorMes(UUID produtorId, YearMonth de, YearMonth ate) {
    OffsetDateTime inicio = de.atDay(1).atStartOfDay(ApuracaoMes.ZONA).toOffsetDateTime();
    OffsetDateTime fim =
        ate.plusMonths(1).atDay(1).atStartOfDay(ApuracaoMes.ZONA).toOffsetDateTime();
    Map<YearMonth, Object[]> porMes = new HashMap<>();
    for (Object[] linha : notaFiscalRepository.agregarApuracaoPorMes(produtorId, inicio, fim)) {
      porMes.put(YearMonth.of(((Number) linha[0]).intValue(), ((Number) linha[1]).intValue()),
          linha);
    }
    return porMes;
  }

  /**
   * Soma os meses por grupo civil. {@code FECHADO} só se todos os meses do grupo fecharam (o
   * fechamento mais recente vira a data do grupo); {@code parcial} se a faixa corta o grupo ou se
   * algum mês dele ainda não terminou.
   */
  private static List<ApuracaoResponse> agrupar(List<ApuracaoResponse> meses,
      AgrupamentoApuracao grupo, YearMonth de, YearMonth ate) {
    Map<YearMonth, List<ApuracaoResponse>> porGrupo = new LinkedHashMap<>();
    for (ApuracaoResponse mes : meses) {
      porGrupo.computeIfAbsent(grupo.inicioDoGrupo(YearMonth.parse(mes.periodo())),
          chave -> new ArrayList<>()).add(mes);
    }
    List<ApuracaoResponse> grupos = new ArrayList<>();
    porGrupo.forEach((inicioGrupo, doGrupo) -> {
      boolean todosFechados = doGrupo.stream()
          .allMatch(m -> StatusApuracao.FECHADO.name().equals(m.status()));
      OffsetDateTime fechamento = todosFechados
          ? doGrupo.stream().map(ApuracaoResponse::dataFechamento).filter(Objects::nonNull)
              .max(OffsetDateTime::compareTo).orElse(null)
          : null;
      boolean cortado = inicioGrupo.isBefore(de) || grupo.fimDoGrupo(inicioGrupo).isAfter(ate);
      boolean algumNaoTerminou = doGrupo.stream().anyMatch(ApuracaoResponse::parcial);
      grupos.add(new ApuracaoResponse(grupo.rotulo(inicioGrupo),
          (todosFechados ? StatusApuracao.FECHADO : StatusApuracao.ABERTO).name(),
          somar(doGrupo, ApuracaoResponse::debitoIbs), somar(doGrupo, ApuracaoResponse::debitoCbs),
          somar(doGrupo, ApuracaoResponse::creditoIbs),
          somar(doGrupo, ApuracaoResponse::creditoCbs),
          somar(doGrupo, ApuracaoResponse::saldoIbs), somar(doGrupo, ApuracaoResponse::saldoCbs),
          doGrupo.stream().mapToLong(ApuracaoResponse::totalNotas).sum(), fechamento,
          doGrupo.get(0).inicio(), doGrupo.get(doGrupo.size() - 1).fim(),
          cortado || algumNaoTerminou));
    });
    return grupos;
  }

  private static BigDecimal somar(List<ApuracaoResponse> meses,
      Function<ApuracaoResponse, BigDecimal> campo) {
    return meses.stream().map(campo).reduce(ZERO, BigDecimal::add);
  }
}
