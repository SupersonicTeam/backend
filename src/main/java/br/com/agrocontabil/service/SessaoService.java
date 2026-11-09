package br.com.agrocontabil.service;

import br.com.agrocontabil.common.MascaraDadosPessoais;
import br.com.agrocontabil.config.RefreshProperties;
import br.com.agrocontabil.dto.LoginResponse;
import br.com.agrocontabil.exception.SessaoInvalidaException;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.SessaoRefresh;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.SessaoRefreshRepository;
import br.com.agrocontabil.security.JwtService;
import br.com.agrocontabil.security.JwtService.TokenAcesso;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.security.TokenOpaco;
import br.com.agrocontabil.security.UsuarioAutenticado;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sessão de login: o par access token (JWT, 15 min) + refresh token (opaco, rotativo) — issue #207.
 *
 * <p><strong>Refresh opaco.</strong> 256 bits aleatórios ({@link TokenOpaco}). No banco só o
 * SHA-256: quem ler a tabela não renova sessão nenhuma. Não é JWT de propósito — um refresh
 * precisa ser revogável um a um, e a revogação já exige consultar o banco.
 *
 * <p><strong>Rotação com detecção de reuso</strong> (OAuth 2.0 Security BCP, §4.14). Cada
 * renovação marca o token como usado e emite o próximo da mesma família. Um token já usado que
 * volta só pode ser cópia: ou o ladrão está usando o que roubou depois que a vítima renovou, ou a
 * vítima está usando o que o ladrão já trocou. Não há como saber qual dos dois — a família inteira
 * é revogada e os dois voltam ao login. O lock pessimista na leitura impede que duas renovações
 * simultâneas do mesmo token passem ambas.
 *
 * <p><strong>Vida máxima.</strong> A renovação estende a validade de cada token, mas nunca além de
 * {@code vida-maxima} desde o login: um token roubado não vira acesso eterno.
 *
 * <p><strong>Desfecho fora da transação de leitura.</strong> Um refresh inválido precisa
 * <em>gravar</em> antes de recusar (revogar a família no reuso). Por isso a transação devolve o
 * desfecho e a exceção é lançada depois do commit — lançada lá dentro, ela desfaria a revogação.
 */
@Service
@ConditionalOnWebApplication
public class SessaoService {

  private static final Logger log = LoggerFactory.getLogger(SessaoService.class);


  private final SessaoRefreshRepository sessaoRepository;
  private final CredencialUsuarioRepository credencialRepository;
  private final JwtService jwtService;
  private final RefreshProperties propriedades;
  private final EnderecoEscritorioService enderecoService;
  private final Clock clock;
  private final TransactionTemplate transacao;

  public SessaoService(
      SessaoRefreshRepository sessaoRepository,
      CredencialUsuarioRepository credencialRepository,
      JwtService jwtService,
      RefreshProperties propriedades,
      EnderecoEscritorioService enderecoService,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.sessaoRepository = sessaoRepository;
    this.credencialRepository = credencialRepository;
    this.jwtService = jwtService;
    this.propriedades = propriedades;
    this.enderecoService = enderecoService;
    this.clock = clock;
    this.transacao = new TransactionTemplate(transactionManager);
  }

  /**
   * Abre uma família nova para uma credencial que acabou de provar a senha.
   *
   * @param credencial credencial autenticada
   * @return access token e refresh token
   */
  public SessaoEmitida abrir(CredencialUsuario credencial) {
    OffsetDateTime agora = OffsetDateTime.now(clock);
    Instant expiraEm = expiracao(agora, agora);
    String refresh = transacao.execute(status ->
        gravarToken(credencial.getId(), UUID.randomUUID(), credencial.getVersaoSessao(), agora,
            agora, expiraEm));
    return emitir(credencial, refresh, expiraEm);
  }

  /**
   * Troca um refresh token válido por um par novo; o usado deixa de valer.
   *
   * @param refreshToken token do cookie
   * @return access token e refresh token novos
   * @throws SessaoInvalidaException se o token não renovar, por qualquer motivo
   */
  public SessaoEmitida renovar(String refreshToken) {
    return renovar(refreshToken, Optional.empty());
  }

