package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.ObrigacaoRequest;
import br.com.agrocontabil.dto.ObrigacaoResponse;
import br.com.agrocontabil.dto.VencimentoFiscalResponse;
import br.com.agrocontabil.exception.ObrigacaoInvalidaException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.AjusteFimDeSemana;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.ObrigacaoFiscal;
import br.com.agrocontabil.model.Periodicidade;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.repository.ObrigacaoFiscalRepository;
import br.com.agrocontabil.security.MembroEscritorioProvider;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catálogo de obrigações do escritório e as ocorrências no calendário fiscal (issue #181).
 *
 * <p><strong>Catálogo efetivo</strong> de um escritório = as obrigações próprias + as versões dele
 * dos modelos + os modelos que ele não personalizou. Os modelos são só de leitura: editar ou
 * desativar um modelo cria a versão do escritório; "restaurar padrão" a apaga.
 *
 * <p>Qualquer membro vê o catálogo; só o {@code ADMINISTRADOR} o altera. Sem auditoria: não é
 * operação fiscal, e o calendário é derivado (nenhuma ocorrência é gravada).
 *
 * <p><strong>Ocorrências:</strong> todas as da janela pedida; dia inexistente no mês cai no último
 * (31 em abril → 30); sábado e domingo seguem o {@link AjusteFimDeSemana}; feriados fora da v1.
 * Além do catálogo, o fechamento da apuração do mês (RN004) entra para todo produtor, com ou sem
 * escritório.
 */
@Service
public class ObrigacaoFiscalService {

  /** Tipo do item de calendário que vem do catálogo. */
  public static final String TIPO_OBRIGACAO = "obrigacao";

  /** Id fixo da obrigação do sistema (fechamento da apuração, RN004). */
  static final UUID FECHAMENTO_APURACAO_ID =
      UUID.fromString("00000000-0000-4000-8181-0000000000ff");

  static final String MODELO = "MODELO";
  static final String PERSONALIZADA = "PERSONALIZADA";
  static final String PROPRIA = "PROPRIA";

  private final MembroEscritorioProvider membroProvider;
  private final ObrigacaoFiscalRepository obrigacaoRepository;

  public ObrigacaoFiscalService(MembroEscritorioProvider membroProvider,
      ObrigacaoFiscalRepository obrigacaoRepository) {
    this.membroProvider = membroProvider;
    this.obrigacaoRepository = obrigacaoRepository;
  }

  /**
   * Catálogo efetivo do escritório do contador autenticado (qualquer membro).
   *
   * @return obrigações, por título
   */
  @Transactional(readOnly = true)
  public List<ObrigacaoResponse> catalogo() {
    UUID escritorioId = membroProvider.membro().getEscritorioId();
    return efetivo(List.of(escritorioId)).getOrDefault(escritorioId, List.of()).stream()
        .sorted(Comparator.comparing(ObrigacaoFiscal::getTitulo, String.CASE_INSENSITIVE_ORDER))
        .map(ObrigacaoFiscalService::resposta)
        .toList();
  }

  /**
   * Cria uma obrigação própria do escritório (só administrador).
   *
   * @param request dados
   * @return a obrigação
   */
  @Transactional
  public ObrigacaoResponse criar(ObrigacaoRequest request) {
    UUID escritorioId = administrador();
    validarPrazo(request);
    ObrigacaoFiscal nova = ObrigacaoFiscal.builder().escritorioId(escritorioId).build();
    aplicar(nova, request);
    return resposta(obrigacaoRepository.save(nova));
  }

  /**
   * Edita uma obrigação do catálogo (só administrador). Num modelo, cria ou atualiza a versão do
   * escritório; o modelo não muda.
   *
   * @param id      obrigação (própria, versão ou modelo)
   * @param request dados novos
   * @return a obrigação como fica no catálogo
   * @throws ResourceNotFoundException obrigação de outro escritório (404)
   */
  @Transactional
  public ObrigacaoResponse atualizar(UUID id, ObrigacaoRequest request) {
    UUID escritorioId = administrador();
    validarPrazo(request);
    ObrigacaoFiscal alvo = editavel(id, escritorioId);
    aplicar(alvo, request);
    return resposta(obrigacaoRepository.save(alvo));
  }

  /**
   * Tira a obrigação do calendário (só administrador); num modelo, pela versão do escritório.
   *
   * @param id obrigação
   * @return a obrigação desativada
   */
  @Transactional
  public ObrigacaoResponse desativar(UUID id) {
    return definirAtiva(id, false);
  }

  /**
   * Volta a obrigação ao calendário (só administrador).
   *
   * @param id obrigação
   * @return a obrigação reativada
   */
  @Transactional
  public ObrigacaoResponse reativar(UUID id) {
    return definirAtiva(id, true);
  }

  /**
   * Restaura o modelo: apaga a versão do escritório (só administrador).
   *
   * @param id versão do escritório ou o próprio modelo
   * @return o modelo, como volta a valer
   * @throws ObrigacaoInvalidaException obrigação própria (não tem padrão)
   */
  @Transactional
  public ObrigacaoResponse restaurarPadrao(UUID id) {
    UUID escritorioId = administrador();
    UUID modeloId = obrigacaoRepository.findByIdAndEscritorioId(id, escritorioId)
        .map(propria -> {
          if (propria.getModeloId() == null) {
            throw new ObrigacaoInvalidaException("id", "Obrigacao propria nao tem padrao.",
                "Para tira-la do calendario, desative-a.");
          }
          return propria.getModeloId();
        })
        .orElseGet(() -> obrigacaoRepository.findModeloById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Obrigacao", id)).getId());
    obrigacaoRepository.findByEscritorioIdAndModeloId(escritorioId, modeloId)
        .ifPresent(obrigacaoRepository::delete);
    return resposta(obrigacaoRepository.findModeloById(modeloId)
        .orElseThrow(() -> new ResourceNotFoundException("Obrigacao", modeloId)));
  }

  /**
   * Ocorrências na janela das obrigações ativas dos escritórios que valem para o produtor, mais o
   * fechamento da apuração do mês. Roda na transação de quem chama.
   *
   * @param produtor    produtor (tipo e regime decidem o que se aplica)
   * @param escritorios escritórios cujo catálogo entra
   * @param inicio      primeiro dia da janela (inclusivo)
   * @param fim         último dia da janela (inclusivo)
   * @return itens de calendário {@code tipo = "obrigacao"}
   */
  public List<VencimentoFiscalResponse> ocorrencias(Produtor produtor,
      Collection<UUID> escritorios, LocalDate inicio, LocalDate fim) {
    List<VencimentoFiscalResponse> itens = new ArrayList<>();
    for (List<ObrigacaoFiscal> catalogo : efetivo(escritorios).values()) {
      for (ObrigacaoFiscal obrigacao : catalogo) {
        if (obrigacao.isAtiva()
            && obrigacao.aplicaA(produtor.getTipoProdutor(), produtor.getRegimeTributario())) {
          for (LocalDate data : datas(obrigacao.getPeriodicidade(), obrigacao.getDia(),
              obrigacao.getMes(), obrigacao.getAjusteFimDeSemana(), inicio, fim)) {
            itens.add(item(obrigacao.getId(), obrigacao.getTitulo(), obrigacao.getDescricao(),
                data, obrigacao.getPeriodicidade(), obrigacao.getOrigem()));
          }
        }
      }
    }
    for (LocalDate data : datas(Periodicidade.MENSAL, 31, null, AjusteFimDeSemana.NENHUM,
        inicio, fim)) {
      itens.add(item(FECHAMENTO_APURACAO_ID, "Fechamento da apuracao do mes",
          "A apuracao de IBS/CBS do mes fecha automaticamente no ultimo dia.", data,
          Periodicidade.MENSAL, "RN004 (apuracao mensal de IBS/CBS)"));
    }
    return itens;
  }

  /**
   * Datas da obrigação dentro da janela: dia que não existe no mês cai no último, e o fim de
   * semana segue o ajuste. Olha um mês para fora de cada ponta, porque o ajuste pode atravessar a
   * virada do mês.
   */
  static List<LocalDate> datas(Periodicidade periodicidade, int dia, Integer mes,
      AjusteFimDeSemana ajuste, LocalDate inicio, LocalDate fim) {
    List<LocalDate> datas = new ArrayList<>();
    YearMonth ultimo = YearMonth.from(fim).plusMonths(1);
    for (YearMonth ym = YearMonth.from(inicio).minusMonths(1); !ym.isAfter(ultimo);
        ym = ym.plusMonths(1)) {
      if (periodicidade == Periodicidade.ANUAL && ym.getMonthValue() != mes) {
        continue;
      }
      LocalDate data = ajustar(ym.atDay(Math.min(dia, ym.lengthOfMonth())), ajuste);
      if (!data.isBefore(inicio) && !data.isAfter(fim)) {
        datas.add(data);
      }
    }
    return datas;
  }

  private static LocalDate ajustar(LocalDate data, AjusteFimDeSemana ajuste) {
    DayOfWeek dia = data.getDayOfWeek();
    boolean fimDeSemana = dia == DayOfWeek.SATURDAY || dia == DayOfWeek.SUNDAY;
    if (!fimDeSemana || ajuste == null || ajuste == AjusteFimDeSemana.NENHUM) {
      return data;
    }
    if (ajuste == AjusteFimDeSemana.ANTECIPA) {
      return data.minusDays(dia == DayOfWeek.SATURDAY ? 1 : 2);
    }
    return data.plusDays(dia == DayOfWeek.SATURDAY ? 2 : 1);
  }

  /** Catálogo efetivo de cada escritório: próprias + versões + modelos sem versão. */
  private Map<UUID, List<ObrigacaoFiscal>> efetivo(Collection<UUID> escritorios) {
    Map<UUID, List<ObrigacaoFiscal>> porEscritorio = new HashMap<>();
    if (escritorios.isEmpty()) {
      return porEscritorio;
    }
    List<ObrigacaoFiscal> modelos = obrigacaoRepository.findModelos();
    List<ObrigacaoFiscal> linhas = obrigacaoRepository.findByEscritorioIdIn(escritorios);
    for (UUID escritorioId : escritorios) {
      List<ObrigacaoFiscal> doEscritorio = linhas.stream()
          .filter(o -> escritorioId.equals(o.getEscritorioId())).toList();
      List<UUID> personalizados = doEscritorio.stream().map(ObrigacaoFiscal::getModeloId)
          .filter(Objects::nonNull).toList();
      List<ObrigacaoFiscal> efetivo = new ArrayList<>(doEscritorio);
      modelos.stream().filter(m -> !personalizados.contains(m.getId())).forEach(efetivo::add);
      porEscritorio.put(escritorioId, efetivo);
    }
    return porEscritorio;
  }

  private ObrigacaoResponse definirAtiva(UUID id, boolean ativa) {
    UUID escritorioId = administrador();
    ObrigacaoFiscal alvo = editavel(id, escritorioId);
    alvo.setAtiva(ativa);
    return resposta(obrigacaoRepository.save(alvo));
  }

  /**
   * A linha que o escritório edita: a dele, ou — sendo um modelo — a versão dele, criada como
   * cópia do modelo quando ainda não existe.
   */
  private ObrigacaoFiscal editavel(UUID id, UUID escritorioId) {
    return obrigacaoRepository.findByIdAndEscritorioId(id, escritorioId)
        .orElseGet(() -> {
          ObrigacaoFiscal modelo = obrigacaoRepository.findModeloById(id)
              .orElseThrow(() -> new ResourceNotFoundException("Obrigacao", id));
          return obrigacaoRepository.findByEscritorioIdAndModeloId(escritorioId, id)
              .orElseGet(() -> modelo.toBuilder()
                  .id(null)
                  .modelo(false)
                  .escritorioId(escritorioId)
                  .modeloId(modelo.getId())
                  .regimes(modelo.getRegimes().isEmpty() ? EnumSet.noneOf(RegimeTributario.class)
                      : EnumSet.copyOf(modelo.getRegimes()))
                  .createdAt(null)
                  .updatedAt(null)
                  .build());
        });
  }

  private UUID administrador() {
    return membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR).getEscritorioId();
  }

  private static void validarPrazo(ObrigacaoRequest request) {
    if (request.periodicidade() == Periodicidade.ANUAL && request.mes() == null) {
      throw new ObrigacaoInvalidaException("mes", "Obrigacao anual precisa do mes.",
          "Informe o mes (1 a 12).");
    }
    if (request.periodicidade() == Periodicidade.MENSAL && request.mes() != null) {
      throw new ObrigacaoInvalidaException("mes", "Obrigacao mensal nao tem mes.",
          "Tire o mes, ou use periodicidade ANUAL.");
    }
  }

  private static void aplicar(ObrigacaoFiscal alvo, ObrigacaoRequest request) {
    alvo.setTitulo(request.titulo().strip());
    alvo.setDescricao(request.descricao());
    alvo.setPeriodicidade(request.periodicidade());
    alvo.setDia(request.dia());
    alvo.setMes(request.mes());
    alvo.setAjusteFimDeSemana(request.ajusteFimDeSemana() == null ? AjusteFimDeSemana.NENHUM
        : request.ajusteFimDeSemana());
    alvo.setTipoProdutor(request.tipoProdutor());
    alvo.setRegimes(request.regimes() == null || request.regimes().isEmpty()
        ? EnumSet.noneOf(RegimeTributario.class) : EnumSet.copyOf(request.regimes()));
    alvo.setOrigem(request.origem());
  }

  private static VencimentoFiscalResponse item(UUID obrigacaoId, String titulo,
      String descricao, LocalDate data, Periodicidade periodicidade, String origem) {
    return new VencimentoFiscalResponse(obrigacaoId + ":" + data, titulo, descricao, data,
        TIPO_OBRIGACAO, null, null, titulo, periodicidade, origem);
  }

  private static ObrigacaoResponse resposta(ObrigacaoFiscal o) {
    String origemCatalogo = o.isModelo() ? MODELO : o.getModeloId() != null ? PERSONALIZADA
        : PROPRIA;
    return new ObrigacaoResponse(o.getId(), o.isModelo() ? o.getId() : o.getModeloId(),
        origemCatalogo, o.getTitulo(), o.getDescricao(), o.getPeriodicidade(), o.getDia(),
        o.getMes(), o.getAjusteFimDeSemana(), o.getTipoProdutor(),
        o.getRegimes() == null ? List.of() : List.copyOf(o.getRegimes()), o.getOrigem(),
        o.isAtiva());
  }
}
