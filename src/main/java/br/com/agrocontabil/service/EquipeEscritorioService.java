package br.com.agrocontabil.service;

import br.com.agrocontabil.config.ConviteEquipeProperties;
import br.com.agrocontabil.dto.CadastroEquipeRequest;
import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.dto.ConviteMembroResponse;
import br.com.agrocontabil.dto.EscritorioRequest;
import br.com.agrocontabil.dto.EscritorioResponse;
import br.com.agrocontabil.dto.MembroResponse;
import br.com.agrocontabil.exception.ConviteEquipeInvalidoException;
import br.com.agrocontabil.exception.EscritorioAtualEmUsoException;
import br.com.agrocontabil.exception.LimiteConvitesException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.exception.UltimoAdministradorException;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.ConviteMembro;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ConviteCarteiraRepository;
import br.com.agrocontabil.repository.ConviteMembroRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.EtiquetaCarteiraRepository;
import br.com.agrocontabil.repository.ObrigacaoFiscalRepository;
import br.com.agrocontabil.repository.NotificacaoRepository;
import br.com.agrocontabil.repository.PendenciaAnexoRepository;
import br.com.agrocontabil.repository.PendenciaRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.security.MembroEscritorioProvider;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.security.TokenOpaco;
import br.com.agrocontabil.service.auditoria.Auditado;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Escritório e equipe (issue #228): dados do escritório, membros e funções, convite de equipe e
 * entrada pelo convite (com conta ou pelo cadastro).
 *
 * <p><strong>Convite como o link de senha (#209):</strong> token de 256 bits só em SHA-256, no
 * fragmento do link, uso único, validade de 7 dias; resposta sempre 202, sem olhar se o e-mail
 * tem conta. Aceitar exige o mesmo e-mail na credencial — um link encaminhado não põe outra
 * pessoa na equipe.
 *
 * <p><strong>Nunca sem administrador:</strong> rebaixar, remover ou excluir os dados do último
 * administrador de uma equipe é 409. As mudanças de equipe rodam sob lock dos membros do
 * escritório, para dois administradores não se rebaixarem ao mesmo tempo.
 */
@Service
public class EquipeEscritorioService {

  private static final Logger log = LoggerFactory.getLogger(EquipeEscritorioService.class);

  /** Janela do teto de convites de equipe. */
  static final Duration JANELA_TETO = Duration.ofHours(24);

  private final MembroEscritorioProvider membroProvider;
  private final EscritorioRepository escritorioRepository;
  private final ContadorRepository contadorRepository;
  private final ConviteMembroRepository conviteRepository;
  private final CredencialUsuarioRepository credencialRepository;
  private final VinculoCarteiraRepository vinculoRepository;
  private final EtiquetaCarteiraRepository etiquetaRepository;
  private final ConviteCarteiraRepository conviteCarteiraRepository;
  private final ObrigacaoFiscalRepository obrigacaoRepository;
  private final PendenciaRepository pendenciaRepository;
  private final PendenciaAnexoRepository anexoRepository;
  private final NotificacaoRepository notificacaoRepository;
  private final CadastroService cadastroService;
  private final EnvioConviteEquipe envio;
  private final ConviteEquipeProperties propriedades;
  private final Clock clock;
  private final TransactionTemplate transacao;
  private final ThreadPoolTaskExecutor executor;

  public EquipeEscritorioService(
      MembroEscritorioProvider membroProvider,
      EscritorioRepository escritorioRepository,
      ContadorRepository contadorRepository,
      ConviteMembroRepository conviteRepository,
      CredencialUsuarioRepository credencialRepository,
      VinculoCarteiraRepository vinculoRepository,
      EtiquetaCarteiraRepository etiquetaRepository,
      ConviteCarteiraRepository conviteCarteiraRepository,
      ObrigacaoFiscalRepository obrigacaoRepository,
      PendenciaRepository pendenciaRepository,
      PendenciaAnexoRepository anexoRepository,
      NotificacaoRepository notificacaoRepository,
      CadastroService cadastroService,
      EnvioConviteEquipe envio,
      ConviteEquipeProperties propriedades,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.membroProvider = membroProvider;
    this.escritorioRepository = escritorioRepository;
    this.contadorRepository = contadorRepository;
    this.conviteRepository = conviteRepository;
    this.credencialRepository = credencialRepository;
    this.vinculoRepository = vinculoRepository;
    this.etiquetaRepository = etiquetaRepository;
    this.conviteCarteiraRepository = conviteCarteiraRepository;
    this.obrigacaoRepository = obrigacaoRepository;
    this.pendenciaRepository = pendenciaRepository;
    this.anexoRepository = anexoRepository;
    this.notificacaoRepository = notificacaoRepository;
    this.cadastroService = cadastroService;
    this.envio = envio;
    this.propriedades = propriedades;
    this.clock = clock;
    this.transacao = new TransactionTemplate(transactionManager);
    this.executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("agro-email-equipe-");
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(100);
    executor.initialize();
  }

  /** Encerra o pool de e-mail com a aplicação. */
  @PreDestroy
  void encerrar() {
    executor.shutdown();
  }

  /**
   * Escritório do contador autenticado.
   *
   * @return o escritório, com a função de quem pergunta
   */
  public EscritorioResponse escritorio() {
    Contador membro = membroProvider.membro();
    Escritorio escritorio = escritorioRepository.findById(membro.getEscritorioId())
        .orElseThrow(() -> new ResourceNotFoundException("Escritorio", membro.getEscritorioId()));
    return resposta(escritorio, membro);
  }

  /**
   * Edita nome e CNPJ do escritório (só administrador). O nome aparece nos e-mails de convite.
   *
   * @param request dados novos
   * @return o escritório
   */
  @Auditado(acao = AcaoAuditada.ESCRITORIO_ALTERADO, recurso = RecursoAuditado.ESCRITORIO)
  public EscritorioResponse atualizar(EscritorioRequest request) {
    Contador membro = membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR);
    return transacao.execute(status -> {
      Escritorio escritorio = escritorioRepository.findByIdForUpdate(membro.getEscritorioId())
          .orElseThrow(() -> new ResourceNotFoundException("Escritorio",
              membro.getEscritorioId()));
      escritorio.setNome(request.nome().strip());
      // PATCH: CNPJ ausente fica como esta; vazio apaga.
      if (request.cnpj() != null) {
        escritorio.setCnpj(request.cnpj().isBlank() ? null : request.cnpj());
      }
      return resposta(escritorio, membro);
    });
  }

  /**
   * Membros da equipe (qualquer membro vê).
   *
   * @return membros por nome
   */
  public List<MembroResponse> membros() {
    UUID escritorioId = membroProvider.membro().getEscritorioId();
    return contadorRepository.findByEscritorioIdOrderByNomeCompletoAsc(escritorioId).stream()
        .map(EquipeEscritorioService::membro)
        .toList();
  }

  /**
   * Troca a função de um membro (só administrador). O último administrador não é rebaixado.
   *
   * @param membroId membro
   * @param funcao   função nova
   * @return o membro
   * @throws ResourceNotFoundException membro de outro escritório (404)
   * @throws UltimoAdministradorException rebaixaria o último administrador (409)
   */
  @Auditado(acao = AcaoAuditada.FUNCAO_ALTERADA, recurso = RecursoAuditado.MEMBRO_ESCRITORIO)
  public MembroResponse alterarFuncao(UUID membroId, FuncaoEscritorio funcao) {
    UUID escritorioId = membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR)
        .getEscritorioId();
    return transacao.execute(status -> {
      List<Contador> equipe = contadorRepository.findByEscritorioIdForUpdate(escritorioId);
      Contador alvo = membroDa(equipe, membroId);
      if (alvo.getFuncao() == FuncaoEscritorio.ADMINISTRADOR
          && funcao != FuncaoEscritorio.ADMINISTRADOR && administradores(equipe) == 1) {
        throw new UltimoAdministradorException();
      }
      alvo.setFuncao(funcao);
      return membro(alvo);
    });
  }

  /**
   * Remove um membro da equipe (só administrador): os clientes dele ficam sem responsável e ele
   * deixa de alcançar a carteira. O último administrador não sai.
   *
   * @param membroId membro
   * @throws ResourceNotFoundException membro de outro escritório (404)
   * @throws UltimoAdministradorException removeria o último administrador (409)
   */
  @Auditado(acao = AcaoAuditada.MEMBRO_REMOVIDO, recurso = RecursoAuditado.MEMBRO_ESCRITORIO)
  public void remover(UUID membroId) {
    UUID escritorioId = membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR)
        .getEscritorioId();
    transacao.executeWithoutResult(status -> {
      List<Contador> equipe = contadorRepository.findByEscritorioIdForUpdate(escritorioId);
      Contador alvo = membroDa(equipe, membroId);
      if (alvo.getFuncao() == FuncaoEscritorio.ADMINISTRADOR && administradores(equipe) == 1) {
        throw new UltimoAdministradorException();
      }
      sairDoEscritorio(alvo);
    });
    log.info("Membro removido da equipe: membro={}, escritorio={}", membroId, escritorioId);
  }

  /**
   * Convida um e-mail para a equipe (só administrador). Sempre aceito (202), exista ou não conta;
   * reenviar ao mesmo e-mail em menos de 24 h não manda outro e-mail; o teto diário vale para o
   * escritório.
   *
   * @param email  e-mail do convidado
   * @param funcao função oferecida
   * @return o convite (o controller responde 202 sem corpo)
   * @throws LimiteConvitesException acima do teto diário (429)
   */
  @Auditado(acao = AcaoAuditada.MEMBRO_CONVIDADO, recurso = RecursoAuditado.MEMBRO_ESCRITORIO)
  public ConviteMembroResponse convidar(String email, FuncaoEscritorio funcao) {
    Contador admin = membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR);
    UUID escritorioId = admin.getEscritorioId();
    String normalizado = CadastroService.normalizarEmail(email);
    OffsetDateTime agora = OffsetDateTime.now(clock);

    ConviteMembro convite = transacao.execute(status -> {
      Escritorio escritorio = escritorioRepository.findByIdForUpdate(escritorioId)
          .orElseThrow(() -> new ResourceNotFoundException("Escritorio", escritorioId));
      var vigente = conviteRepository
          .findFirstByEscritorioIdAndEmailAndUsadoEmIsNullOrderByCriadoEmDesc(
              escritorioId, normalizado)
          .filter(c -> c.getCriadoEm().isAfter(agora.minus(JANELA_TETO)))
          .filter(c -> c.getExpiraEm().isAfter(agora));
      if (vigente.isPresent()) {
        // Mesmo e-mail em menos de 24 h: o convite vigente fica, sem novo e-mail (o reenvio
        // escaparia do teto e encheria a caixa de entrada de uma pessoa).
        return vigente.get();
      }
      OffsetDateTime inicioJanela = agora.minus(JANELA_TETO);
      long naJanela = conviteRepository.countByEscritorioIdAndCriadoEmAfter(
          escritorioId, inicioJanela);
      if (naJanela >= propriedades.maxPorDia()) {
        long espera = conviteRepository
            .findFirstByEscritorioIdAndCriadoEmAfterOrderByCriadoEmAsc(escritorioId, inicioJanela)
            .map(maisAntigo -> Duration.between(agora, maisAntigo.getCriadoEm().plus(JANELA_TETO))
                .toSeconds())
            .orElse(JANELA_TETO.toSeconds());
        throw new LimiteConvitesException(propriedades.maxPorDia(), espera);
      }
      conviteRepository.apagarPendentes(escritorioId, normalizado);
      String token = TokenOpaco.gerar();
      ConviteMembro salvo = conviteRepository.save(ConviteMembro.builder()
          .escritorioId(escritorioId)
          .email(normalizado)
          .funcao(funcao)
          .tokenHash(TokenOpaco.hash(token))
          .criadoEm(agora)
          .expiraEm(agora.plus(propriedades.validade()))
          .convidadoPor(admin.getId())
          .build());
      String nome = ConviteCarteiraService.nomeParaEmail(escritorio.getNome());
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          enviarEmSegundoPlano(normalizado, nome, propriedades.urlFrontend() + "#token=" + token);
        }
      });
      return salvo;
    });
    return convite(convite);
  }

  /**
   * Convites de equipe pendentes (só administrador).
   *
   * @return convites válidos, do mais recente ao mais antigo
   */
  public List<ConviteMembroResponse> convitesPendentes() {
    UUID escritorioId = membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR)
        .getEscritorioId();
    return conviteRepository
        .findByEscritorioIdAndUsadoEmIsNullAndExpiraEmAfterOrderByCriadoEmDesc(
            escritorioId, OffsetDateTime.now(clock)).stream()
        .map(EquipeEscritorioService::convite)
        .toList();
  }

  /**
   * O contador autenticado aceita o convite e entra na equipe. Se ele está num escritório
   * unipessoal vazio (sem clientes abertos), esse escritório sai; com clientes ou equipe, 409.
   *
   * @param token token do link
   * @return o contador como membro
   * @throws ConviteEquipeInvalidoException token inválido, vencido, usado ou de outro e-mail (400)
   * @throws EscritorioAtualEmUsoException o escritório atual tem clientes ou equipe (409)
   */
  @Auditado(acao = AcaoAuditada.MEMBRO_ENTROU, recurso = RecursoAuditado.MEMBRO_ESCRITORIO)
  public MembroResponse aceitar(String token) {
    Contador contador = membroProvider.contador();
    String email = credencialRepository.findByContadorId(contador.getId())
        .map(CredencialUsuario::getEmail).orElse(null);
    return transacao.execute(status -> {
      ConviteMembro convite = conviteValido(token, email);
      UUID conviteId = convite.getId();
      Contador gerenciado = contadorRepository.findById(contador.getId())
          .orElseThrow(ConviteEquipeInvalidoException::new);
      UUID anterior = gerenciado.getEscritorioId();
      if (convite.getEscritorioId().equals(anterior)) {
        // Ja e da equipe: funcao se troca por PATCH, sob a regra do ultimo administrador. Pelo
        // convite, o administrador unico se rebaixaria e deixaria o escritorio sem nenhum.
        throw new ConviteEquipeInvalidoException();
      }
      if (anterior != null) {
        sairDoAnteriorVazio(gerenciado, anterior);
        // As exclusoes em massa do escritorio antigo limpam o contexto de persistencia: o
        // contador e o convite voltam a ser lidos para a gravacao valer.
        gerenciado = contadorRepository.findById(contador.getId())
            .orElseThrow(ConviteEquipeInvalidoException::new);
        convite = conviteRepository.findById(conviteId)
            .orElseThrow(ConviteEquipeInvalidoException::new);
      }
      gerenciado.setEscritorioId(convite.getEscritorioId());
      gerenciado.setFuncao(convite.getFuncao());
      convite.setUsadoEm(OffsetDateTime.now(clock));
      return membro(gerenciado);
    });
  }

  /**
   * Cadastro de contador pelo link do convite: a conta nasce já na equipe, com o e-mail do
   * convite (sem escritório unipessoal).
   *
   * @param request token e dados do cadastro
   * @return a conta criada
   * @throws ConviteEquipeInvalidoException token inválido, vencido ou usado (400)
   */
  @Auditado(acao = AcaoAuditada.MEMBRO_ENTROU, recurso = RecursoAuditado.MEMBRO_ESCRITORIO)
  public CadastroResponse cadastrarPorConvite(CadastroEquipeRequest request) {
    // O convite e conferido antes do cadastro: um link usado ou vencido e 400, nunca o 409 de
    // e-mail ja cadastrado (que confirmaria a conta a quem tem o link).
    OffsetDateTime agora = OffsetDateTime.now(clock);
    String email = conviteRepository.findByTokenHash(TokenOpaco.hash(request.token()))
        .filter(c -> c.getUsadoEm() == null && c.getExpiraEm().isAfter(agora))
        .map(ConviteMembro::getEmail)
        .orElseThrow(ConviteEquipeInvalidoException::new);
    CadastroRequest cadastro = new CadastroRequest(PapelUsuario.CONTADOR,
        request.nomeCompleto(), request.cpfCnpj(), email, request.senha());
    return cadastroService.cadastrar(cadastro, contador -> {
      ConviteMembro convite = conviteValido(request.token(), email);
      contador.setEscritorioId(convite.getEscritorioId());
      contador.setFuncao(convite.getFuncao());
      convite.setUsadoEm(OffsetDateTime.now(clock));
    });
  }

  /**
   * Tira o contador do escritório na exclusão dos dados dele (#148). Sozinho, o escritório inteiro
   * sai (carteira, convites, catálogo); com equipe, só ele — e o último administrador de uma
   * equipe não sai. Roda na transação de quem chama.
   *
   * @param contador contador gerenciado
   * @throws UltimoAdministradorException é o último administrador de uma equipe (409)
   */
  void sairPorExclusao(Contador contador) {
    UUID escritorioId = contador.getEscritorioId();
    if (escritorioId == null) {
      return;
    }
    List<Contador> equipe = contadorRepository.findByEscritorioIdForUpdate(escritorioId);
    if (equipe.size() <= 1) {
      sairDoEscritorio(contador);
      apagarEscritorio(escritorioId);
      return;
    }
    if (contador.getFuncao() == FuncaoEscritorio.ADMINISTRADOR && administradores(equipe) == 1) {
      throw new UltimoAdministradorException();
    }
    sairDoEscritorio(contador);
  }

  private void sairDoAnteriorVazio(Contador contador, UUID anterior) {
    List<Contador> equipe = contadorRepository.findByEscritorioIdForUpdate(anterior);
    if (equipe.size() > 1 || vinculoRepository.contarAbertosDoEscritorio(anterior) > 0) {
      throw new EscritorioAtualEmUsoException();
    }
    sairDoEscritorio(contador);
    apagarEscritorio(anterior);
  }

  private void sairDoEscritorio(Contador contador) {
    vinculoRepository.limparResponsavel(contador.getId());
    vinculoRepository.limparAutorDoEncerramento(contador.getId());
    contador.setEscritorioId(null);
    contador.setFuncao(null);
    contadorRepository.saveAndFlush(contador);
  }

  /** Apaga o escritório e o que é dele (vínculos com a ficha, convites, catálogos, pendências). */
  private void apagarEscritorio(UUID escritorioId) {
    List<UUID> vinculos = vinculoRepository.findIdsDoEscritorio(escritorioId);
    if (!vinculos.isEmpty()) {
      vinculoRepository.apagarEtiquetasDosVinculos(vinculos);
      vinculoRepository.apagarPorIds(vinculos);
    }
    conviteCarteiraRepository.apagarDoEscritorio(escritorioId);
    etiquetaRepository.apagarDoEscritorio(escritorioId);
    conviteRepository.apagarDoEscritorio(escritorioId);
    obrigacaoRepository.apagarDoEscritorio(escritorioId);
    anexoRepository.apagarDoEscritorio(escritorioId);
    pendenciaRepository.apagarDoEscritorio(escritorioId);
    notificacaoRepository.apagarDoEscritorio(escritorioId);
    escritorioRepository.deleteById(escritorioId);
    escritorioRepository.flush();
  }

  private ConviteMembro conviteValido(String token, String email) {
    OffsetDateTime agora = OffsetDateTime.now(clock);
    return conviteRepository.findByTokenHashForUpdate(TokenOpaco.hash(token))
        .filter(c -> c.getUsadoEm() == null)
        .filter(c -> c.getExpiraEm().isAfter(agora))
        .filter(c -> email != null && c.getEmail().equals(email))
        .orElseThrow(ConviteEquipeInvalidoException::new);
  }

  private void enviarEmSegundoPlano(String email, String escritorio, String link) {
    try {
      executor.execute(() -> {
        try {
          envio.enviarConvite(email, escritorio, link, propriedades.validade());
        } catch (RuntimeException e) {
          log.warn("Falha ao enviar o e-mail do convite de equipe: {}",
              e.getClass().getSimpleName());
        }
      });
    } catch (TaskRejectedException e) {
      log.warn("Fila de e-mail cheia: convite de equipe descartado.");
    }
  }

  private static Contador membroDa(List<Contador> equipe, UUID membroId) {
    return equipe.stream().filter(c -> c.getId().equals(membroId)).findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("Membro", membroId));
  }

  private static long administradores(List<Contador> equipe) {
    return equipe.stream().filter(c -> c.getFuncao() == FuncaoEscritorio.ADMINISTRADOR).count();
  }

  private EscritorioResponse resposta(Escritorio escritorio, Contador membro) {
    return new EscritorioResponse(escritorio.getId(), escritorio.getNome(), escritorio.getCnpj(),
        membro.getFuncao(), contadorRepository.countByEscritorioId(escritorio.getId()));
  }

  private static MembroResponse membro(Contador contador) {
    return new MembroResponse(contador.getId(), contador.getNomeCompleto(), contador.getCrc(),
        contador.getFuncao());
  }

  private static ConviteMembroResponse convite(ConviteMembro convite) {
    return new ConviteMembroResponse(convite.getId(), convite.getEmail(), convite.getFuncao(),
        convite.getCriadoEm(), convite.getExpiraEm());
  }
}