  /**
   * Troca um refresh token válido por um par novo, considerando o host (#464). No endereço de um
   * escritório, a credencial precisa continuar membro dele: quem saiu da equipe, ou o escritório
   * que perdeu o endereço, perde a família inteira (401, cookie apagado). No host principal, nada
   * muda.
   *
   * @param refreshToken token do cookie
   * @param rotuloDoHost rótulo de escritório do host; vazio no host principal
   * @return access token e refresh token novos
   * @throws SessaoInvalidaException se o token não renovar, por qualquer motivo
   */
  public SessaoEmitida renovar(String refreshToken, Optional<String> rotuloDoHost) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new SessaoInvalidaException();
    }
    String hash = TokenOpaco.hash(refreshToken);
    Renovacao renovacao = transacao.execute(status -> renovarSobLock(hash, rotuloDoHost));
    if (renovacao == null || renovacao.credencial() == null) {
      throw new SessaoInvalidaException();
    }
    return emitir(renovacao.credencial(), renovacao.refreshToken(), renovacao.expiraEm());
  }

  /**
   * Encerra a sessão deste refresh token (logout neste dispositivo). Idempotente: token
   * desconhecido, vencido ou já revogado não é erro — o resultado desejado já vale.
   *
   * @param refreshToken token do cookie; pode ser nulo
   */
  public void encerrar(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      return;
    }
    String hash = TokenOpaco.hash(refreshToken);
    // Mesmo lock da renovacao: um refresh deste token em andamento termina (e grava o sucessor)
    // antes do UPDATE da familia, que entao o alcanca. Sem o lock, o sucessor inserido por uma
    // renovacao simultanea escaparia da revogacao — e quem tivesse uma copia do token seguiria
    // logado depois do logout da vitima.
    transacao.executeWithoutResult(status -> sessaoRepository.findByTokenHashForUpdate(hash)
        .ifPresent(s -> sessaoRepository.revogarFamilia(s.getFamiliaId(), agora())));
  }

  /**
   * Encerra todas as sessões de quem está autenticado ("sair de todos os dispositivos"): revoga
   * todos os refresh e invalida todos os access tokens já emitidos, inclusive o desta requisição.
   *
   * <p>Sem credencial (identidade do mock de dev), não há sessão a encerrar.
   *
   * @param usuario identidade autenticada
   */
  public void encerrarTodas(UsuarioAutenticado usuario) {
    Optional<CredencialUsuario> credencial = usuario.papel() == PapelUsuario.CONTADOR
        ? credencialRepository.findByContadorId(usuario.id())
        : credencialRepository.findByProdutorId(usuario.id());
    credencial.ifPresent(c -> revogarTodas(c.getId()));
  }

  /**
   * Revoga todas as sessões de uma credencial e avança a versão de sessão dela. Ponto único da
   * revogação em massa — troca e redefinição de senha (issue #209) passam por aqui.
   *
   * @param credencialId credencial
   */
  public void revogarTodas(UUID credencialId) {
    transacao.executeWithoutResult(status -> {
      credencialRepository.incrementarVersaoSessao(credencialId);
      int revogadas = sessaoRepository.revogarDaCredencial(credencialId, agora());
      log.info("Sessoes encerradas: credencial={}, refresh revogados={}",
          MascaraDadosPessoais.referencia(credencialId), revogadas);
    });
  }

  private Renovacao renovarSobLock(String hash, Optional<String> rotuloDoHost) {
    Optional<SessaoRefresh> encontrada = sessaoRepository.findByTokenHashForUpdate(hash);
    if (encontrada.isEmpty()) {
      return Renovacao.INVALIDA;
    }
    SessaoRefresh sessao = encontrada.get();
    OffsetDateTime agora = agora();

    if (sessao.getRevogadoEm() != null) {
      return Renovacao.INVALIDA;
    }
    if (sessao.getUsadoEm() != null) {
      // Reuso: alguem tem uma copia. Encerra a familia e grava antes de recusar.
      int revogadas = sessaoRepository.revogarFamilia(sessao.getFamiliaId(), agora);
      log.warn("Reuso de refresh token detectado: familia={} revogada ({} token(s)), "
          + "credencial={}", sessao.getFamiliaId(), revogadas,
          MascaraDadosPessoais.referencia(sessao.getCredencialId()));
      return Renovacao.INVALIDA;
    }
    if (!sessao.getExpiraEm().isAfter(agora)) {
      return Renovacao.INVALIDA;
    }

    Optional<CredencialUsuario> credencial = credencialRepository.findById(sessao.getCredencialId())
        .filter(CredencialUsuario::isAtivo)
        .filter(c -> c.getVersaoSessao() == sessao.getVersaoSessao());
    if (credencial.isEmpty()) {
      // Senha trocada ou conta desativada depois do login: a familia morre junto.
      sessaoRepository.revogarFamilia(sessao.getFamiliaId(), agora);
      return Renovacao.INVALIDA;
    }

    if (rotuloDoHost.isPresent()
        && !enderecoService.membroDoEndereco(credencial.get(), rotuloDoHost.get())) {
      // Endereco de escritorio (#464): fora da equipe, a familia morre junto.
      sessaoRepository.revogarFamilia(sessao.getFamiliaId(), agora);
      log.info("Refresh recusado (fora do escritorio do endereco): credencial={}",
          MascaraDadosPessoais.referencia(sessao.getCredencialId()));
      return Renovacao.INVALIDA;
    }

    Instant expiraEm = expiracao(sessao.getFamiliaIniciadaEm(), agora);
    if (!expiraEm.isAfter(agora.toInstant())) {
      // Vida maxima da sessao esgotada: so com a senha de novo.
      sessaoRepository.revogarFamilia(sessao.getFamiliaId(), agora);
      return Renovacao.INVALIDA;
    }

    sessao.setUsadoEm(agora);
    String novo = gravarToken(sessao.getCredencialId(), sessao.getFamiliaId(),
        sessao.getVersaoSessao(), sessao.getFamiliaIniciadaEm(), agora, expiraEm);
    return new Renovacao(credencial.get(), novo, expiraEm);
  }

  private String gravarToken(UUID credencialId, UUID familiaId, int versaoSessao,
      OffsetDateTime familiaIniciadaEm, OffsetDateTime agora, Instant expiraEm) {
    String token = TokenOpaco.gerar();
    sessaoRepository.save(SessaoRefresh.builder()
        .credencialId(credencialId)
        .familiaId(familiaId)
        .tokenHash(TokenOpaco.hash(token))
        .versaoSessao(versaoSessao)
        .familiaIniciadaEm(familiaIniciadaEm)
        .criadoEm(agora)
        .expiraEm(OffsetDateTime.ofInstant(expiraEm, clock.getZone()))
        .build());
    return token;
  }

  /** Validade de um token novo: a menor entre a validade e o fim da vida máxima da família. */
  private Instant expiracao(OffsetDateTime familiaIniciadaEm, OffsetDateTime agora) {
    Instant porValidade = agora.toInstant().plus(propriedades.validade());
    Instant porVidaMaxima = familiaIniciadaEm.toInstant().plus(propriedades.vidaMaxima());
    return porValidade.isBefore(porVidaMaxima) ? porValidade : porVidaMaxima;
  }

  private SessaoEmitida emitir(CredencialUsuario credencial, String refresh, Instant refreshExpira) {
    TokenAcesso acesso = jwtService.emitir(
        credencial.getId(), credencial.getPapel(), credencial.getVersaoSessao());
    LoginResponse resposta = new LoginResponse(acesso.token(), LoginResponse.TIPO_BEARER,
        acesso.expiraEm(), credencial.getPapel(), credencial.usuarioId());
    return new SessaoEmitida(resposta, refresh, refreshExpira);
  }

  private OffsetDateTime agora() {
    return OffsetDateTime.now(clock);
  }


  /** Desfecho da renovação dentro da transação; {@code credencial} nula = recusada. */
  private record Renovacao(CredencialUsuario credencial, String refreshToken, Instant expiraEm) {
    static final Renovacao INVALIDA = new Renovacao(null, null, null);
  }

  /**
   * Par emitido no login ou na renovação.
   *
   * @param acesso          corpo da resposta (access token)
   * @param refreshToken    refresh token, que vai só no cookie
   * @param refreshExpiraEm validade do refresh (o {@code Max-Age} do cookie)
   */
  public record SessaoEmitida(LoginResponse acesso, String refreshToken, Instant refreshExpiraEm) {

    /** Sem os tokens. */
    @Override
    public String toString() {
      return "SessaoEmitida[" + acesso + ", refreshExpiraEm=" + refreshExpiraEm + "]";
    }
  }
}
