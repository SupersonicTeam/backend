package br.com.agrocontabil.service;

import br.com.agrocontabil.model.AcaoRevisao;
import br.com.agrocontabil.model.NotaFiscalRevisao;
import br.com.agrocontabil.model.NotaFiscalValidacao;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.StatusPendencia;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.repository.CompraInsumoAgregado;
import br.com.agrocontabil.repository.CompraInsumoTotal;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRevisaoRepository;
import br.com.agrocontabil.repository.NotaFiscalValidacaoRepository;
import br.com.agrocontabil.repository.NotaPorNumeroLinha;
import br.com.agrocontabil.repository.NotaResumo;
import br.com.agrocontabil.repository.PendenciaRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.TotalAgregado;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.repository.VencimentoGuiaResumo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consultas de dados do produtor usadas pelas funções da Ceres (issue #142).
 *
 * <p>Todo método recebe o {@code produtorId} explicitamente e toda consulta filtra por ele. As
 * funções da Ceres não podem ler o {@code SecurityContext} (com streaming elas rodam em outra
 * thread, issue #143), então quem chama resolve o produtor antes e o passa adiante.
 *
 * <p><strong>Contrato de segurança:</strong> {@code produtorId} tem de vir de identidade resolvida
 * no servidor (o {@code ContextoAssistente}), nunca de entrada do cliente ou do modelo.
 *
 * <p>As regras de recorte (entrada = {@code IMPORTADA} + {@code AUTORIZADA}, competência pela
 * emissão em {@code America/Sao_Paulo}) são as mesmas do crédito e da apuração: estão nas queries
 * do {@link NotaFiscalRepository}, não aqui.
 */
@Service
@Transactional(readOnly = true)
public class ConsultaDadosProdutorService {

  /** Fuso de referência do projeto para o recorte por data. */
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  /** Maior lista que uma consulta devolve (minimização, issue #144). */
  public static final int LIMITE_MAXIMO = 10;

  private static final Pattern PREFIXO_NCM = Pattern.compile("\\d{2,8}");

  /** Notas com o mesmo número que uma consulta devolve (séries e notas de fornecedor). */
  public static final int LIMITE_NOTAS_POR_NUMERO = 3;

  private final NotaFiscalRepository notaFiscalRepository;
  private final GuiaFiscalRepository guiaFiscalRepository;
  private final NotaFiscalRevisaoRepository revisaoRepository;
  private final NotaFiscalValidacaoRepository validacaoRepository;
  private final SinaisNotaConferirService sinaisService;
  private final PendenciaRepository pendenciaRepository;
  private final ProdutorRepository produtorRepository;
  private final VinculoCarteiraRepository vinculoRepository;

  /**
   * Constrói o serviço com os repositórios de notas, guias, revisões e validações, os sinais de
   * nota a conferir (#276), e o produtor e os vínculos para o perfil do atalho (#347).
   */
  public ConsultaDadosProdutorService(
      NotaFiscalRepository notaFiscalRepository, GuiaFiscalRepository guiaFiscalRepository,
      NotaFiscalRevisaoRepository revisaoRepository,
      NotaFiscalValidacaoRepository validacaoRepository,
      SinaisNotaConferirService sinaisService, PendenciaRepository pendenciaRepository,
      ProdutorRepository produtorRepository, VinculoCarteiraRepository vinculoRepository) {
    this.produtorRepository = produtorRepository;
    this.vinculoRepository = vinculoRepository;
    this.notaFiscalRepository = notaFiscalRepository;
    this.guiaFiscalRepository = guiaFiscalRepository;
    this.revisaoRepository = revisaoRepository;
    this.validacaoRepository = validacaoRepository;
    this.sinaisService = sinaisService;
    this.pendenciaRepository = pendenciaRepository;
  }

  /** Notas ainda não emitidas que podem ter sinal a conferir. */
  static final Set<StatusNotaFiscal> NAO_EMITIDAS = java.util.EnumSet.of(
      StatusNotaFiscal.RASCUNHO, StatusNotaFiscal.COM_INCONSISTENCIAS,
      StatusNotaFiscal.DEVOLVIDA, StatusNotaFiscal.AGUARDANDO_APROVACAO,
      StatusNotaFiscal.APROVADA);

  /** Candidatas a sinal que o resumo da Ceres examina (#266): o teto de consultas por pergunta. */
  public static final int LIMITE_CANDIDATAS_A_SINAL = 10;

  /**
   * Notas próprias com sinal a conferir (#276), para o resumo da Ceres (#266): as ainda não
   * emitidas (rascunho até aprovada) e as autorizadas desde {@code autorizadasDesde} (dentro do
   * prazo de cancelamento), das {@value #LIMITE_CANDIDATAS_A_SINAL} mais recentes. Só as que têm
   * sinal saem.
   *
   * @param produtorId       produtor da sessão
   * @param autorizadasDesde início do prazo de cancelamento das autorizadas
   * @return as notas com sinal, da mais recente para a mais antiga
   */
  public List<NotaPorNumeroResultado> notasAConferir(UUID produtorId,
      OffsetDateTime autorizadasDesde) {
    return notaFiscalRepository.findCandidatasASinal(produtorId,
            NAO_EMITIDAS, autorizadasDesde,
            PageRequest.of(0, LIMITE_CANDIDATAS_A_SINAL))
        .stream()
        .map(n -> soSinais(produtorId, n))
        .filter(n -> !n.sinais().isEmpty())
        .toList();
  }

  /**
   * A nota só com os sinais: o resumo não usa o motivo da devolução nem as inconsistências, e
   * carregá-los custaria duas consultas a mais por candidata.
   */
  private NotaPorNumeroResultado soSinais(UUID produtorId, NotaPorNumeroLinha n) {
    return new NotaPorNumeroResultado(n.numero(), n.serie(), n.dataEmissao(), n.origem(),
        n.status(), duasCasas(n.valorTotal()), duasCasas(n.valorIbs()), duasCasas(n.valorCbs()),
        null, List.of(),
        sinaisService.sinais(produtorId, new SinaisNotaConferirService.NotaParaSinal(
            n.id(), n.origem(), n.status(), n.dataEmissao(), n.destinatarioCpfCnpj(),
            n.valorTotal())));
  }

  /**
   * Pedidos de documento do escritório ainda sem resposta (#180), dos escritórios com vínculo
   * {@code ATIVO}, do mais recente para o mais antigo.
   *
   * @param produtorId produtor da sessão
   * @return título e prazo de cada pedido
   */
  public List<PedidoPendente> pedidosPendentes(UUID produtorId) {
    return pendenciaRepository.findDoProdutor(produtorId, List.of(StatusPendencia.PENDENTE))
        .stream()
        .map(p -> new PedidoPendente(p.getTitulo(), p.getDataLimite()))
        .toList();
  }

  /**
   * Um pedido do escritório.
   *
   * @param titulo     título escrito pelo escritório
   * @param dataLimite prazo, ou nulo
   */
  public record PedidoPendente(String titulo, LocalDate dataLimite) {}

  /**
   * Notas do produtor com o número pedido (issue #256). Número de nota de outro produtor dá lista
   * vazia, igual a número inexistente: não revela existência.
   *
   * <p>O motivo da devolução só vem com a nota ainda {@code DEVOLVIDA}, e as inconsistências só com
   * ela em {@code COM_INCONSISTENCIAS}: validação que passa não grava lote, então numa nota
   * devolvida, de volta a rascunho ou já emitida o último lote pode ser de erros já corrigidos.
   *
   * @param produtorId produtor da sessão
   * @param numero     número da nota
   * @return as próprias primeiro, no máximo {@link #LIMITE_NOTAS_POR_NUMERO}, e quantas existem
   */
  public NotasPorNumero notasPorNumero(UUID produtorId, long numero) {
    Page<NotaPorNumeroLinha> pagina = notaFiscalRepository.findDoProdutorPorNumero(
        produtorId, numero, PageRequest.of(0, LIMITE_NOTAS_POR_NUMERO));
    return new NotasPorNumero(
        pagina.getContent().stream().map(n -> detalhe(produtorId, n)).toList(),
        pagina.getTotalElements());
  }

  /**
   * Notas consultadas pelo número.
   *
   * @param notas no máximo {@link #LIMITE_NOTAS_POR_NUMERO}
   * @param total quantas o produtor tem com esse número
   */
  public record NotasPorNumero(List<NotaPorNumeroResultado> notas, long total) {}

  private NotaPorNumeroResultado detalhe(UUID produtorId, NotaPorNumeroLinha n) {
    String motivo = n.status() != StatusNotaFiscal.DEVOLVIDA ? null
        : revisaoRepository.findByNotaFiscalIdOrderByOcorridoEmDesc(n.id()).stream()
            .filter(r -> r.getAcao() == AcaoRevisao.DEVOLVIDA)
            .map(NotaFiscalRevisao::getMotivo)
            .findFirst().orElse(null);
    List<NotaPorNumeroResultado.Inconsistencia> inconsistencias = List.of();
    if (n.status() == StatusNotaFiscal.COM_INCONSISTENCIAS) {
      List<NotaFiscalValidacao> validacoes =
          validacaoRepository.findByNotaFiscalIdOrderByValidadoEmDesc(n.id());
      if (!validacoes.isEmpty()) {
        UUID ultimoLote = validacoes.get(0).getLoteValidacao();
        inconsistencias = validacoes.stream()
            .filter(v -> v.getLoteValidacao().equals(ultimoLote))
            .map(v -> new NotaPorNumeroResultado.Inconsistencia(
                v.getCode(), v.getField(), v.getMessage(), v.getSuggestion()))
            .toList();
      }
    }
    // IBS/CBS destacados na nota, como em listarNotas (o credito da compra e outra coisa).
    return new NotaPorNumeroResultado(n.numero(), n.serie(), n.dataEmissao(), n.origem(),
        n.status(), duasCasas(n.valorTotal()), duasCasas(n.valorIbs()), duasCasas(n.valorCbs()),
        motivo, inconsistencias,
        sinaisService.sinais(produtorId, new SinaisNotaConferirService.NotaParaSinal(
            n.id(), n.origem(), n.status(), n.dataEmissao(), n.destinatarioCpfCnpj(),
            n.valorTotal())));
  }

  /**
   * Compras de um insumo identificado por NCM (código completo ou prefixo de 2 a 8 dígitos).
   *
   * @param produtorId produtor da sessão
   * @param prefixoNcm 2 a 8 dígitos (ex.: {@code 3808} = inseticidas, herbicidas, fungicidas)
   * @param inicio     primeiro dia (inclusivo)
   * @param fim        último dia (inclusivo)
   * @return total, notas e quebra por unidade
   * @throws IllegalArgumentException se o prefixo não tiver só 2 a 8 dígitos
   */
  public ComprasInsumoResultado comprasPorNcm(
      UUID produtorId, String prefixoNcm, LocalDate inicio, LocalDate fim) {
    if (prefixoNcm == null || !PREFIXO_NCM.matcher(prefixoNcm).matches()) {
      throw new IllegalArgumentException("Prefixo de NCM deve ter de 2 a 8 digitos.");
    }
    String padrao = prefixoNcm + "%";
    return resultado(
        notaFiscalRepository.totalizarComprasPorNcm(
            produtorId, padrao, inicioDoDia(inicio), fimExclusivo(fim)),
        notaFiscalRepository.somarComprasPorNcm(
            produtorId, padrao, inicioDoDia(inicio), fimExclusivo(fim)));
  }

  /**
   * Compras de um insumo pela descrição do item (contém, sem diferenciar maiúsculas). Curingas do
   * {@code LIKE} digitados pelo usuário são tratados como texto.
   *
   * @param produtorId produtor da sessão
   * @param termo      trecho da descrição (ex.: {@code fertilizante})
   * @param inicio     primeiro dia (inclusivo)
   * @param fim        último dia (inclusivo)
   * @return total, notas e quebra por unidade
   */
  public ComprasInsumoResultado comprasPorDescricao(
      UUID produtorId, String termo, LocalDate inicio, LocalDate fim) {
    String padrao = "%" + escaparLike(termo.trim().toLowerCase(Locale.ROOT)) + "%";
    return resultado(
        notaFiscalRepository.totalizarComprasPorDescricao(
            produtorId, padrao, inicioDoDia(inicio), fimExclusivo(fim)),
        notaFiscalRepository.somarComprasPorDescricao(
            produtorId, padrao, inicioDoDia(inicio), fimExclusivo(fim)));
  }

  /**
   * Vendas de um produto (issue #248): saídas próprias autorizadas no período, só os itens com CFOP
   * de venda ({@code x1xx}), por NCM (2 a 8 dígitos) ou pela descrição do item, com a mesma
   * competência das compras. Mesmo resultado das
   * compras (total, notas e quebra por unidade).
   *
   * @param produtorId produtor da sessão
   * @param termo      NCM (2 a 8 dígitos) ou trecho da descrição
   * @param porNcm     {@code true} se o termo é NCM
   * @param inicio     primeiro dia (inclusivo)
   * @param fim        último dia (inclusivo)
   * @return total, notas e quebra por unidade
   * @throws IllegalArgumentException sem termo, ou se {@code porNcm} e o termo não tiver só 2 a 8
   *                                  dígitos
   */
  public ComprasInsumoResultado vendasDeProduto(
      UUID produtorId, String termo, boolean porNcm, LocalDate inicio, LocalDate fim) {
    if (termo == null || termo.isBlank()) {
      throw new IllegalArgumentException("Informe o produto ou o NCM.");
    }
    if (porNcm && !PREFIXO_NCM.matcher(termo).matches()) {
      throw new IllegalArgumentException("Prefixo de NCM deve ter de 2 a 8 digitos.");
    }
    OffsetDateTime de = inicioDoDia(inicio);
    OffsetDateTime ate = fimExclusivo(fim);
    if (porNcm) {
      String prefixo = termo + "%";
      return resultado(notaFiscalRepository.totalizarVendasPorNcm(produtorId, prefixo, de, ate),
          notaFiscalRepository.somarVendasPorNcm(produtorId, prefixo, de, ate));
    }
    String padrao = "%" + escaparLike(termo.trim().toLowerCase(Locale.ROOT)) + "%";
    return resultado(
        notaFiscalRepository.totalizarVendasPorDescricao(produtorId, padrao, de, ate),
        notaFiscalRepository.somarVendasPorDescricao(produtorId, padrao, de, ate));
  }

  /**
   * NCMs comprados de um insumo, pelo mesmo critério de {@link #comprasPorNcm} (termo de 2 a 8
   * dígitos) ou {@link #comprasPorDescricao} (texto), no mesmo recorte e maior valor primeiro.
   * Issue #170: no MISTO, a busca na lei inclui os itens de anexo desses NCMs.
   *
   * @param produtorId     produtor da sessão
   * @param descricaoOuNcm o termo já normalizado pela função {@code totalComprasPorInsumo}
   * @param inicio         primeiro dia (inclusivo)
   * @param fim            último dia (inclusivo)
   * @param limite         quantos NCMs (1 a {@link #LIMITE_MAXIMO})
   * @return NCMs de 8 dígitos, sem repetição
   */
  public List<String> ncmsComprados(
      UUID produtorId, String descricaoOuNcm, LocalDate inicio, LocalDate fim, int limite) {
    PageRequest pagina = PageRequest.of(0, limitar(limite));
    if (PREFIXO_NCM.matcher(descricaoOuNcm).matches()) {
      return notaFiscalRepository.listarNcmsCompradosPorNcm(produtorId, descricaoOuNcm + "%",
          inicioDoDia(inicio), fimExclusivo(fim), pagina);
    }
    String padrao = "%" + escaparLike(descricaoOuNcm.trim().toLowerCase(Locale.ROOT)) + "%";
    return notaFiscalRepository.listarNcmsCompradosPorDescricao(
        produtorId, padrao, inicioDoDia(inicio), fimExclusivo(fim), pagina);
  }

  /**
   * O que o atalho "O que muda para mim com o IBS e o CBS?" usa para personalizar o texto (issue
   * #347): o tipo do produtor, se um escritório o atende (vínculo {@code ATIVO}) e os NCMs que ele
   * mais vendeu no período. Nada de dado pessoal, valor ou comprador.
   *
   * @param tipo                  tipo cadastrado; nulo se não cadastrado
   * @param atendidoPorEscritorio algum escritório com vínculo ativo
   * @param ncmsMaisVendidos      NCMs de 8 dígitos, do maior valor vendido ao menor
   */
  public record PerfilAtalho(
      TipoProdutor tipo, boolean atendidoPorEscritorio, List<String> ncmsMaisVendidos) {

    /** Cópia defensiva. */
    public PerfilAtalho {
      ncmsMaisVendidos = List.copyOf(ncmsMaisVendidos);
    }
  }

  /**
   * Perfil do produtor para o atalho de legislação da Ceres (issue #347).
   *
   * @param produtorId produtor da sessão
   * @param inicio     primeiro dia das vendas (inclusivo)
   * @param fim        último dia das vendas (inclusivo)
   * @param limite     quantos NCMs (1 a {@link #LIMITE_MAXIMO})
   * @return o perfil; produtor inexistente = tipo nulo e nenhum NCM
   */
  public PerfilAtalho perfilDoAtalho(UUID produtorId, LocalDate inicio, LocalDate fim, int limite) {
    TipoProdutor tipo = produtorRepository.findById(produtorId)
        .map(Produtor::getTipoProdutor).orElse(null);
    boolean atendido = !vinculoRepository.findEscritoriosAtivosDoProdutor(produtorId).isEmpty();
    List<String> ncms = notaFiscalRepository.listarNcmsVendidos(produtorId, inicioDoDia(inicio),
        fimExclusivo(fim), PageRequest.of(0, limitar(limite)));
    return new PerfilAtalho(tipo, atendido, ncms);
  }

  /**
   * Notas do produtor no período, mais recentes primeiro, limitadas a {@link #LIMITE_MAXIMO}.
   *
   * @param produtorId produtor da sessão
   * @param origens    origens aceitas (nunca vazio)
   * @param status     situações aceitas (nunca vazio)
   * @param inicio     primeiro dia (inclusivo)
   * @param fim        último dia (inclusivo)
   * @param limite     quantas devolver (1 a {@link #LIMITE_MAXIMO})
   * @return página com as notas e o total encontrado
   */
  public Page<NotaResumo> listarNotas(
      UUID produtorId, Set<OrigemNotaFiscal> origens, Set<StatusNotaFiscal> status,
      LocalDate inicio, LocalDate fim, int limite) {
    return notaFiscalRepository.listarResumos(
        produtorId, origens, status, inicioDoDia(inicio), fimExclusivo(fim),
        PageRequest.of(0, limitar(limite)));
  }

  /**
   * Guias não pagas com vencimento em {@code [de, ate]}, da mais próxima para a mais distante.
   *
   * @param produtorId produtor da sessão
   * @param de         primeiro dia (inclusivo)
   * @param ate        último dia (inclusivo)
   * @param limite     quantas devolver (1 a {@link #LIMITE_MAXIMO})
   * @return página com as guias e o total encontrado
   */
  public Page<VencimentoGuiaResumo> vencimentosNaoPagos(
      UUID produtorId, LocalDate de, LocalDate ate, int limite) {
    return guiaFiscalRepository.listarNaoPagasNaJanela(
        produtorId, de, ate, PageRequest.of(0, limitar(limite)));
  }

  /**
   * Quantidade e soma do valor total das notas do filtro — todas, não só as
   * {@link #LIMITE_MAXIMO} que {@link #listarNotas} devolve (issue #169). Mesmo recorte e mesmo
   * filtro por produtor; contagem e soma saem da mesma consulta, sobre o mesmo conjunto.
   *
   * @return quantidade e soma com 2 casas (zero sem notas)
   */
  public TotalAgregado totalizarNotas(
      UUID produtorId, Set<OrigemNotaFiscal> origens, Set<StatusNotaFiscal> status,
      LocalDate inicio, LocalDate fim) {
    return normalizado(notaFiscalRepository.totalizarResumos(
        produtorId, origens, status, inicioDoDia(inicio), fimExclusivo(fim)));
  }

  /**
   * Quantidade e soma do valor de todas as guias não pagas com vencimento em {@code [de, ate]} —
   * inclusive as que não cabem na lista de {@link #vencimentosNaoPagos} (issue #169).
   *
   * @return quantidade e soma com 2 casas (zero sem guias)
   */
  public TotalAgregado totalizarVencimentosNaoPagos(UUID produtorId, LocalDate de, LocalDate ate) {
    return normalizado(guiaFiscalRepository.totalizarNaoPagasNaJanela(produtorId, de, ate));
  }

  /**
   * Escapa os curingas do {@code LIKE} com {@code !} (o {@code ESCAPE} das queries): o texto do
   * usuário é literal. O próprio {@code !} é escapado primeiro.
   */
  static String escaparLike(String texto) {
    return texto.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private static ComprasInsumoResultado resultado(
      CompraInsumoTotal total, List<CompraInsumoAgregado> porUnidade) {
    BigDecimal valor = total == null ? null : total.valorTotal();
    long notas = total == null || total.quantidadeNotas() == null ? 0 : total.quantidadeNotas();
    return new ComprasInsumoResultado(duasCasas(valor), notas, porUnidade);
  }

  private static TotalAgregado normalizado(TotalAgregado total) {
    long quantidade = total == null || total.quantidade() == null ? 0 : total.quantidade();
    return new TotalAgregado(quantidade, duasCasas(total == null ? null : total.valor()));
  }

  private static BigDecimal duasCasas(BigDecimal valor) {
    return (valor == null ? BigDecimal.ZERO : valor).setScale(2, RoundingMode.HALF_UP);
  }

  private static int limitar(int limite) {
    return Math.max(1, Math.min(limite, LIMITE_MAXIMO));
  }

  private static OffsetDateTime inicioDoDia(LocalDate dia) {
    return dia.atStartOfDay(ZONE).toOffsetDateTime();
  }

  private static OffsetDateTime fimExclusivo(LocalDate ultimoDia) {
    return ultimoDia.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
  }
}
