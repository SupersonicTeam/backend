package br.com.agrocontabil.service;

import br.com.agrocontabil.common.NormalizacaoBusca;
import br.com.agrocontabil.dto.AtencaoCarteiraResponse;
import br.com.agrocontabil.dto.FiltroCarteiraRequest;
import br.com.agrocontabil.dto.ProdutorCarteiraResponse;
import br.com.agrocontabil.dto.SituacaoVinculoResponse;
import br.com.agrocontabil.dto.VinculoProdutorResponse;
import br.com.agrocontabil.exception.FiltroCarteiraInvalidoException;
import br.com.agrocontabil.exception.PapelNaoAutorizadoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.exception.VinculoSituacaoInvalidaException;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.VinculoCarteira;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ContagemPorProdutor;
import br.com.agrocontabil.repository.EtiquetaDoVinculo;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.security.UsuarioAutenticado;
import br.com.agrocontabil.security.UsuarioAutenticadoProvider;
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
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carteira do escritório (issue #177): a lista de clientes com o que pede atenção, e o ciclo do
 * vínculo (suspender, reativar, encerrar) dos dois lados.
 *
 * <p><strong>Lista em memória, de propósito.</strong> A carteira de um escritório tem centenas de
 * clientes, e ordenar por urgência exige os indicadores de todos antes de paginar. Por isso o
 * service faz um número fixo de consultas — os vínculos com o produtor, as etiquetas e uma
 * agregação ({@code GROUP BY produtor}) por indicador — e filtra, ordena e pagina em Java. Nunca
 * uma consulta por cliente.
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

  private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

  private static final Comparator<ProdutorCarteiraResponse> POR_NOME = Comparator
      .comparing((ProdutorCarteiraResponse p) -> NormalizacaoBusca.normalizar(p.nomeCompleto()))
      .thenComparing(ProdutorCarteiraResponse::id);

  /** Guias vencidas, depois inconsistências, depois vencimentos próximos; empate pelo nome. */
  private static final Comparator<ProdutorCarteiraResponse> POR_URGENCIA = Comparator
      .comparingLong(
          (ProdutorCarteiraResponse p) -> -indicador(p, AtencaoCarteiraResponse::guiasVencidas))
      .thenComparingLong(p -> -indicador(p, AtencaoCarteiraResponse::notasComInconsistencia))
      .thenComparingLong(p -> -indicador(p, AtencaoCarteiraResponse::vencimentosProximos7Dias))
      .thenComparing(POR_NOME);

  private final UsuarioAutenticadoProvider usuarioProvider;
  private final VinculoCarteiraRepository vinculoRepository;
  private final ProdutorRepository produtorRepository;
  private final ContadorRepository contadorRepository;
  private final Clock clock;

  public CarteiraService(
      UsuarioAutenticadoProvider usuarioProvider,
      VinculoCarteiraRepository vinculoRepository,
      ProdutorRepository produtorRepository,
      ContadorRepository contadorRepository,
      Clock clock) {
    this.usuarioProvider = usuarioProvider;
    this.vinculoRepository = vinculoRepository;
    this.produtorRepository = produtorRepository;
    this.contadorRepository = contadorRepository;
    this.clock = clock;
  }

  /**
   * Lista a carteira do contador autenticado, filtrada, ordenada e paginada.
   *
   * @param filtro filtros, ordenação e página
   * @return página de clientes; {@code totalElements} reflete o filtro
   * @throws PapelNaoAutorizadoException se quem pede não for contador (403)
   * @throws FiltroCarteiraInvalidoException ordenação fora de {@code nome}/{@code urgencia} (400)
   */
  @Transactional(readOnly = true)
  public Page<ProdutorCarteiraResponse> listar(FiltroCarteiraRequest filtro) {
    UUID contadorId = exigirContador().contador().getId();
    Comparator<ProdutorCarteiraResponse> ordem = ordenacao(filtro.sort());
    SituacaoVinculo situacao = filtro.situacao() == null ? SituacaoVinculo.ATIVO
        : filtro.situacao();

    List<VinculoCarteira> vinculos = vinculoRepository.findDaCarteira(contadorId, situacao);
    Set<UUID> porDocumento = idsPorDocumento(filtro.busca());
    String termo = termoDeNome(filtro.busca());
    Map<UUID, List<String>> etiquetas = etiquetasPorVinculo(contadorId, situacao);
    Set<String> etiquetasFiltro = filtro.etiqueta() == null ? Set.of()
        : filtro.etiqueta().stream().filter(Objects::nonNull).map(NormalizacaoBusca::normalizar)
            .filter(e -> !e.isEmpty()).collect(Collectors.toSet());
    Map<UUID, AtencaoCarteiraResponse> atencao = situacao == SituacaoVinculo.ATIVO
        ? atencao(contadorId) : Map.of();
    AtencaoCarteiraResponse semNada = new AtencaoCarteiraResponse(0, 0, 0, 0);

    List<ProdutorCarteiraResponse> clientes = vinculos.stream()
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
   * Suspende o cliente: sai do alcance do escritório (404), mas a ficha fica.
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
   * Reativa um cliente suspenso, sem novo convite.
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
   * Encerra o cliente pelo escritório: acesso cortado na hora; voltar exige novo convite.
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
   * Escritórios que atendem o produtor autenticado (ativos e suspensos).
   *
   * @return vínculos, do mais recente ao mais antigo
   * @throws PapelNaoAutorizadoException se quem pede for contador (403)
   */
  @Transactional(readOnly = true)
  public List<VinculoProdutorResponse> listarDoProdutor() {
    UUID produtorId = exigirProdutor().getId();
    List<VinculoCarteira> vinculos = vinculoRepository.findAbertosDoProdutor(produtorId);
    Map<UUID, Contador> contadores = contadorRepository
        .findAllById(vinculos.stream().map(VinculoCarteira::getContadorId).toList()).stream()
        .collect(Collectors.toMap(Contador::getId, Function.identity()));
    return vinculos.stream()
        .map(v -> vinculoDoProdutor(v, contadores.get(v.getContadorId())))
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
    UUID produtorId = exigirProdutor().getId();
    VinculoCarteira vinculo = vinculoRepository
        .findByIdAndProdutorIdForUpdate(vinculoId, produtorId)
        .filter(v -> v.getSituacao() != SituacaoVinculo.ENCERRADO)
        .orElseThrow(() -> new ResourceNotFoundException("Vinculo", vinculoId));
    encerrarVinculo(vinculo);
    return vinculoDoProdutor(vinculo,
        contadorRepository.findById(vinculo.getContadorId()).orElse(null));
  }

  /**
   * Abre ou reativa o vínculo do par — consequência do aceite de um convite (consentimento do
   * produtor). Roda na transação de quem chama.
   *
   * @param contadorId escritório
   * @param produtorId produtor que aceitou
   */
  void ativarPorConvite(UUID contadorId, UUID produtorId) {
    vinculoRepository.findAbertoForUpdate(contadorId, produtorId).ifPresentOrElse(
        aberto -> aberto.setSituacao(SituacaoVinculo.ATIVO),
        () -> vinculoRepository.save(VinculoCarteira.builder()
            .contadorId(contadorId)
            .produtorId(produtorId)
            .situacao(SituacaoVinculo.ATIVO)
            .inicio(OffsetDateTime.now(clock))
            .build()));
  }

  private VinculoCarteira vinculoAberto(UUID produtorId) {
    UUID contadorId = exigirContador().contador().getId();
    return vinculoRepository.findAbertoForUpdate(contadorId, produtorId)
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

  private UsuarioAutenticado exigirContador() {
    UsuarioAutenticado usuario = usuarioProvider.get();
    if (!usuario.isContador()) {
      throw new PapelNaoAutorizadoException(usuario.papel(), PapelUsuario.CONTADOR);
    }
    return usuario;
  }

  private Produtor exigirProdutor() {
    UsuarioAutenticado usuario = usuarioProvider.get();
    if (usuario.isContador()) {
      throw new PapelNaoAutorizadoException(usuario.papel(), PapelUsuario.PRODUTOR);
    }
    return usuario.produtor();
  }

  private static Comparator<ProdutorCarteiraResponse> ordenacao(String sort) {
    String pedida = sort == null ? "" : sort.strip().toLowerCase(Locale.ROOT);
    if (pedida.isEmpty() || ORDEM_NOME.equals(pedida)) {
      return POR_NOME;
    }
    if (ORDEM_URGENCIA.equals(pedida)) {
      return POR_URGENCIA;
    }
    throw new FiltroCarteiraInvalidoException(sort, List.of(ORDEM_NOME, ORDEM_URGENCIA));
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

  private Map<UUID, List<String>> etiquetasPorVinculo(UUID contadorId, SituacaoVinculo situacao) {
    return vinculoRepository.findEtiquetasDaCarteira(contadorId, situacao).stream()
        .collect(Collectors.groupingBy(EtiquetaDoVinculo::vinculoId,
            Collectors.collectingAndThen(
                Collectors.mapping(EtiquetaDoVinculo::nome, Collectors.toList()),
                nomes -> nomes.stream()
                    .sorted(Comparator.comparing(NormalizacaoBusca::normalizar)).toList())));
  }

  /** Uma consulta agregada por indicador, sobre a carteira ativa inteira. */
  private Map<UUID, AtencaoCarteiraResponse> atencao(UUID contadorId) {
    LocalDate hoje = LocalDate.now(clock.withZone(FUSO));
    Map<UUID, Long> inconsistencias = porProdutor(vinculoRepository.contarNotasDaCarteira(
        contadorId, StatusNotaFiscal.COM_INCONSISTENCIAS));
    Map<UUID, Long> vencidas = porProdutor(
        vinculoRepository.contarGuiasVencidasDaCarteira(contadorId, hoje));
    Map<UUID, Long> proximos = porProdutor(vinculoRepository.contarGuiasNaFaixaDaCarteira(
        contadorId, StatusGuiaFiscal.PENDENTE, hoje, hoje.plusDays(DIAS_VENCIMENTOS_PROXIMOS)));

    Set<UUID> produtores = new HashSet<>(inconsistencias.keySet());
    produtores.addAll(vencidas.keySet());
    produtores.addAll(proximos.keySet());
    Map<UUID, AtencaoCarteiraResponse> atencao = new HashMap<>();
    for (UUID produtorId : produtores) {
      atencao.put(produtorId, new AtencaoCarteiraResponse(
          inconsistencias.getOrDefault(produtorId, 0L),
          // AGUARDANDO_APROVACAO chega com a issue #178.
          0L,
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
      List<String> etiquetas, AtencaoCarteiraResponse atencao) {
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
        etiquetas,
        atencao);
  }

  private static SituacaoVinculoResponse situacao(VinculoCarteira vinculo) {
    return new SituacaoVinculoResponse(vinculo.getProdutorId(), vinculo.getSituacao(),
        vinculo.getInicio(), vinculo.getFim());
  }

  private static VinculoProdutorResponse vinculoDoProdutor(VinculoCarteira vinculo,
      Contador contador) {
    return new VinculoProdutorResponse(
        vinculo.getId(),
        vinculo.getContadorId(),
        contador == null ? null : contador.getNomeCompleto(),
        contador == null ? null : contador.getCrc(),
        vinculo.getSituacao(),
        vinculo.getInicio(),
        vinculo.getFim());
  }
}
