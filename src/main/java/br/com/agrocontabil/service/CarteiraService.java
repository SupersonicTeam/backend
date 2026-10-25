package br.com.agrocontabil.service;

import br.com.agrocontabil.common.NormalizacaoBusca;
import br.com.agrocontabil.dto.AtencaoCarteiraResponse;
import br.com.agrocontabil.dto.FiltroCarteiraRequest;
import br.com.agrocontabil.dto.ProdutorCarteiraResponse;
import br.com.agrocontabil.dto.ResponsavelClienteResponse;
import br.com.agrocontabil.dto.SituacaoVinculoResponse;
import br.com.agrocontabil.dto.VinculoProdutorResponse;
import br.com.agrocontabil.exception.FiltroCarteiraInvalidoException;
import br.com.agrocontabil.exception.FuncaoNaoAutorizadaException;
import br.com.agrocontabil.exception.PapelNaoAutorizadoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.exception.VinculoSituacaoInvalidaException;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.VinculoCarteira;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ContagemPorProdutor;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.EtiquetaDoVinculo;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.security.MembroEscritorioProvider;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
import br.com.agrocontabil.service.auditoria.Auditado;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carteira do escritório (issues #177 e #228): a lista de clientes com o que pede atenção, o
 * responsável por cliente e o ciclo do vínculo (suspender, reativar, encerrar) dos dois lados.
 *
 * <p>O dono da carteira é o <strong>escritório</strong>: todo membro enxerga a carteira inteira;
 * só o {@code ADMINISTRADOR} gerencia o vínculo (convidar, suspender, reativar, encerrar).
 *
 * <p><strong>Lista em memória, de propósito.</strong> A carteira de um escritório tem centenas de
 * clientes, e ordenar por urgência exige os indicadores de todos antes de paginar. Por isso o
 * service faz um número fixo de consultas — os vínculos com o produtor, as etiquetas, os membros e
 * uma agregação ({@code GROUP BY produtor}) por indicador — e filtra, ordena e pagina em Java.
 * Nunca uma consulta por cliente.
 *
 * <p>Os indicadores só existem para vínculo {@code ATIVO}: suspenso ou encerrado, o escritório não
 * alcança os dados do produtor.
 */
@Service
public class CarteiraService {

  /** Página padrão da lista. */
  public static final int TAMANHO_PAGINA_PADRAO = 50;

  /** Mínimo de caracteres para a busca por nome (como a busca das notas, #174). */
  static final int BUSCA_MINIMA = 3;

  /** Janela dos vencimentos próximos, em dias a partir de hoje. */
  static final int DIAS_VENCIMENTOS_PROXIMOS = 7;

  static final String ORDEM_NOME = "nome";
  static final String ORDEM_URGENCIA = "urgencia";

  /** Filtro {@code responsavel}: o próprio membro. */
  static final String RESPONSAVEL_EU = "eu";

  /** Filtro {@code responsavel}: clientes sem responsável. */
  static final String RESPONSAVEL_NENHUM = "nenhum";

  private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

  private static final Comparator<ProdutorCarteiraResponse> POR_NOME = Comparator
      .comparing((ProdutorCarteiraResponse p) -> NormalizacaoBusca.normalizar(p.nomeCompleto()))
      .thenComparing(ProdutorCarteiraResponse::vinculoId);

  /** Guias vencidas, depois inconsistências, depois vencimentos próximos; empate pelo nome. */
  private static final Comparator<ProdutorCarteiraResponse> POR_URGENCIA = Comparator
      .comparingLong(
          (ProdutorCarteiraResponse p) -> -indicador(p, AtencaoCarteiraResponse::guiasVencidas))
      .thenComparingLong(p -> -indicador(p, AtencaoCarteiraResponse::notasComInconsistencia))
      .thenComparingLong(p -> -indicador(p, AtencaoCarteiraResponse::vencimentosProximos7Dias))
      .thenComparing(POR_NOME);

  private final MembroEscritorioProvider membroProvider;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final ProdutorEmContextoResolver produtorEmContexto;
  private final VinculoCarteiraRepository vinculoRepository;
  private final ProdutorRepository produtorRepository;
  private final ContadorRepository contadorRepository;
  private final EscritorioRepository escritorioRepository;
  private final Clock clock;

  public CarteiraService(
      MembroEscritorioProvider membroProvider,
      AuthenticatedProdutorProvider produtorProvider,
      ProdutorEmContextoResolver produtorEmContexto,
      VinculoCarteiraRepository vinculoRepository,
      ProdutorRepository produtorRepository,
      ContadorRepository contadorRepository,
      EscritorioRepository escritorioRepository,
      Clock clock) {
    this.membroProvider = membroProvider;
    this.produtorProvider = produtorProvider;
    this.produtorEmContexto = produtorEmContexto;
    this.vinculoRepository = vinculoRepository;
    this.produtorRepository = produtorRepository;
    this.contadorRepository = contadorRepository;
    this.escritorioRepository = escritorioRepository;
    this.clock = clock;
  }

  /**
   * Lista a carteira do escritório do contador autenticado, filtrada, ordenada e paginada.
   *
   * @param filtro filtros, ordenação e página
   * @return página de clientes; {@code totalElements} reflete o filtro
   * @throws PapelNaoAutorizadoException se quem pede não for contador (403)
   * @throws FuncaoNaoAutorizadaException se o contador não tiver escritório (403)
   * @throws FiltroCarteiraInvalidoException ordenação ou responsável inválidos (400)
   */
  @Transactional(readOnly = true)
  public Page<ProdutorCarteiraResponse> listar(FiltroCarteiraRequest filtro) {
    Contador membro = membroProvider.membro();
    UUID escritorioId = membro.getEscritorioId();
    Comparator<ProdutorCarteiraResponse> ordem = ordenacao(filtro.sort());
    Predicate<VinculoCarteira> doResponsavel = porResponsavel(filtro.responsavel(), membro);
    SituacaoVinculo situacao = filtro.situacao() == null ? SituacaoVinculo.ATIVO
        : filtro.situacao();

    List<VinculoCarteira> vinculos = vinculoRepository.findDaCarteira(escritorioId, situacao);
    Set<UUID> porDocumento = idsPorDocumento(filtro.busca());
    String termo = termoDeNome(filtro.busca());
    Map<UUID, List<String>> etiquetas = etiquetasPorVinculo(escritorioId, situacao);
    Map<UUID, String> membros = contadorRepository
        .findByEscritorioIdOrderByNomeCompletoAsc(escritorioId).stream()
        .collect(Collectors.toMap(Contador::getId, Contador::getNomeCompleto));
    Set<String> etiquetasFiltro = filtro.etiqueta() == null ? Set.of()
        : filtro.etiqueta().stream().filter(Objects::nonNull).map(NormalizacaoBusca::normalizar)
            .filter(e -> !e.isEmpty()).collect(Collectors.toSet());
    Map<UUID, AtencaoCarteiraResponse> atencao = situacao == SituacaoVinculo.ATIVO
        ? atencao(escritorioId) : Map.of();
    AtencaoCarteiraResponse semNada = new AtencaoCarteiraResponse(0, 0, 0, 0);

    List<ProdutorCarteiraResponse> clientes = vinculos.stream()
        .filter(doResponsavel)
        .filter(v -> porDocumento == null || porDocumento.contains(v.getProdutorId()))
        .filter(v -> termo == null
            || NormalizacaoBusca.normalizar(v.getProdutor().getNomeCompleto()).contains(termo))
        .filter(v -> filtro.regime() == null
            || filtro.regime() == v.getProdutor().getRegimeTributario())
        .filter(v -> filtro.tipoProdutor() == null
            || filtro.tipoProdutor() == v.getProdutor().getTipoProdutor())
        .filter(v -> filtro.uf() == null
            || filtro.uf().equalsIgnoreCase(v.getProdutor().getEstado()))
        .filter(v -> etiquetasFiltro.isEmpty() || etiquetas.getOrDefault(v.getId(), List.of())
            .stream().map(NormalizacaoBusca::normalizar).anyMatch(etiquetasFiltro::contains))
        .map(v -> resposta(v, etiquetas.getOrDefault(v.getId(), List.of()),
            v.getResponsavelId() == null ? null : membros.get(v.getResponsavelId()),
            situacao == SituacaoVinculo.ATIVO
                ? atencao.getOrDefault(v.getProdutorId(), semNada) : null))
        .filter(c -> !Boolean.TRUE.equals(filtro.precisaAtencao())
            || (c.atencao() != null && c.atencao().precisaAtencao()))
        .sorted(ordem)
        .toList();

    int pagina = filtro.page() == null ? 0 : filtro.page();
    int tamanho = filtro.size() == null ? TAMANHO_PAGINA_PADRAO : filtro.size();
    long inicio = (long) pagina * tamanho;
    List<ProdutorCarteiraResponse> conteudo = inicio >= clientes.size() ? List.of()
        : clientes.subList((int) inicio, (int) Math.min(clientes.size(), inicio + tamanho));
    return new PageImpl<>(conteudo, PageRequest.of(pagina, tamanho), clientes.size());
  }

  /**
   * Suspende o cliente: sai do alcance do escritório (404), mas a ficha fica. Só administrador.
   *
   * @param produtorId cliente
   * @return a situação nova
   * @throws ResourceNotFoundException sem vínculo aberto com o produtor (404)
   * @throws VinculoSituacaoInvalidaException se já estiver suspenso (409)
   */
  @Transactional
  @Auditado(acao = AcaoAuditada.VINCULO_SUSPENSO, recurso = RecursoAuditado.VINCULO_CARTEIRA)
  public SituacaoVinculoResponse suspender(UUID produtorId) {
    VinculoCarteira vinculo = vinculoAberto(produtorId);
    exigirSituacao(vinculo, SituacaoVinculo.ATIVO);
    vinculo.setSituacao(SituacaoVinculo.SUSPENSO);
    return situacao(vinculo);
  }

  /**
   * Reativa um cliente suspenso, sem novo convite. Só administrador.
   *
   * @param produtorId cliente
   * @return a situação nova
   * @throws ResourceNotFoundException sem vínculo aberto com o produtor (404)
   * @throws VinculoSituacaoInvalidaException se não estiver suspenso (409)
   */
  @Transactional
  @Auditado(acao = AcaoAuditada.VINCULO_REATIVADO, recurso = RecursoAuditado.VINCULO_CARTEIRA)
  public SituacaoVinculoResponse reativar(UUID produtorId) {
    VinculoCarteira vinculo = vinculoAberto(produtorId);
    exigirSituacao(vinculo, SituacaoVinculo.SUSPENSO);
    vinculo.setSituacao(SituacaoVinculo.ATIVO);
    return situacao(vinculo);
  }

  /**
   * Encerra o cliente pelo escritório: acesso cortado na hora; voltar exige novo convite. Só
   * administrador.
   *
   * @param produtorId cliente
   * @return a situação nova
   * @throws ResourceNotFoundException sem vínculo aberto com o produtor (404)
   */
  @Transactional
  @Auditado(acao = AcaoAuditada.VINCULO_ENCERRADO, recurso = RecursoAuditado.VINCULO_CARTEIRA)
  public SituacaoVinculoResponse encerrar(UUID produtorId) {
    VinculoCarteira vinculo = vinculoAberto(produtorId);
    encerrarVinculo(vinculo);
    return situacao(vinculo);
  }

  /**
   * Troca o responsável pelo cliente: o administrador ou o próprio responsável (repassando).
   *
   * @param produtorId    cliente (carteira ativa)
   * @param responsavelId membro do mesmo escritório, ou nulo para deixar sem responsável
   * @return o responsável novo
   * @throws ResourceNotFoundException cliente fora da carteira ativa, ou membro de outro
   *     escritório (404)
   * @throws FuncaoNaoAutorizadaException se quem pede não for administrador nem o responsável
   */
  @Transactional
  @Auditado(acao = AcaoAuditada.RESPONSAVEL_ALTERADO, recurso = RecursoAuditado.VINCULO_CARTEIRA)
  public ResponsavelClienteResponse alterarResponsavel(UUID produtorId, UUID responsavelId) {
    Contador membro = produtorEmContexto.exigirContadorComAcesso(produtorId).contador();
    VinculoCarteira vinculo = vinculoRepository
        .findAbertoForUpdate(membro.getEscritorioId(), produtorId)
        .filter(v -> v.getSituacao() == SituacaoVinculo.ATIVO)
        .orElseThrow(() -> new ResourceNotFoundException("Produtor", produtorId));
    if (membro.getFuncao() != FuncaoEscritorio.ADMINISTRADOR
        && !membro.getId().equals(vinculo.getResponsavelId())) {
      throw new FuncaoNaoAutorizadaException(membro.getFuncao(),
          List.of(FuncaoEscritorio.ADMINISTRADOR));
    }
    String nome = null;
    if (responsavelId != null) {
      nome = contadorRepository.findByIdAndEscritorioId(responsavelId, membro.getEscritorioId())
          .orElseThrow(() -> new ResourceNotFoundException("Membro", responsavelId))
          .getNomeCompleto();
    }
    vinculo.setResponsavelId(responsavelId);
    return new ResponsavelClienteResponse(produtorId, responsavelId, nome);
  }

  /**
   * Escritórios que atendem o produtor autenticado (ativos e suspensos).
   *
   * @return vínculos, do mais recente ao mais antigo
   * @throws PapelNaoAutorizadoException se quem pede for contador (403)
   */
  @Transactional(readOnly = true)
  public List<VinculoProdutorResponse> listarDoProdutor() {
    UUID produtorId = produtorProvider.get().getId();
    List<VinculoCarteira> vinculos = vinculoRepository.findAbertosDoProdutor(produtorId);
    Map<UUID, Escritorio> escritorios = escritorioRepository
        .findAllById(vinculos.stream().map(VinculoCarteira::getEscritorioId).toList()).stream()
        .collect(Collectors.toMap(Escritorio::getId, Function.identity()));
    Map<UUID, Contador> responsaveis = contadorRepository
        .findAllById(vinculos.stream().map(VinculoCarteira::getResponsavelId)
            .filter(Objects::nonNull).toList()).stream()
        .collect(Collectors.toMap(Contador::getId, Function.identity()));
    return vinculos.stream()
        .map(v -> vinculoDoProdutor(v, escritorios.get(v.getEscritorioId()),
            v.getResponsavelId() == null ? null : responsaveis.get(v.getResponsavelId())))
        .toList();
  }

  /**
   * O produtor encerra o vínculo com um escritório.
   *
   * @param vinculoId vínculo
   * @return o vínculo encerrado
   * @throws ResourceNotFoundException se o vínculo não for do produtor ou já estiver encerrado
   */
  @Transactional
  @Auditado(acao = AcaoAuditada.VINCULO_ENCERRADO, recurso = RecursoAuditado.VINCULO_CARTEIRA)
  public VinculoProdutorResponse encerrarPeloProdutor(UUID vinculoId) {
    UUID produtorId = produtorProvider.get().getId();
    VinculoCarteira vinculo = vinculoRepository
        .findByIdAndProdutorIdForUpdate(vinculoId, produtorId)
        .filter(v -> v.getSituacao() != SituacaoVinculo.ENCERRADO)
        .orElseThrow(() -> new ResourceNotFoundException("Vinculo", vinculoId));
    encerrarVinculo(vinculo);
    return vinculoDoProdutor(vinculo,
        escritorioRepository.findById(vinculo.getEscritorioId()).orElse(null),
        vinculo.getResponsavelId() == null ? null
            : contadorRepository.findById(vinculo.getResponsavelId()).orElse(null));
  }

  /**
   * Abre ou reativa o vínculo do par — consequência do aceite de um convite (consentimento do
   * produtor). Roda na transação de quem chama.
   *
   * @param escritorioId  escritório
   * @param produtorId    produtor que aceitou
   * @param responsavelId membro que convidou, se ainda for da equipe (nulo = sem responsável)
   */
  void ativarPorConvite(UUID escritorioId, UUID produtorId, UUID responsavelId) {
    UUID responsavel = responsavelId != null
        && contadorRepository.findByIdAndEscritorioId(responsavelId, escritorioId).isPresent()
        ? responsavelId : null;
    vinculoRepository.findAbertoForUpdate(escritorioId, produtorId).ifPresentOrElse(
        aberto -> aberto.setSituacao(SituacaoVinculo.ATIVO),
        () -> vinculoRepository.save(VinculoCarteira.builder()
            .escritorioId(escritorioId)
            .produtorId(produtorId)
            .responsavelId(responsavel)
            .situacao(SituacaoVinculo.ATIVO)
            .inicio(OffsetDateTime.now(clock))
            .build()));
  }

  private VinculoCarteira vinculoAberto(UUID produtorId) {
    UUID escritorioId = membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR)
        .getEscritorioId();
    return vinculoRepository.findAbertoForUpdate(escritorioId, produtorId)
        .orElseThrow(() -> new ResourceNotFoundException("Produtor", produtorId));
  }

  private void encerrarVinculo(VinculoCarteira vinculo) {
    vinculo.setSituacao(SituacaoVinculo.ENCERRADO);
    vinculo.setFim(OffsetDateTime.now(clock));
  }

  private static void exigirSituacao(VinculoCarteira vinculo, SituacaoVinculo exigida) {
    if (vinculo.getSituacao() != exigida) {
      throw new VinculoSituacaoInvalidaException(vinculo.getSituacao(), List.of(exigida));
    }
  }

  private static Comparator<ProdutorCarteiraResponse> ordenacao(String sort) {
    String pedida = sort == null ? "" : sort.strip().toLowerCase(Locale.ROOT);
    if (pedida.isEmpty() || ORDEM_NOME.equals(pedida)) {
      return POR_NOME;
    }
    if (ORDEM_URGENCIA.equals(pedida)) {
      return POR_URGENCIA;
    }
    throw new FiltroCarteiraInvalidoException("sort", sort, List.of(ORDEM_NOME, ORDEM_URGENCIA));
  }

  /** {@code eu}, {@code nenhum} ou o id de um membro; sem valor, todos. */
  private static Predicate<VinculoCarteira> porResponsavel(String responsavel, Contador membro) {
    String pedido = responsavel == null ? "" : responsavel.strip().toLowerCase(Locale.ROOT);
    if (pedido.isEmpty()) {
      return v -> true;
    }
    if (RESPONSAVEL_EU.equals(pedido)) {
      return v -> membro.getId().equals(v.getResponsavelId());
    }
    if (RESPONSAVEL_NENHUM.equals(pedido)) {
      return v -> v.getResponsavelId() == null;
    }
    try {
      UUID id = UUID.fromString(pedido);
      return v -> id.equals(v.getResponsavelId());
    } catch (IllegalArgumentException e) {
      throw new FiltroCarteiraInvalidoException("responsavel", responsavel,
          List.of(RESPONSAVEL_EU, RESPONSAVEL_NENHUM, "<id do membro>"));
    }
  }

  /**
   * CPF/CNPJ completo (com ou sem máscara) vira busca por igualdade no banco; outro termo, nulo.
   */
  private Set<UUID> idsPorDocumento(String busca) {
    if (busca == null) {
      return null;
    }
    String bruto = busca.strip();
    if (!bruto.matches("[0-9./\\-\\s]+")) {
      return null;
    }
    String digitos = bruto.replaceAll("\\D", "");
    if (digitos.length() != 11 && digitos.length() != 14) {
      return null;
    }
    return new HashSet<>(produtorRepository.findIdsByCpfCnpjIn(
        List.of(digitos, CadastroService.comMascara(digitos))));
  }

  /** Termo de nome normalizado; nulo quando é documento ou curto demais (ignorado). */
  private static String termoDeNome(String busca) {
    if (busca == null || busca.strip().matches("[0-9./\\-\\s]+")) {
      return null;
    }
    String termo = NormalizacaoBusca.normalizar(busca);
    return termo.length() >= BUSCA_MINIMA ? termo : null;
  }

  private Map<UUID, List<String>> etiquetasPorVinculo(UUID escritorioId,
      SituacaoVinculo situacao) {
    return vinculoRepository.findEtiquetasDaCarteira(escritorioId, situacao).stream()
        .collect(Collectors.groupingBy(EtiquetaDoVinculo::vinculoId,
            Collectors.collectingAndThen(
                Collectors.mapping(EtiquetaDoVinculo::nome, Collectors.toList()),
                nomes -> nomes.stream()
                    .sorted(Comparator.comparing(NormalizacaoBusca::normalizar)).toList())));
  }

  /** Uma consulta agregada por indicador, sobre a carteira ativa inteira. */
  private Map<UUID, AtencaoCarteiraResponse> atencao(UUID escritorioId) {
    LocalDate hoje = LocalDate.now(clock.withZone(FUSO));
    Map<UUID, Long> inconsistencias = porProdutor(vinculoRepository.contarNotasDaCarteira(
        escritorioId, StatusNotaFiscal.COM_INCONSISTENCIAS));
    Map<UUID, Long> aguardando = porProdutor(vinculoRepository.contarNotasDaCarteira(
        escritorioId, StatusNotaFiscal.AGUARDANDO_APROVACAO));
    Map<UUID, Long> vencidas = porProdutor(
        vinculoRepository.contarGuiasVencidasDaCarteira(escritorioId, hoje));
    Map<UUID, Long> proximos = porProdutor(vinculoRepository.contarGuiasNaFaixaDaCarteira(
        escritorioId, StatusGuiaFiscal.PENDENTE, hoje, hoje.plusDays(DIAS_VENCIMENTOS_PROXIMOS)));

    Set<UUID> produtores = new HashSet<>(inconsistencias.keySet());
    produtores.addAll(aguardando.keySet());
    produtores.addAll(vencidas.keySet());
    produtores.addAll(proximos.keySet());
    Map<UUID, AtencaoCarteiraResponse> atencao = new HashMap<>();
    for (UUID produtorId : produtores) {
      atencao.put(produtorId, new AtencaoCarteiraResponse(
          inconsistencias.getOrDefault(produtorId, 0L),
          aguardando.getOrDefault(produtorId, 0L),
          vencidas.getOrDefault(produtorId, 0L),
          proximos.getOrDefault(produtorId, 0L)));
    }
    return atencao;
  }

  private static Map<UUID, Long> porProdutor(Collection<ContagemPorProdutor> contagens) {
    return contagens.stream().collect(
        Collectors.toMap(ContagemPorProdutor::produtorId, ContagemPorProdutor::total));
  }

  private static long indicador(ProdutorCarteiraResponse cliente,
      ToLongFunction<AtencaoCarteiraResponse> campo) {
    return cliente.atencao() == null ? 0 : campo.applyAsLong(cliente.atencao());
  }

  private static ProdutorCarteiraResponse resposta(VinculoCarteira vinculo,
      List<String> etiquetas, String responsavelNome, AtencaoCarteiraResponse atencao) {
    Produtor produtor = vinculo.getProdutor();
    // Encerrado: so o nome, para o historico; o resto e dado atual de quem nao e mais cliente.
    boolean cliente = vinculo.getSituacao() != SituacaoVinculo.ENCERRADO;
    return new ProdutorCarteiraResponse(
        vinculo.getId(),
        produtor.getId(),
        produtor.getNomeCompleto(),
        cliente ? produtor.getCpfCnpj() : null,
        cliente ? produtor.getTipoProdutor() : null,
        cliente ? produtor.getRegimeTributario() : null,
        cliente ? produtor.getEstado() : null,
        vinculo.getSituacao(),
        vinculo.getInicio(),
        vinculo.getFim(),
        vinculo.getResponsavelId(),
        responsavelNome,
        etiquetas,
        atencao);
  }

  private static SituacaoVinculoResponse situacao(VinculoCarteira vinculo) {
    return new SituacaoVinculoResponse(vinculo.getProdutorId(), vinculo.getSituacao(),
        vinculo.getInicio(), vinculo.getFim());
  }

  private static VinculoProdutorResponse vinculoDoProdutor(VinculoCarteira vinculo,
      Escritorio escritorio, Contador responsavel) {
    return new VinculoProdutorResponse(
        vinculo.getId(),
        vinculo.getEscritorioId(),
        escritorio == null ? null : escritorio.getNome(),
        responsavel == null ? null : responsavel.getNomeCompleto(),
        responsavel == null ? null : responsavel.getCrc(),
        vinculo.getSituacao(),
        vinculo.getInicio(),
        vinculo.getFim());
  }
}
