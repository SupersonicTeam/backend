package br.com.agrocontabil.service;

import br.com.agrocontabil.config.ConviteCarteiraProperties;
import br.com.agrocontabil.dto.ConviteEnviadoResponse;
import br.com.agrocontabil.dto.ConviteRecebidoResponse;
import br.com.agrocontabil.exception.LimiteConvitesException;
import br.com.agrocontabil.exception.PapelNaoAutorizadoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.ConviteCarteira;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.model.SituacaoConvite;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ConviteCarteiraRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.security.UsuarioAutenticado;
import br.com.agrocontabil.security.UsuarioAutenticadoProvider;
import br.com.agrocontabil.service.auditoria.Auditado;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * Convite do escritório para o produtor entrar na carteira (issue #177).
 *
 * <p><strong>Sem oráculo de enumeração.</strong> O convite é gravado pelo e-mail sem olhar se
 * existe conta com ele, e a resposta é sempre 202: o escritório não descobre quem é cliente do
 * sistema (mesmo cuidado do {@code esqueci} da #209). O e-mail sai em segundo plano, depois do
 * commit.
 *
 * <p><strong>Vínculo só com o aceite.</strong> O produtor vê os convites dirigidos ao e-mail da
 * própria credencial — inclusive os de antes do cadastro — e aceita ou recusa. Nunca há vínculo
 * por CPF/CNPJ informado pelo escritório (quem soubesse o documento puxaria o cliente).
 */
@Service
public class ConviteCarteiraService {

  private static final Logger log = LoggerFactory.getLogger(ConviteCarteiraService.class);

  /** Janela do teto de convites. */
  static final Duration JANELA_TETO = Duration.ofHours(24);

  /**
   * Reenviar ao mesmo e-mail antes disso devolve o convite vigente, sem novo e-mail. Igual à
   * janela do teto de propósito: o reenvio apaga a linha anterior, e com intervalo menor um
   * endereço receberia várias mensagens no dia sem que nenhuma somasse no teto.
   */
  static final Duration INTERVALO_MINIMO_REENVIO = JANELA_TETO;

  /** Tamanho máximo do nome do escritório no texto do e-mail. */
  private static final int NOME_MAXIMO_NO_EMAIL = 120;

  private final UsuarioAutenticadoProvider usuarioProvider;
  private final ConviteCarteiraRepository conviteRepository;
  private final ContadorRepository contadorRepository;
  private final CredencialUsuarioRepository credencialRepository;
  private final CarteiraService carteiraService;
  private final EnvioConviteCarteira envio;
  private final ConviteCarteiraProperties propriedades;
  private final Clock clock;
  private final TransactionTemplate transacao;
  private final ThreadPoolTaskExecutor executor;

  public ConviteCarteiraService(
      UsuarioAutenticadoProvider usuarioProvider,
      ConviteCarteiraRepository conviteRepository,
      ContadorRepository contadorRepository,
      CredencialUsuarioRepository credencialRepository,
      CarteiraService carteiraService,
      EnvioConviteCarteira envio,
      ConviteCarteiraProperties propriedades,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.usuarioProvider = usuarioProvider;
    this.conviteRepository = conviteRepository;
    this.contadorRepository = contadorRepository;
    this.credencialRepository = credencialRepository;
    this.carteiraService = carteiraService;
    this.envio = envio;
    this.propriedades = propriedades;
    this.clock = clock;
    this.transacao = new TransactionTemplate(transactionManager);
    this.executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("agro-email-convite-");
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
   * Convida um e-mail para a carteira do contador autenticado. Reenviar substitui o convite
   * pendente anterior para o mesmo e-mail (com novo e-mail e nova validade), desde que o anterior
   * tenha mais de {@link #INTERVALO_MINIMO_REENVIO}; antes disso, nada muda.
   *
   * @param email e-mail do produtor
   * @return o convite criado (o id vai à auditoria; o controller responde 202 sem corpo)
   * @throws PapelNaoAutorizadoException se quem pede não for contador (403)
   * @throws LimiteConvitesException acima do teto diário (429)
   */
  @Auditado(acao = AcaoAuditada.CONVITE_ENVIADO, recurso = RecursoAuditado.CONVITE_CARTEIRA)
  public ConviteEnviadoResponse convidar(String email) {
    Contador contador = exigirContador().contador();
    String normalizado = CadastroService.normalizarEmail(email);
    OffsetDateTime agora = OffsetDateTime.now(clock);

    ConviteCarteira convite = transacao.execute(status -> {
      // Serializa os convites do escritorio: sem o lock, requisicoes simultaneas leriam a mesma
      // contagem e passariam juntas do teto.
      contadorRepository.findByIdForUpdate(contador.getId());
      OffsetDateTime inicioJanela = agora.minus(JANELA_TETO);
      long naJanela = conviteRepository.countByContadorIdAndCriadoEmAfter(
          contador.getId(), inicioJanela);
      if (naJanela >= propriedades.maxPorDia()) {
        long espera = conviteRepository
            .findFirstByContadorIdAndCriadoEmAfterOrderByCriadoEmAsc(contador.getId(), inicioJanela)
            .map(maisAntigo -> Duration.between(agora, maisAntigo.getCriadoEm().plus(JANELA_TETO))
                .toSeconds())
            .orElse(JANELA_TETO.toSeconds());
        throw new LimiteConvitesException(propriedades.maxPorDia(), espera);
      }
      Optional<ConviteCarteira> recente = conviteRepository
          .findByContadorIdAndEmailAndSituacao(contador.getId(), normalizado,
              SituacaoConvite.PENDENTE)
          .filter(c -> c.getCriadoEm().isAfter(agora.minus(INTERVALO_MINIMO_REENVIO)));
      if (recente.isPresent()) {
        // Reenvio logo depois: mesmo convite, sem outro e-mail. Sem isso, apagar e recriar
        // escaparia do teto diario e encheria a caixa de entrada de uma pessoa.
        return recente.get();
      }
      conviteRepository.apagarPendente(contador.getId(), normalizado);
      ConviteCarteira salvo = conviteRepository.save(ConviteCarteira.builder()
          .contadorId(contador.getId())
          .email(normalizado)
          .situacao(SituacaoConvite.PENDENTE)
          .criadoEm(agora)
          .expiraEm(agora.plus(propriedades.validade()))
          .build());
      String escritorio = nomeParaEmail(contador.getNomeCompleto());
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          enviarEmSegundoPlano(normalizado, escritorio);
        }
      });
      return salvo;
    });
    log.info("Convite da carteira registrado: convite={}, contador={}", convite.getId(),
        contador.getId());
    return enviado(convite);
  }

  /**
   * Convites pendentes do contador autenticado.
   *
   * @return convites válidos, do mais recente ao mais antigo
   */
  public List<ConviteEnviadoResponse> listarEnviados() {
    UUID contadorId = exigirContador().contador().getId();
    return conviteRepository.findByContadorIdAndSituacaoAndExpiraEmAfterOrderByCriadoEmDesc(
            contadorId, SituacaoConvite.PENDENTE, OffsetDateTime.now(clock)).stream()
        .map(ConviteCarteiraService::enviado)
        .toList();
  }

  /**
   * Convites pendentes dirigidos ao e-mail do produtor autenticado.
   *
   * @return convites válidos, do mais recente ao mais antigo
   * @throws PapelNaoAutorizadoException se quem pede for contador (403)
   */
  public List<ConviteRecebidoResponse> listarRecebidos() {
    String email = emailDoProdutor(exigirProdutor());
    if (email == null) {
      return List.of();
    }
    List<ConviteCarteira> convites = conviteRepository
        .findByEmailAndSituacaoAndExpiraEmAfterOrderByCriadoEmDesc(
            email, SituacaoConvite.PENDENTE, OffsetDateTime.now(clock));
    Map<UUID, Contador> contadores = contadorRepository
        .findAllById(convites.stream().map(ConviteCarteira::getContadorId).toList()).stream()
        .collect(Collectors.toMap(Contador::getId, Function.identity()));
    return convites.stream()
        .map(c -> recebido(c, contadores.get(c.getContadorId())))
        .toList();
  }

  /**
   * O produtor aceita o convite: o vínculo nasce (ou volta a valer) {@code ATIVO}.
   *
   * @param conviteId convite
   * @return o convite respondido
   * @throws ResourceNotFoundException convite inexistente, de outro e-mail, vencido ou já
   *     respondido (404)
   */
  @Auditado(acao = AcaoAuditada.CONVITE_ACEITO, recurso = RecursoAuditado.CONVITE_CARTEIRA)
  public ConviteRecebidoResponse aceitar(UUID conviteId) {
    return responder(conviteId, SituacaoConvite.ACEITO);
  }

  /**
   * O produtor recusa o convite.
   *
   * @param conviteId convite
   * @return o convite respondido
   * @throws ResourceNotFoundException convite inexistente, de outro e-mail, vencido ou já
   *     respondido (404)
   */
  @Auditado(acao = AcaoAuditada.CONVITE_RECUSADO, recurso = RecursoAuditado.CONVITE_CARTEIRA)
  public ConviteRecebidoResponse recusar(UUID conviteId) {
    return responder(conviteId, SituacaoConvite.RECUSADO);
  }

  private ConviteRecebidoResponse responder(UUID conviteId, SituacaoConvite resposta) {
    Produtor produtor = exigirProdutor();
    String email = emailDoProdutor(produtor);
    OffsetDateTime agora = OffsetDateTime.now(clock);
    return transacao.execute(status -> {
      ConviteCarteira convite = (email == null ? Optional.<ConviteCarteira>empty()
          : conviteRepository.findByIdAndEmailForUpdate(conviteId, email))
          .filter(c -> c.getSituacao() == SituacaoConvite.PENDENTE)
          .filter(c -> c.getExpiraEm().isAfter(agora))
          .orElseThrow(() -> new ResourceNotFoundException("Convite", conviteId));
      convite.setSituacao(resposta);
      convite.setRespondidoEm(agora);
      convite.setProdutorId(produtor.getId());
      if (resposta == SituacaoConvite.ACEITO) {
        carteiraService.ativarPorConvite(convite.getContadorId(), produtor.getId());
      }
      return recebido(convite, contadorRepository.findById(convite.getContadorId()).orElse(null));
    });
  }

  /** E-mail da credencial do produtor (o que ele usa para entrar), já normalizado. */
  private String emailDoProdutor(Produtor produtor) {
    return credencialRepository.findByProdutorId(produtor.getId())
        .map(CredencialUsuario::getEmail)
        .orElse(null);
  }

  private void enviarEmSegundoPlano(String email, String escritorio) {
    try {
      executor.execute(() -> {
        try {
          envio.enviarConvite(email, escritorio, propriedades.urlFrontend(),
              propriedades.validade());
        } catch (RuntimeException e) {
          // Sem o e-mail no log (LGPD); o convite ja existe e aparece para o produtor no login.
          log.warn("Falha ao enviar o e-mail do convite da carteira: {}",
              e.getClass().getSimpleName());
        }
      });
    } catch (TaskRejectedException e) {
      log.warn("Fila de e-mail cheia: aviso de convite da carteira descartado.");
    }
  }

  /** Nome do escritório sem caractere de controle, no tamanho do texto do e-mail. */
  static String nomeParaEmail(String nome) {
    String limpo = nome == null ? "" : nome.replaceAll("\\p{Cntrl}", " ").strip();
    return limpo.length() > NOME_MAXIMO_NO_EMAIL ? limpo.substring(0, NOME_MAXIMO_NO_EMAIL)
        : limpo;
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

  private static ConviteEnviadoResponse enviado(ConviteCarteira convite) {
    return new ConviteEnviadoResponse(convite.getId(), convite.getEmail(), convite.getSituacao(),
        convite.getCriadoEm(), convite.getExpiraEm());
  }

  private static ConviteRecebidoResponse recebido(ConviteCarteira convite, Contador contador) {
    return new ConviteRecebidoResponse(
        convite.getId(),
        contador == null ? null : contador.getNomeCompleto(),
        contador == null ? null : contador.getCrc(),
        convite.getSituacao(),
        convite.getCriadoEm(),
        convite.getExpiraEm());
  }
}
