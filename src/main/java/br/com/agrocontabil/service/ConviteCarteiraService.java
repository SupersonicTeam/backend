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
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.model.SituacaoConvite;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ConviteCarteiraRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.security.MembroEscritorioProvider;
import br.com.agrocontabil.service.auditoria.Auditado;
import jakarta.annotation.PreDestroy;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
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

  /** Endereço no nome do escritório: URL com esquema, {@code www.}, e-mail ou domínio (#460). */
  private static final Pattern ENDERECO_NO_NOME = Pattern.compile(
      "(?iu)[a-z][a-z0-9+.-]*://\\S*"
          + "|\\bwww\\.\\S*"
          + "|\\S+@\\S+"
          + "|(?<![\\p{L}\\p{N}])[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.\\p{L}{2,}"
          + "(?![\\p{L}\\p{N}])\\S*");

  private final MembroEscritorioProvider membroProvider;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final EscritorioRepository escritorioRepository;
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
      MembroEscritorioProvider membroProvider,
      AuthenticatedProdutorProvider produtorProvider,
      EscritorioRepository escritorioRepository,
      ConviteCarteiraRepository conviteRepository,
      ContadorRepository contadorRepository,
      CredencialUsuarioRepository credencialRepository,
      CarteiraService carteiraService,
      EnvioConviteCarteira envio,
      ConviteCarteiraProperties propriedades,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.membroProvider = membroProvider;
    this.produtorProvider = produtorProvider;
    this.escritorioRepository = escritorioRepository;
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
   * Convida um e-mail para a carteira do escritório do contador autenticado (só
   * administrador). Reenviar substitui o convite
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
    Contador contador = membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR);
    UUID escritorioId = contador.getEscritorioId();
    String normalizado = CadastroService.normalizarEmail(email);
    OffsetDateTime agora = OffsetDateTime.now(clock);

    ConviteCarteira convite = transacao.execute(status -> {
      // Serializa os convites do escritorio: sem o lock, requisicoes simultaneas leriam a mesma
      // contagem e passariam juntas do teto.
      Escritorio escritorio = escritorioRepository.findByIdForUpdate(escritorioId)
          .orElseThrow(() -> new ResourceNotFoundException("Escritório", escritorioId));
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
      Optional<ConviteCarteira> recente = conviteRepository
          .findByEscritorioIdAndEmailAndSituacao(escritorioId, normalizado,
              SituacaoConvite.PENDENTE)
          .filter(c -> c.getCriadoEm().isAfter(agora.minus(INTERVALO_MINIMO_REENVIO)));
      if (recente.isPresent()) {
        // Reenvio logo depois: mesmo convite, sem outro e-mail. Sem isso, apagar e recriar
        // escaparia do teto diario e encheria a caixa de entrada de uma pessoa.
        return recente.get();
      }
      conviteRepository.apagarPendente(escritorioId, normalizado);
      ConviteCarteira salvo = conviteRepository.save(ConviteCarteira.builder()
          .escritorioId(escritorioId)
          .convidadoPor(contador.getId())
          .email(normalizado)
          .situacao(SituacaoConvite.PENDENTE)
          .criadoEm(agora)
          .expiraEm(agora.plus(propriedades.validade()))
          .build());
      EscritorioDoConvite remetente =
          EscritorioDoConvite.de(escritorio, nomeParaEmail(escritorio.getNome()));
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          enviarEmSegundoPlano(normalizado, remetente, salvo.getExpiraEm());
        }
      });
      return salvo;
    });
    log.info("Convite da carteira registrado: convite={}, escritorio={}", convite.getId(),
        escritorioId);
    return enviado(convite);
  }

  /**
   * Convites pendentes do escritório do contador autenticado (qualquer membro).
   *
   * @return convites válidos, do mais recente ao mais antigo
   */
  public List<ConviteEnviadoResponse> listarEnviados() {
    UUID escritorioId = membroProvider.membro().getEscritorioId();
    return conviteRepository.findByEscritorioIdAndSituacaoAndExpiraEmAfterOrderByCriadoEmDesc(
            escritorioId, SituacaoConvite.PENDENTE, OffsetDateTime.now(clock)).stream()
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
    String email = emailDoProdutor(produtorProvider.get());
    if (email == null) {
      return List.of();
    }
    List<ConviteCarteira> convites = conviteRepository
        .findByEmailAndSituacaoAndExpiraEmAfterOrderByCriadoEmDesc(
            email, SituacaoConvite.PENDENTE, OffsetDateTime.now(clock));
    Map<UUID, Escritorio> escritorios = escritorioRepository
        .findAllById(convites.stream().map(ConviteCarteira::getEscritorioId).toList()).stream()
        .collect(Collectors.toMap(Escritorio::getId, Function.identity()));
    Map<UUID, Contador> convidantes = contadorRepository
        .findAllById(convites.stream().map(ConviteCarteira::getConvidadoPor)
            .filter(Objects::nonNull).toList()).stream()
        .collect(Collectors.toMap(Contador::getId, Function.identity()));
    return convites.stream()
        .map(c -> recebido(c, escritorios.get(c.getEscritorioId()),
            c.getConvidadoPor() == null ? null : convidantes.get(c.getConvidadoPor())))
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
    Produtor produtor = produtorProvider.get();
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
        carteiraService.ativarPorConvite(convite.getEscritorioId(), produtor.getId(),
            convite.getConvidadoPor());
      }
      return recebido(convite,
          escritorioRepository.findById(convite.getEscritorioId()).orElse(null),
          convite.getConvidadoPor() == null ? null
              : contadorRepository.findById(convite.getConvidadoPor()).orElse(null));
    });
  }

  /** E-mail da credencial do produtor (o que ele usa para entrar), já normalizado. */
  private String emailDoProdutor(Produtor produtor) {
    return credencialRepository.findByProdutorId(produtor.getId())
        .map(CredencialUsuario::getEmail)
        .orElse(null);
  }

  private void enviarEmSegundoPlano(String email, EscritorioDoConvite escritorio,
      OffsetDateTime expiraEm) {
    try {
      executor.execute(() -> {
        try {
          envio.enviarConvite(email, escritorio, propriedades.urlFrontend(), expiraEm);
        } catch (RuntimeException e) {
          // Sem o e-mail no log (LGPD); o convite ja existe e aparece para o produtor no login.
          log.warn("Falha ao enviar o e-mail do convite da carteira: {}",
              EnvioEmailFalhouException.paraLog(e));
        }
      });
    } catch (TaskRejectedException e) {
      log.warn("Fila de e-mail cheia: aviso de convite da carteira descartado.");
    }
  }

  /**
   * Nome do escritório para o corpo dos dois convites: sem caractere de controle, sem endereço e
   * no tamanho do texto do e-mail.
   *
   * <p>O nome é livre e o cadastro de contador é público: sem tirar o endereço, um atacante
   * mandaria de {@code nao-responda@agroconia.com.br} um convite com "acesse
   * golpe.com.br" no nome, e queimaria a reputação do domínio (#460). Sai qualquer
   * {@code esquema://...}, {@code www....}, e-mail e domínio ({@code rótulo.rótulo}, com o último
   * rótulo de duas letras ou mais). O NFKC antes faz o ponto e as letras de largura total valerem
   * como os comuns ({@code golpe．com}). Efeito aceito: nome como {@code Contab.Rural} perde o
   * trecho com ponto.
   */
  static String nomeParaEmail(String nome) {
    String limpo = nome == null ? ""
        : Normalizer.normalize(nome, Normalizer.Form.NFKC).replaceAll("\\p{Cntrl}", " ");
    limpo = ENDERECO_NO_NOME.matcher(limpo).replaceAll(" ").replaceAll("\\s+", " ").strip();
    return limpo.length() > NOME_MAXIMO_NO_EMAIL ? limpo.substring(0, NOME_MAXIMO_NO_EMAIL)
        : limpo;
  }

  private static ConviteEnviadoResponse enviado(ConviteCarteira convite) {
    return new ConviteEnviadoResponse(convite.getId(), convite.getEmail(), convite.getSituacao(),
        convite.getCriadoEm(), convite.getExpiraEm());
  }

  private static ConviteRecebidoResponse recebido(ConviteCarteira convite, Escritorio escritorio,
      Contador contador) {
    boolean verificado = escritorio != null && escritorio.cnpjVerificado();
    return new ConviteRecebidoResponse(
        convite.getId(),
        escritorio == null ? null : escritorio.getNome(),
        verificado,
        verificado ? escritorio.getCnpj() : null,
        contador == null ? null : contador.getNomeCompleto(),
        contador == null ? null : contador.getCrc(),
        convite.getSituacao(),
        convite.getCriadoEm(),
        convite.getExpiraEm());
  }
}
