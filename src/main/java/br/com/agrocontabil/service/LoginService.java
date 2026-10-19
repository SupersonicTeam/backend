package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.LoginRequest;
import br.com.agrocontabil.dto.LoginResponse;
import br.com.agrocontabil.dto.UsuarioLogadoResponse;
import br.com.agrocontabil.exception.CredenciaisInvalidasException;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.security.JwtService;
import br.com.agrocontabil.security.JwtService.TokenAcesso;
import br.com.agrocontabil.security.UsuarioAutenticado;
import br.com.agrocontabil.security.UsuarioAutenticadoProvider;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Login por e-mail e senha, com emissão do JWT de acesso (issue #206).
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
  private final JwtService jwtService;
  private final UsuarioAutenticadoProvider usuarioProvider;

  /** Senha conferida contra o hash fictício quando a enviada não pode chegar ao BCrypt. */
  private static final String SENHA_FICTICIA = "senha-ficticia-de-igualacao";

  /** Hash de uma senha aleatória, no custo atual: conferido quando o e-mail não tem conta. */
  private final String hashFicticio;

  public LoginService(
      CredencialUsuarioRepository credencialRepository,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      UsuarioAutenticadoProvider usuarioProvider) {
    this.credencialRepository = credencialRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.usuarioProvider = usuarioProvider;
    this.hashFicticio = passwordEncoder.encode(UUID.randomUUID().toString());
  }

  /**
   * Confere e-mail e senha e emite o token de acesso.
   *
   * @param request e-mail e senha
   * @return token de acesso, validade e papel
   * @throws CredenciaisInvalidasException se e-mail e senha não conferirem, por qualquer motivo
   */
  public LoginResponse entrar(LoginRequest request) {
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
      // Mesmo custo de CPU do caminho com conta: o tempo de resposta nao revela o e-mail.
      passwordEncoder.matches(senha, hashFicticio);
      throw new CredenciaisInvalidasException();
    }

    CredencialUsuario credencial = encontrada.get();
    boolean senhaConfere = passwordEncoder.matches(senha, credencial.getSenhaHash());
    if (!senhaConfere || !credencial.isAtivo()) {
      log.info("Login recusado: credencial={}", credencial.getId());
      throw new CredenciaisInvalidasException();
    }

    atualizarHashSeDefasado(credencial, senha);

    TokenAcesso token = jwtService.emitir(
        credencial.getId(), credencial.getPapel(), credencial.getVersaoSessao());
    log.info("Login: papel={}, usuarioId={}", credencial.getPapel(), credencial.usuarioId());
    return new LoginResponse(token.token(), LoginResponse.TIPO_BEARER, token.expiraEm(),
        credencial.getPapel(), credencial.usuarioId());
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
   * Regrava o hash quando o codificador pede (custo do BCrypt aumentado, algoritmo trocado).
   *
   * <p>É o único momento em que a senha em claro está disponível para gerar o hash novo. A gravação
   * é um {@code UPDATE} só da coluna do hash: um {@code save} da entidade inteira sobrescreveria uma
   * revogação de sessão feita entre a leitura e a gravação.
   */
  private void atualizarHashSeDefasado(CredencialUsuario credencial, String senha) {
    if (passwordEncoder.upgradeEncoding(credencial.getSenhaHash())) {
      credencialRepository.atualizarSenhaHash(credencial.getId(), passwordEncoder.encode(senha));
    }
  }
}
