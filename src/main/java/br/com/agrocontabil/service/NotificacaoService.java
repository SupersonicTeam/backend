package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.NotificacaoResponse;
import br.com.agrocontabil.dto.ResultadoNotificacaoResponse;
import br.com.agrocontabil.dto.VencimentoFiscalResponse;
import br.com.agrocontabil.exception.ContatoAusenteException;
import br.com.agrocontabil.exception.LimiteNotificacoesException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.CanalNotificacao;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Notificacao;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.model.StatusNotificacao;
import br.com.agrocontabil.model.TipoNotificacao;
import br.com.agrocontabil.model.UrgenciaVencimento;
import br.com.agrocontabil.repository.NotificacaoRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
import br.com.agrocontabil.service.auditoria.Auditado;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Notificação ao produtor por WhatsApp (issue #182). Decisões do Murillo: registro no banco (o
 * envio real fica atrás da porta {@link EnvioNotificacao}), {@code GET} do histórico e disparo
 * também pelo escritório.
 *
 * <p><strong>Itens</strong> vêm do calendário ({@link CalendarioFiscalService#vencimentos}: guias,
 * obrigações da #181 e pedidos de documento da #180), em três faixas: {@code URGENTE} vence hoje
 * ou amanhã, {@code PROXIMO} de 2 a 7 dias, {@code ATRASADO} é guia não paga com data passada ou
 * pedido de documento com prazo passado. "Próximas" envia urgentes (primeiro) e próximos.
 *
 * <p><strong>Alcance:</strong> o produtor dispara para si com os itens de todos os escritórios
 * que o atendem; o membro do escritório, só com os do próprio escritório (como no calendário), e
 * só vê no histórico o que o escritório dele disparou.
 *
 * <p><strong>Ordem das recusas:</strong> sem WhatsApp (telefone) → 422 {@code CONTATO_AUSENTE},
 * antes de olhar os itens — o frontend distingue de "nada a avisar" (200, {@code enviados = 0},
 * nada registrado). Teto de {@code app.notificacao.max-por-hora} disparos manuais por produtor
 * (429), conferido sob o lock do produtor. A porta de envio roda depois do commit.
 */
@Service
public class NotificacaoService {

  private static final Logger log = LoggerFactory.getLogger(NotificacaoService.class);

  /** "Próximas": de hoje até 7 dias. */
  static final int DIAS_PROXIMOS = 7;

  /** Quão para trás procurar atrasados. */
  static final int MESES_ATRASADOS = 12;

  /** Página padrão e teto do histórico. */
  public static final int TAMANHO_PAGINA_PADRAO = 50;
  public static final int TAMANHO_PAGINA_MAXIMO = 200;
  public static final int PAGINA_MAXIMA = 10_000;

  private static final String SISTEMA = "SISTEMA";
  private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
  private static final int LOTE_PRODUTORES = 100;

  private final AuthenticatedProdutorProvider produtorProvider;
  private final ProdutorEmContextoResolver produtorEmContexto;
  private final ProdutorRepository produtorRepository;
  private final VinculoCarteiraRepository vinculoRepository;
  private final CalendarioFiscalService calendario;
  private final NotificacaoRepository repository;
  private final EnvioNotificacao envio;
  private final TransactionTemplate transacao;
  private final TransactionTemplate leitura;
  private final TransactionTemplate porProdutor;
  private final Clock clock;
  private final int maxPorHora;

  public NotificacaoService(AuthenticatedProdutorProvider produtorProvider,
      ProdutorEmContextoResolver produtorEmContexto, ProdutorRepository produtorRepository,
      VinculoCarteiraRepository vinculoRepository, CalendarioFiscalService calendario,
      NotificacaoRepository repository, EnvioNotificacao envio,
      PlatformTransactionManager transactionManager, Clock clock,
      @Value("${app.notificacao.max-por-hora:10}") int maxPorHora) {
    if (maxPorHora < 1) {
      throw new IllegalStateException("app.notificacao.max-por-hora precisa ser >= 1.");
    }
    this.produtorProvider = produtorProvider;
    this.produtorEmContexto = produtorEmContexto;
    this.produtorRepository = produtorRepository;
    this.vinculoRepository = vinculoRepository;
    this.calendario = calendario;
    this.repository = repository;
    this.envio = envio;
    this.transacao = new TransactionTemplate(transactionManager);
    this.leitura = new TransactionTemplate(transactionManager);
    this.leitura.setReadOnly(true);
    this.porProdutor = new TransactionTemplate(transactionManager);
    this.porProdutor.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
    this.maxPorHora = maxPorHora;
  }

  /** Quem disparou: o produtor, um membro do escritório ou o sistema. */
  private record Disparo(UUID usuarioId, String papel, UUID escritorioId) {
  }

  /** Item avisado, com a faixa. */
  private record Item(VencimentoFiscalResponse vencimento, UrgenciaVencimento urgencia) {
  }

  // ---------------------------------------------------------------------------------------------
  // Produtor (rotas /api/notificacao; o produtor vem do token)
  // ---------------------------------------------------------------------------------------------

  /**
   * O produtor avisa a si os vencimentos dos próximos 7 dias.
   *
   * @return itens avisados e urgentes
   */
  @Auditado(acao = AcaoAuditada.NOTIFICACAO_REGISTRADA, recurso = RecursoAuditado.NOTIFICACAO)
  public ResultadoNotificacaoResponse proximasDoProdutor() {
    Produtor produtor = produtorProvider.get();
    return proximas(produtor, escritoriosAtivos(produtor), doProdutor(produtor));
  }

  /**
   * O produtor avisa a si o que está atrasado.
   *
   * @return itens avisados
   */
  @Auditado(acao = AcaoAuditada.NOTIFICACAO_REGISTRADA, recurso = RecursoAuditado.NOTIFICACAO)
  public ResultadoNotificacaoResponse atrasadosDoProdutor() {
    Produtor produtor = produtorProvider.get();
    return atrasados(produtor, escritoriosAtivos(produtor), doProdutor(produtor));
  }

  /**
   * Mensagem livre do produtor (para o próprio WhatsApp).
   *
   * @param mensagem texto, 1 a 500
   * @return a notificação registrada
   */
  @Auditado(acao = AcaoAuditada.NOTIFICACAO_REGISTRADA, recurso = RecursoAuditado.NOTIFICACAO)
  public ResultadoNotificacaoResponse mensagemDoProdutor(String mensagem) {
    Produtor produtor = produtorProvider.get();
    return mensagem(produtor, mensagem, doProdutor(produtor));
  }

  /**
   * Histórico do produtor: todas as notificações dele.
   *
   * @param pagina  página
   * @param tamanho itens por página
   * @return a página, mais recentes primeiro
   */
  public Page<NotificacaoResponse> listarDoProdutor(int pagina, int tamanho) {
    UUID produtorId = produtorProvider.get().getId();
    return leitura.execute(status -> repository.findByProdutorIdOrderByCriadoEmDescIdDesc(
        produtorId, PageRequest.of(pagina, tamanho)).map(NotificacaoService::resposta));
  }

  // ---------------------------------------------------------------------------------------------
  // Escritorio (rotas /api/contador/produtores/{produtorId}/notificacao; carteira ativa, 404 fora)
  // ---------------------------------------------------------------------------------------------

  /**
   * O escritório cobra o cliente: vencimentos dos próximos 7 dias do próprio escritório.
   *
   * @param produtorId cliente
   * @return itens avisados e urgentes
   */
  @Auditado(acao = AcaoAuditada.NOTIFICACAO_REGISTRADA, recurso = RecursoAuditado.NOTIFICACAO)
  public ResultadoNotificacaoResponse proximas(UUID produtorId) {
    Contador membro = membro(produtorId);
    return proximas(produtor(produtorId), List.of(membro.getEscritorioId()), doMembro(membro));
  }

  /**
   * O escritório cobra os atrasados do cliente.
   *
   * @param produtorId cliente
   * @return itens avisados
   */
  @Auditado(acao = AcaoAuditada.NOTIFICACAO_REGISTRADA, recurso = RecursoAuditado.NOTIFICACAO)
  public ResultadoNotificacaoResponse atrasados(UUID produtorId) {
    Contador membro = membro(produtorId);
    return atrasados(produtor(produtorId), List.of(membro.getEscritorioId()), doMembro(membro));
  }

  /**
   * Mensagem livre do escritório ao cliente.
   *
   * @param produtorId cliente
   * @param mensagem   texto, 1 a 500
   * @return a notificação registrada
   */
  @Auditado(acao = AcaoAuditada.NOTIFICACAO_REGISTRADA, recurso = RecursoAuditado.NOTIFICACAO)
  public ResultadoNotificacaoResponse mensagem(UUID produtorId, String mensagem) {
    Contador membro = membro(produtorId);
    return mensagem(produtor(produtorId), mensagem, doMembro(membro));
  }

  /**
   * Histórico do escritório com o cliente: só o que o próprio escritório disparou.
   *
   * @param produtorId cliente
   * @param pagina     página
   * @param tamanho    itens por página
   * @return a página, mais recentes primeiro
   */
  public Page<NotificacaoResponse> listar(UUID produtorId, int pagina, int tamanho) {
    UUID escritorioId = membro(produtorId).getEscritorioId();
    return leitura.execute(status -> repository
        .findByProdutorIdAndEscritorioIdOrderByCriadoEmDescIdDesc(produtorId, escritorioId,
            PageRequest.of(pagina, tamanho))
        .map(NotificacaoService::resposta));
  }

  // ---------------------------------------------------------------------------------------------
  // Aviso automatico (job diario)
  // ---------------------------------------------------------------------------------------------

  /**
   * Registra um aviso {@code URGENTE} por item que vence amanhã, para todo produtor com WhatsApp
   * — uma vez só por item e data ({@code chave_item}). Cada produtor numa transação própria: a
   * falha de um vira log e não impede os outros.
   *
   * @return avisos registrados
   */
  public int avisarVencimentosDeAmanha() {
    LocalDate amanha = LocalDate.now(clock).plusDays(1);
    int registrados = 0;
    List<UUID> lote = produtorRepository.findIdsComTelefone(PageRequest.ofSize(LOTE_PRODUTORES));
    while (!lote.isEmpty()) {
      for (UUID produtorId : lote) {
        if (Thread.currentThread().isInterrupted()) {
          return registrados;
        }
        try {
          Avisos avisos = porProdutor.execute(status -> avisarProdutor(produtorId, amanha));
          if (avisos != null) {
            avisos.registrados().forEach(aviso -> entregar(aviso, avisos.telefone()));
            registrados += avisos.registrados().size();
          }
        } catch (RuntimeException e) {
          log.error("Aviso automatico de vencimento falhou para o produtor {}", produtorId, e);
        }
      }
      lote = produtorRepository.findIdsComTelefoneApos(lote.get(lote.size() - 1),
          PageRequest.ofSize(LOTE_PRODUTORES));
    }
    return registrados;
  }

  /** Avisos gravados de um produtor, entregues à porta depois do commit. */
  private record Avisos(List<Notificacao> registrados, String telefone) {
  }

  private Avisos avisarProdutor(UUID produtorId, LocalDate amanha) {
    Produtor produtor = produtorRepository.findById(produtorId).orElse(null);
    if (produtor == null || !temContato(produtor)) {
      return null;
    }
    List<Notificacao> criados = new ArrayList<>();
    for (VencimentoFiscalResponse v : calendario.vencimentos(produtor,
        vinculoRepository.findEscritoriosAtivosDoProdutor(produtorId), amanha, amanha)) {
      if (pago(v)) {
        continue;
      }
      String chave = chave(v);
      if (repository.existsByProdutorIdAndChaveItem(produtorId, chave)) {
        continue;
      }
      criados.add(repository.save(Notificacao.builder()
          .produtorId(produtorId)
          .tipo(TipoNotificacao.AVISO_URGENTE)
          .canal(CanalNotificacao.WHATSAPP)
          .status(StatusNotificacao.REGISTRADA)
          .texto(limitar("AgroContabil: vence amanha (" + DATA.format(v.dataVencimento())
              + ").\n" + linha(new Item(v, UrgenciaVencimento.URGENTE))))
          .quantidadeItens(1)
          .urgentes(1)
          .chaveItem(chave)
          .criadoEm(OffsetDateTime.now(clock))
          .build()));
    }
    return new Avisos(criados, produtor.getTelefone());
  }

  // ---------------------------------------------------------------------------------------------

  private ResultadoNotificacaoResponse proximas(Produtor produtor, List<UUID> escritorios,
      Disparo disparo) {
    exigirContato(produtor);
    LocalDate hoje = LocalDate.now(clock);
    List<Item> itens = itens(produtor, escritorios, hoje, hoje.plusDays(DIAS_PROXIMOS),
        v -> !pago(v), hoje);
    StringBuilder texto = new StringBuilder("AgroContabil: vencimentos dos proximos ")
        .append(DIAS_PROXIMOS).append(" dias.");
    return registrarItens(produtor, disparo, TipoNotificacao.PROXIMAS_OBRIGACOES, texto, itens);
  }

  private ResultadoNotificacaoResponse atrasados(Produtor produtor, List<UUID> escritorios,
      Disparo disparo) {
    exigirContato(produtor);
    LocalDate hoje = LocalDate.now(clock);
    // Obrigacao do catalogo nao entra: o sistema nao sabe se foi cumprida.
    List<Item> itens = itens(produtor, escritorios, hoje.minusMonths(MESES_ATRASADOS),
        hoje.minusDays(1), v -> ("imposto".equals(v.tipo()) && !pago(v))
            || "solicitacao".equals(v.tipo()), hoje);
    StringBuilder texto = new StringBuilder("AgroContabil: itens em atraso.");
    return registrarItens(produtor, disparo, TipoNotificacao.ATRASADOS, texto, itens);
  }

  private ResultadoNotificacaoResponse mensagem(Produtor produtor, String mensagem,
      Disparo disparo) {
    exigirContato(produtor);
    return registrar(produtor, disparo, TipoNotificacao.MENSAGEM, mensagem.strip(), 1, 0);
  }

  private List<Item> itens(Produtor produtor, List<UUID> escritorios, LocalDate inicio,
      LocalDate fim, Predicate<VencimentoFiscalResponse> filtro, LocalDate hoje) {
    return leitura.execute(status -> calendario.vencimentos(produtor, escritorios, inicio, fim))
        .stream()
        .filter(filtro)
        .map(v -> new Item(v, urgencia(v.dataVencimento(), hoje)))
        .sorted(Comparator.comparing(Item::urgencia)
            .thenComparing(i -> i.vencimento().dataVencimento())
            .thenComparing(i -> i.vencimento().id()))
        .toList();
  }

  private ResultadoNotificacaoResponse registrarItens(Produtor produtor, Disparo disparo,
      TipoNotificacao tipo, StringBuilder texto, List<Item> itens) {
    if (itens.isEmpty()) {
      return new ResultadoNotificacaoResponse(0, 0, null, produtor.getId());
    }
    itens.forEach(item -> texto.append('\n').append(linha(item)));
    int urgentes = (int) itens.stream()
        .filter(i -> i.urgencia() == UrgenciaVencimento.URGENTE).count();
    return registrar(produtor, disparo, tipo, texto.toString(), itens.size(), urgentes);
  }

  /** Grava sob o lock do produtor, conferindo o teto por hora; a porta roda depois do commit. */
  private ResultadoNotificacaoResponse registrar(Produtor produtor, Disparo disparo,
      TipoNotificacao tipo, String texto, int quantidade, int urgentes) {
    Notificacao gravada = transacao.execute(status -> {
      produtorRepository.findByIdForUpdate(produtor.getId())
          .orElseThrow(() -> new ResourceNotFoundException("Produtor", produtor.getId()));
      OffsetDateTime agora = OffsetDateTime.now(clock);
      List<OffsetDateTime> naHora =
          repository.disparosManuaisDesde(produtor.getId(), agora.minusHours(1));
      if (naHora.size() >= maxPorHora) {
        // Libera quando o mais antigo da janela fizer uma hora.
        long espera = Duration.between(agora, naHora.get(0).plusHours(1)).toSeconds();
        throw new LimiteNotificacoesException(maxPorHora, espera);
      }
      return repository.save(Notificacao.builder()
          .produtorId(produtor.getId())
          .escritorioId(disparo.escritorioId())
          .disparadoPor(disparo.usuarioId())
          .papel(disparo.papel())
          .tipo(tipo)
          .canal(CanalNotificacao.WHATSAPP)
          .status(StatusNotificacao.REGISTRADA)
          .texto(limitar(texto))
          .quantidadeItens(quantidade)
          .urgentes(urgentes)
          .criadoEm(agora)
          .build());
    });
    entregar(gravada, produtor.getTelefone());
    return new ResultadoNotificacaoResponse(quantidade, urgentes, gravada.getId(),
        produtor.getId());
  }

  /**
   * Entrega pela porta, depois do commit. A falha do canal vira log e não muda a resposta: a
   * notificação já está registrada, e um 500 levaria o cliente a repetir o disparo (gastando o
   * teto por hora com duplicatas). Sem o texto nem o telefone no log.
   */
  private void entregar(Notificacao notificacao, String destinatario) {
    try {
      envio.enviar(notificacao, destinatario);
    } catch (RuntimeException e) {
      log.error("Canal de notificacao falhou para a notificacao {} (fica REGISTRADA): {}",
          notificacao.getId(), e.getClass().getSimpleName());
    }
  }

  private Contador membro(UUID produtorId) {
    return produtorEmContexto.exigirContadorComAcesso(produtorId).contador();
  }

  private Produtor produtor(UUID produtorId) {
    return produtorRepository.findById(produtorId)
        .orElseThrow(() -> new ResourceNotFoundException("Produtor", produtorId));
  }

  private List<UUID> escritoriosAtivos(Produtor produtor) {
    return vinculoRepository.findEscritoriosAtivosDoProdutor(produtor.getId());
  }

  private static Disparo doProdutor(Produtor produtor) {
    return new Disparo(produtor.getId(), PapelUsuario.PRODUTOR.name(), null);
  }

  private static Disparo doMembro(Contador membro) {
    return new Disparo(membro.getId(), PapelUsuario.CONTADOR.name(), membro.getEscritorioId());
  }

  private static void exigirContato(Produtor produtor) {
    if (!temContato(produtor)) {
      throw new ContatoAusenteException();
    }
  }

  private static boolean temContato(Produtor produtor) {
    return produtor.getTelefone() != null && !produtor.getTelefone().isBlank();
  }

  private static boolean pago(VencimentoFiscalResponse v) {
    return "pago".equals(v.status());
  }

  static UrgenciaVencimento urgencia(LocalDate data, LocalDate hoje) {
    long dias = ChronoUnit.DAYS.between(hoje, data);
    if (dias < 0) {
      return UrgenciaVencimento.ATRASADO;
    }
    return dias <= 1 ? UrgenciaVencimento.URGENTE : UrgenciaVencimento.PROXIMO;
  }

  /** Chave da idempotência do aviso: o tipo, o item e a data (obrigação recorrente repete o id). */
  static String chave(VencimentoFiscalResponse v) {
    return v.tipo() + ":" + v.id() + ":" + v.dataVencimento();
  }

  private static String linha(Item item) {
    VencimentoFiscalResponse v = item.vencimento();
    StringBuilder linha = new StringBuilder();
    if (item.urgencia() != UrgenciaVencimento.PROXIMO) {
      linha.append('[').append(item.urgencia()).append("] ");
    }
    linha.append(DATA.format(v.dataVencimento())).append(" - ").append(v.titulo());
    BigDecimal valor = v.valor();
    if (valor != null) {
      linha.append(" (").append(NumberFormat.getCurrencyInstance(PT_BR).format(valor))
          .append(')');
    }
    return linha.toString();
  }

  private static String limitar(String texto) {
    return texto.length() <= Notificacao.TEXTO_MAXIMO ? texto
        : texto.substring(0, Notificacao.TEXTO_MAXIMO - 3) + "...";
  }

  private static NotificacaoResponse resposta(Notificacao n) {
    return new NotificacaoResponse(n.getId(), n.getTipo(), n.getCanal(), n.getStatus(),
        n.getTexto(), n.getQuantidadeItens(), n.getUrgentes(),
        n.getPapel() == null ? SISTEMA : n.getPapel(), n.getCriadoEm());
  }
}
