package br.com.agrocontabil.service;

import br.com.agrocontabil.common.MascaraDadosPessoais;
import br.com.agrocontabil.config.RedefinicaoSenhaProperties;
import br.com.agrocontabil.exception.SenhaAtualIncorretaException;
import br.com.agrocontabil.exception.TokenRedefinicaoInvalidoException;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.model.RedefinicaoSenha;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.RedefinicaoSenhaRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.security.TokenOpaco;
import br.com.agrocontabil.security.UsuarioAutenticado;
import br.com.agrocontabil.security.UsuarioAutenticadoProvider;
import br.com.agrocontabil.service.auditoria.Auditado;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recuperação de senha por e-mail e troca de senha de quem está logado (issue #209).
 *
 * <p><strong>"Esqueci a senha" não revela nada.</strong> A resposta é sempre a mesma (202), e o
 * trabalho — procurar a conta, gerar o token, enviar o e-mail — roda em segundo plano, num pool
 * próprio ({@code agro-email-}). Fazer isso na requisição deixaria o <em>tempo</em> de resposta
 * dizer quem tem conta (gravar e enviar custa; não achar, não). Fila cheia descarta o pedido com
 * log: nunca segura a requisição.
 *
 * <p><strong>Link de uso único e curto.</strong> Token de 256 bits ({@link TokenOpaco}), só o
 * SHA-256 no banco, 30 minutos; um pedido novo apaga os anteriores (só o último link vale). O
 * token vai no fragmento da URL ({@code #token=}): o navegador não o manda ao servidor do frontend
 * nem o põe no {@code Referer}.
 *
 * <p><strong>Trocar a senha encerra todas as sessões</strong> ({@code SessaoService.revogarTodas}):
 * redefinição e troca derrubam na hora os access tokens e os refresh já emitidos — inclusive os do
 * invasor, que é justamente quem fez o titular trocar a senha. Também apagam os links pendentes e
 * zeram o bloqueio por tentativas.
 *
 * <p><strong>Ordem: validar, BCrypt, gravar.</strong> O token é conferido sem lock, a política e o
 * hash rodam fora de transação (o BCrypt não segura conexão), e a gravação reconfere o token sob
 * lock antes de marcá-lo usado — dois envios simultâneos do mesmo link nunca passam ambos, e uma
 * senha fraca não queima o link.
 */
@Service
@ConditionalOnWebApplication
public class SenhaService {

  private static final Logger log = LoggerFactory.getLogger(SenhaService.class);

  /** Campo da senha nova nos requests, para o {@code field} dos erros da política. */
  static final String CAMPO_NOVA_SENHA = "novaSenha";

  private static final String SENHA_FICTICIA = "senha-ficticia-de-igualacao";

  /** Intervalo mínimo entre dois links para a mesma conta. */
  static final Duration INTERVALO_MINIMO_ENTRE_LINKS = Duration.ofMinutes(1);

  private final CredencialUsuarioRepository credencialRepository;
  private final RedefinicaoSenhaRepository redefinicaoRepository;
  private final PasswordEncoder passwordEncoder;
  private final PoliticaSenha politicaSenha;
  private final SessaoService sessaoService;
  private final BloqueioContaService bloqueioConta;
  private final EnvioEmail envioEmail;
  private final RedefinicaoSenhaProperties propriedades;
  private final UsuarioAutenticadoProvider usuarioProvider;
  private final Clock clock;
  private final TransactionTemplate transacao;
  private final ThreadPoolTaskExecutor executor;
  private final String hashFicticio;

  public SenhaService(
      CredencialUsuarioRepository credencialRepository,
      RedefinicaoSenhaRepository redefinicaoRepository,
      PasswordEncoder passwordEncoder,
      PoliticaSenha politicaSenha,
      SessaoService sessaoService,
      BloqueioContaService bloqueioConta,
      EnvioEmail envioEmail,
      RedefinicaoSenhaProperties propriedades,
      UsuarioAutenticadoProvider usuarioProvider,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.credencialRepository = credencialRepository;
    this.redefinicaoRepository = redefinicaoRepository;
    this.passwordEncoder = passwordEncoder;
    this.politicaSenha = politicaSenha;
    this.sessaoService = sessaoService;
    this.bloqueioConta = bloqueioConta;
    this.envioEmail = envioEmail;
    this.propriedades = propriedades;
    this.usuarioProvider = usuarioProvider;
    this.clock = clock;
    this.transacao = new TransactionTemplate(transactionManager);
    this.hashFicticio = passwordEncoder.encode(UUID.randomUUID().toString());
    // Pool proprio, fora do contexto: um Executor como bean desligaria o applicationTaskExecutor do
    // Boot (usado pelo SSE da Ceres) — mesmo cuidado do AssistenteService.
    this.executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("agro-email-");
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(100);
    executor.initialize();
  }

  /** Encerra o pool do e-mail no desligamento. */
  @PreDestroy
  void encerrar() {
    int pendentes = executor.getThreadPoolExecutor().getQueue().size();
    if (pendentes > 0) {
      log.warn("Desligando com {} pedido(s) de redefinicao de senha na fila: nao serao enviados; "
          + "o titular precisa pedir de novo.", pendentes);
    }
    executor.shutdown();
  }

  /**
   * Pede o link de redefinição. Responde na hora, exista ou não a conta; o trabalho segue em
   * segundo plano.
   *
   * @param email e-mail informado
   */
  public void solicitarRedefinicao(String email) {
    String normalizado = CadastroService.normalizarEmail(email);
    try {
      executor.execute(() -> processarPedido(normalizado));
    } catch (TaskRejectedException e) {
      log.warn("Fila de e-mail cheia: pedido de redefinicao descartado.");
    }
  }

  /**
   * Gera o token e envia o link, se o e-mail tiver conta ativa. Roda no pool de e-mail; visível
   * no pacote para os testes chamarem de forma síncrona.
   *
   * @param email e-mail normalizado
   */
  void processarPedido(String email) {
    try {
      Optional<CredencialUsuario> credencial =
          credencialRepository.findByEmail(email).filter(CredencialUsuario::isAtivo);
      if (credencial.isEmpty()) {
        return;
      }
      OffsetDateTime agora = OffsetDateTime.now(clock);
      UUID credencialId = credencial.get().getId();
      if (redefinicaoRepository.existsByCredencialIdAndCriadoEmAfter(
          credencialId, agora.minus(INTERVALO_MINIMO_ENTRE_LINKS))) {
        // Um pedido novo apaga o link anterior: sem intervalo, qualquer um invalidaria sem parar
        // o link que a vitima acabou de receber (e encheria a caixa de entrada dela).
        log.info("Pedido de redefinicao ignorado (intervalo minimo): credencial={}",
            MascaraDadosPessoais.referencia(credencialId));
        return;
      }
      String token = TokenOpaco.gerar();
      transacao.executeWithoutResult(status -> {
        redefinicaoRepository.apagarDaCredencial(credencialId);
        redefinicaoRepository.save(RedefinicaoSenha.builder()
            .credencialId(credencialId)
            .tokenHash(TokenOpaco.hash(token))
            .criadoEm(agora)
            .expiraEm(agora.plus(propriedades.validade()))
            .build());
      });
      envioEmail.enviarRedefinicaoSenha(
          email, propriedades.urlFrontend() + "#token=" + token, propriedades.validade());
      log.info("Link de redefinicao de senha emitido: credencial={}",
          MascaraDadosPessoais.referencia(credencialId));
    } catch (RuntimeException e) {
      // Sem o e-mail no log (LGPD) e sem propagar: ninguem espera por este resultado.
      log.error("Falha ao processar pedido de redefinicao de senha.", e);
    }
  }

  /**
   * Redefine a senha com o token do link.
   *
   * @param token     token do link
   * @param novaSenha senha nova
   * @throws TokenRedefinicaoInvalidoException se o link não valer
   * @throws br.com.agrocontabil.exception.SenhaFracaException se a senha nova não atender a
   *     política (o link continua valendo)
   */
  public void redefinir(String token, String novaSenha) {
    String tokenHash = TokenOpaco.hash(token);
    RedefinicaoSenha pedido = redefinicaoRepository.findByTokenHash(tokenHash)
        .filter(this::vigente)
        .orElseThrow(TokenRedefinicaoInvalidoException::new);
    CredencialUsuario credencial = credencialRepository.findById(pedido.getCredencialId())
        .filter(CredencialUsuario::isAtivo)
        .orElseThrow(TokenRedefinicaoInvalidoException::new);

    String senha = PoliticaSenha.normalizar(novaSenha);
    politicaSenha.validar(senha, credencial.getEmail(), credencial.getCpfCnpj(), CAMPO_NOVA_SENHA);
    String hash = passwordEncoder.encode(senha);

    transacao.executeWithoutResult(status -> {
      RedefinicaoSenha travado = redefinicaoRepository.findByTokenHashForUpdate(tokenHash)
          .filter(this::vigente)
          .orElseThrow(TokenRedefinicaoInvalidoException::new);
      travado.setUsadoEm(OffsetDateTime.now(clock));
      gravarSenhaNova(credencial.getId(), hash);
    });
    log.info("Senha redefinida pelo link: credencial={}",
        MascaraDadosPessoais.referencia(credencial.getId()));
  }

  /**
   * Troca a senha de quem está autenticado, conferindo a atual.
   *
   * @param senhaAtual senha em uso
   * @param novaSenha  senha nova
   * @throws SenhaAtualIncorretaException se a senha atual não conferir (ou a conta estiver
   *     bloqueada por tentativas)
   */
  @Auditado(acao = AcaoAuditada.SENHA_ALTERADA, recurso = RecursoAuditado.CONTA)
  public void alterar(String senhaAtual, String novaSenha) {
    CredencialUsuario credencial = confirmarSenhaAtual(senhaAtual);

    String senha = PoliticaSenha.normalizar(novaSenha);
    politicaSenha.validar(senha, credencial.getEmail(), credencial.getCpfCnpj(), CAMPO_NOVA_SENHA);
    String hash = passwordEncoder.encode(senha);
    transacao.executeWithoutResult(status -> gravarSenhaNova(credencial.getId(), hash));
    log.info("Senha alterada pelo titular: credencial={}",
        MascaraDadosPessoais.referencia(credencial.getId()));
  }

  /**
   * Confirma a senha atual de quem está autenticado — a prova adicional das operações sensíveis
   * (troca de senha; exclusão dos dados pessoais, #148). Passa pela mesma reserva antitentativa do
   * login: com um access token roubado, ninguém adivinha a senha por aqui.
   *
   * @param senhaAtual senha informada
   * @return a credencial de quem está autenticado
   * @throws SenhaAtualIncorretaException se a senha não conferir, a conta estiver travada ou a
   *     identidade não tiver credencial (mock de dev)
   */
  public CredencialUsuario confirmarSenhaAtual(String senhaAtual) {
    UsuarioAutenticado usuario = usuarioProvider.get();
    CredencialUsuario credencial = (usuario.papel() == PapelUsuario.CONTADOR
        ? credencialRepository.findByContadorId(usuario.id())
        : credencialRepository.findByProdutorId(usuario.id()))
        .orElseThrow(SenhaAtualIncorretaException::new);

    String atual = PoliticaSenha.normalizar(senhaAtual);
    if (atual.getBytes(StandardCharsets.UTF_8).length > PoliticaSenha.MAXIMO_BYTES
        || !bloqueioConta.reservarTentativa(credencial.getId())) {
      // Mesmo custo do caminho que confere: o tempo nao diz se a conta esta travada.
      passwordEncoder.matches(SENHA_FICTICIA, hashFicticio);
      throw new SenhaAtualIncorretaException();
    }
    if (!passwordEncoder.matches(atual, credencial.getSenhaHash())) {
      throw new SenhaAtualIncorretaException();
    }
    // A senha atual conferiu: a tentativa foi legitima e a reserva volta ja, antes da politica.
    // Sem isso, cinco senhas novas recusadas pela politica travariam o proprio titular.
    bloqueioConta.liberar(credencial.getId());
    return credencial;
  }

  /**
   * Grava o hash novo e faz tudo o que a troca de senha implica, na mesma transação: encerra as
   * sessões, apaga os links pendentes e zera o bloqueio por tentativas.
   */
  private void gravarSenhaNova(UUID credencialId, String hash) {
    credencialRepository.atualizarSenhaHash(credencialId, hash);
    redefinicaoRepository.apagarDaCredencial(credencialId);
    sessaoService.revogarTodas(credencialId);
    bloqueioConta.liberar(credencialId);
  }

  private boolean vigente(RedefinicaoSenha pedido) {
    return pedido.getUsadoEm() == null
        && pedido.getExpiraEm().isAfter(OffsetDateTime.now(clock));
  }
}
