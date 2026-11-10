package br.com.agrocontabil.service;

import br.com.agrocontabil.common.FormatoSlug;
import br.com.agrocontabil.common.MascaraDadosPessoais;
import br.com.agrocontabil.dto.LoginRequest;
import br.com.agrocontabil.dto.UsuarioLogadoResponse;
import br.com.agrocontabil.exception.CredenciaisInvalidasException;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.security.UsuarioAutenticado;
import br.com.agrocontabil.security.UsuarioAutenticadoProvider;
import br.com.agrocontabil.service.SessaoService.SessaoEmitida;
import br.com.agrocontabil.service.auditoria.Auditado;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Login por e-mail e senha (issue #206). Com a senha conferida, abre a sessão pelo
 * {@link SessaoService}: JWT de acesso no corpo e refresh token no cookie (issue #207).
 *
 * <p><strong>Uma resposta para todo fracasso.</strong> E-mail sem conta, senha errada e credencial
 * desativada lançam a mesma {@link CredenciaisInvalidasException}. E o <em>tempo</em> também é o
 * mesmo: sem conta, a senha é conferida contra um hash fictício de mesmo custo. Sem isso, o
 * e-mail inexistente responderia em ~1 ms e o existente em ~250 ms (o custo do BCrypt), e o
 * cronômetro diria quem tem conta.
 *
 * <p><strong>Sem transação.</strong> O BCrypt roda fora de qualquer transação, pelo mesmo motivo do
 * {@link CadastroService}: não segurar conexão do pool durante o hash. Cada acesso ao banco é uma
 * chamada curta de repositório.
 *
 * <p>Só existe com servidor web: os comandos sem web (ingestão, avaliação) não emitem token.
 */
@Service
@ConditionalOnWebApplication
public class LoginService {

  private static final Logger log = LoggerFactory.getLogger(LoginService.class);

  private final CredencialUsuarioRepository credencialRepository;
  private final PasswordEncoder passwordEncoder;
  private final SessaoService sessaoService;
  private final BloqueioContaService bloqueioConta;
  private final UsuarioAutenticadoProvider usuarioProvider;
  private final EnderecoEscritorioService enderecoService;

  /** Senha conferida contra o hash fictício quando a enviada não pode chegar ao BCrypt. */
  private static final String SENHA_FICTICIA = "senha-ficticia-de-igualacao";

  /** Hash de uma senha aleatória, no custo atual: conferido quando o e-mail não tem conta. */
  private final String hashFicticio;

  public LoginService(
      CredencialUsuarioRepository credencialRepository,
      PasswordEncoder passwordEncoder,
      SessaoService sessaoService,
      BloqueioContaService bloqueioConta,
      UsuarioAutenticadoProvider usuarioProvider,
      EnderecoEscritorioService enderecoService) {
    this.credencialRepository = credencialRepository;
    this.passwordEncoder = passwordEncoder;
    this.sessaoService = sessaoService;
    this.bloqueioConta = bloqueioConta;
    this.usuarioProvider = usuarioProvider;
    this.enderecoService = enderecoService;
    this.hashFicticio = passwordEncoder.encode(UUID.randomUUID().toString());
  }

  /**
   * Confere e-mail e senha e emite o token de acesso, pelo host principal.
   *
   * @param request e-mail e senha
   * @return access token (corpo da resposta) e refresh token (cookie)
   * @throws CredenciaisInvalidasException se e-mail e senha não conferirem, por qualquer motivo
   */
  public SessaoEmitida entrar(LoginRequest request) {
    return entrar(request, Optional.empty());
  }

  /**
   * Confere e-mail e senha e emite o token de acesso (issue #206), considerando o host (#464).
   *
   * <p><strong>Modo escritório</strong> quando o host é de escritório ou o corpo traz
   * {@code escritorio}. A ordem de sempre não muda — reserva da tentativa e BCrypt (#208) — e a
   * senha errada segue contando. Com a senha <em>certa</em>, a sessão só sai para o contador
   * membro do escritório daquele endereço; fora disso, o mesmo 401 de qualquer credencial errada,
   * com as falhas zeradas (quem acertou a senha não é atacante, e travar o titular seria pior). O
   * BCrypt já rodou, então o tempo também é o mesmo.
   *
   * @param request          e-mail, senha e, opcionalmente, o slug do escritório
   * @param rotuloDoHost     rótulo de escritório do host ({@code HostDaRequisicao}); vazio no
   *                         host principal
   * @return access token (corpo da resposta) e refresh token (cookie)
   * @throws CredenciaisInvalidasException se e-mail e senha não conferirem, por qualquer motivo
   */
  public SessaoEmitida entrar(LoginRequest request, Optional<String> rotuloDoHost) {
    String email = CadastroService.normalizarEmail(request.email());
    String senha = PoliticaSenha.normalizar(request.senha());

    if (senha.getBytes(StandardCharsets.UTF_8).length > PoliticaSenha.MAXIMO_BYTES) {
      // Nenhuma senha cadastrada passa de 72 bytes (a politica barra), e o BCrypt recusa conferir
      // acima disso com excecao (500). Mesmo 401 e mesmo custo dos outros caminhos.
      passwordEncoder.matches(SENHA_FICTICIA, hashFicticio);
      throw new CredenciaisInvalidasException();
    }

    Optional<CredencialUsuario> encontrada = credencialRepository.findByEmail(email);
    if (encontrada.isEmpty()) {
      // Mesmo custo de banco e de CPU do caminho com conta: o tempo nao revela o e-mail.
      bloqueioConta.reservarFicticia();
      passwordEncoder.matches(senha, hashFicticio);
      throw new CredenciaisInvalidasException();
    }

    CredencialUsuario credencial = encontrada.get();
    // A tentativa e contada ANTES de conferir a senha (#208): requisicoes paralelas consomem a
    // cota na fila do lock, e a sexta em diante ja encontra a conta travada.
    if (!bloqueioConta.reservarTentativa(credencial.getId())) {
      // Conta travada: a senha nem e conferida — a adivinhacao para. Mesmo custo e mesma
      // resposta dos outros fracassos: um 423 diria que este e-mail tem conta.
      passwordEncoder.matches(senha, hashFicticio);
      log.info("Login recusado (conta bloqueada): credencial={}",
          MascaraDadosPessoais.referencia(credencial.getId()));
      throw new CredenciaisInvalidasException();
    }

    boolean senhaConfere = passwordEncoder.matches(senha, credencial.getSenhaHash());
    if (!senhaConfere) {
      log.info("Login recusado: credencial={}",
          MascaraDadosPessoais.referencia(credencial.getId()));
      throw new CredenciaisInvalidasException();
    }
    if (!credencial.isAtivo()) {
      log.info("Login recusado (conta desativada): credencial={}",
          MascaraDadosPessoais.referencia(credencial.getId()));
      throw new CredenciaisInvalidasException();
    }

    if (modoEscritorio(request, rotuloDoHost)
        && !membroDoEnderecoDoHost(credencial, request, rotuloDoHost)) {
      bloqueioConta.liberar(credencial.getId());
      log.info("Login recusado (fora do escritorio do endereco): credencial={}",
          MascaraDadosPessoais.referencia(credencial.getId()));
      throw new CredenciaisInvalidasException();
    }

    bloqueioConta.liberar(credencial.getId());
    atualizarHashSeDefasado(credencial, senha);

    log.info("Login: papel={}, usuarioId={}", credencial.getPapel(), credencial.usuarioId());
    return sessaoService.abrir(credencial);
  }

  /** O host é de escritório ou o corpo traz o escritório (em branco conta como ausente). */
  private static boolean modoEscritorio(LoginRequest request, Optional<String> rotuloDoHost) {
    return rotuloDoHost.isPresent()
        || (request.escritorio() != null && !request.escritorio().isBlank());
  }

  /**
   * O host é de escritório, o corpo (se veio) nomeia o mesmo e a credencial é de um membro dele.
   * Pelo {@code www}, o corpo com escritório não basta: o host decide.
   */
  private boolean membroDoEnderecoDoHost(CredencialUsuario credencial, LoginRequest request,
      Optional<String> rotuloDoHost) {
    if (rotuloDoHost.isEmpty()) {
      return false;
    }
    String rotulo = rotuloDoHost.get();
    String doCorpo = request.escritorio();
    if (doCorpo != null && !doCorpo.isBlank()
        && !rotulo.equals(FormatoSlug.normalizar(doCorpo))) {
      return false;
    }
    return enderecoService.membroDoEndereco(credencial, rotulo);
  }

  /**
   * Identidade da requisição atual.
   *
   * @return id, papel e nome de quem está autenticado
   */
  public UsuarioLogadoResponse usuarioLogado() {
    UsuarioAutenticado usuario = usuarioProvider.get();
    return new UsuarioLogadoResponse(usuario.id(), usuario.papel(), usuario.nome());
  }

  /**
   * Encerra todas as sessões de quem está autenticado (issue #207).
   */
  @Auditado(acao = AcaoAuditada.SESSOES_ENCERRADAS, recurso = RecursoAuditado.CONTA)
  public void sairDeTodos() {
    sessaoService.encerrarTodas(usuarioProvider.get());
  }

  /**
   * Regrava o hash quando o codificador pede (custo do BCrypt aumentado, algoritmo trocado).
   *
   * <p>É o único momento em que a senha em claro está disponível para gerar o hash novo. A gravação
   * é um {@code UPDATE} só da coluna do hash: um {@code save} da entidade inteira sobrescreveria uma
   * revogação de sessão feita entre a leitura e a gravação.
   */
  private void atualizarHashSeDefasado(CredencialUsuario credencial, String senha) {
    if (passwordEncoder.upgradeEncoding(credencial.getSenhaHash())) {
      // Condicional ao hash lido: uma redefinicao de senha no meio nao e desfeita (#209).
      credencialRepository.atualizarSenhaHashSeIgual(
          credencial.getId(), credencial.getSenhaHash(), passwordEncoder.encode(senha));
    }
  }
}
