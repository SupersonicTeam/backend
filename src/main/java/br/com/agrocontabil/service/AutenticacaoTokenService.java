package br.com.agrocontabil.service;

import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.JwtService;
import br.com.agrocontabil.security.JwtService.TokenConferido;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.security.UsuarioAutenticado;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/**
 * Transforma um JWT de acesso na identidade da requisição (issue #206).
 *
 * <p>O token prova que foi emitido aqui e não expirou; o banco responde se ele <em>ainda</em> vale.
 * A cada requisição a credencial é relida, e o token é recusado se ela:
 *
 * <ul>
 *   <li>não existe mais (identidade apagada — o CASCADE leva a credencial junto);
 *   <li>foi desativada;
 *   <li>mudou de versão de sessão (troca ou redefinição de senha revogam todos os tokens);
 *   <li>não tem mais o papel que o token afirma.
 * </ul>
 *
 * <p>É uma consulta a mais por requisição — o preço de revogar na hora em vez de esperar o
 * {@code exp}, que é o que importa quando a senha vazou.
 */
@Service
@ConditionalOnWebApplication
public class AutenticacaoTokenService {

  private final JwtService jwtService;
  private final CredencialUsuarioRepository credencialRepository;
  private final ProdutorRepository produtorRepository;
  private final ContadorRepository contadorRepository;

  public AutenticacaoTokenService(
      JwtService jwtService,
      CredencialUsuarioRepository credencialRepository,
      ProdutorRepository produtorRepository,
      ContadorRepository contadorRepository) {
    this.jwtService = jwtService;
    this.credencialRepository = credencialRepository;
    this.produtorRepository = produtorRepository;
    this.contadorRepository = contadorRepository;
  }

  /**
   * Resolve a identidade de um token de acesso.
   *
   * @param token JWT compacto, sem o prefixo {@code Bearer}
   * @return a identidade, ou vazio se o token não vale (por qualquer motivo)
   */
  public Optional<UsuarioAutenticado> autenticar(String token) {
    return jwtService.conferir(token).flatMap(this::resolver);
  }

  private Optional<UsuarioAutenticado> resolver(TokenConferido token) {
    return credencialRepository.findById(token.credencialId())
        .filter(CredencialUsuario::isAtivo)
        .filter(c -> c.getPapel() == token.papel())
        .filter(c -> c.getVersaoSessao() == token.versaoSessao())
        .flatMap(this::identidade);
  }

  private Optional<UsuarioAutenticado> identidade(CredencialUsuario credencial) {
    if (credencial.getPapel() == PapelUsuario.CONTADOR) {
      return contadorRepository.findById(credencial.getContadorId())
          .map(UsuarioAutenticado::deContador);
    }
    return produtorRepository.findById(credencial.getProdutorId())
        .map(UsuarioAutenticado::deProdutor);
  }
}
