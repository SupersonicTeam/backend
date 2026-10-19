package br.com.agrocontabil.security;

import br.com.agrocontabil.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Emite e confere o JWT de acesso (issue #206).
 *
 * <p><strong>Formato.</strong> HS256 com o segredo de {@code JWT_SECRET}. Claims: {@code sub} = id
 * da credencial, {@code papel}, {@code sv} = versão de sessão da credencial no momento da emissão,
 * {@code jti} aleatório, {@code iss}, {@code aud}, {@code iat}, {@code exp}. O token não carrega
 * nome, e-mail nem CPF: ele viaja em header, aparece em log de proxy e é legível por quem o tiver
 * (assinado não é cifrado).
 *
 * <p><strong>Conferência estrita.</strong> Só HS256 é aceito (lista fechada: {@code alg:
 * none}, HS512 ou RS256 com a mesma chave são recusados), exige assinatura, {@code iss}, {@code aud},
 * {@code exp} e {@code sub}. Qualquer falha vira {@link Optional#empty()} — o motivo vai para log em
 * DEBUG, nunca para o cliente, que recebe o mesmo 401 em todos os casos.
 *
 * <p><strong>Segredo.</strong> A aplicação não sobe com segredo ausente (fora de {@code dev}/{@code
 * test}), com menos de 32 bytes (RFC 7518 §3.2: a chave do HS256 precisa ter pelo menos o tamanho
 * do hash) ou com o valor de exemplo do {@code .env.example}.
 */
@Component
@ConditionalOnWebApplication
public class JwtService {

  /** Claim do papel da credencial. */
  static final String CLAIM_PAPEL = "papel";

  /** Claim da versão de sessão da credencial ({@code CredencialUsuario#versaoSessao}). */
  static final String CLAIM_VERSAO_SESSAO = "sv";

  /** Mínimo de bytes do segredo do HS256 (RFC 7518 §3.2). */
  static final int MINIMO_BYTES_SEGREDO = 32;

  private static final Logger log = LoggerFactory.getLogger(JwtService.class);

  private final SecretKey chave;
  private final JwtProperties propriedades;
  private final Clock clock;
  private final JwtParser parser;

  /**
   * Monta a chave e o parser, recusando segredo fraco.
   *
   * @param propriedades configuração do JWT
   * @param clock        relógio do projeto (fixo nos testes)
   * @throws IllegalStateException se o segredo estiver ausente, curto ou for o de exemplo
   */
  public JwtService(JwtProperties propriedades, Clock clock) {
    this.propriedades = propriedades;
    this.clock = clock;
    this.chave = Keys.hmacShaKeyFor(bytesDoSegredo(propriedades));
    this.parser = Jwts.parser()
        .verifyWith(chave)
        .requireIssuer(propriedades.emissor())
        .requireAudience(propriedades.audiencia())
        .clockSkewSeconds(propriedades.toleranciaRelogio().toSeconds())
        .clock(() -> Date.from(clock.instant()))
        .build();
  }

  /**
   * Emite o token de acesso de uma credencial.
   *
   * @param credencialId id da credencial
   * @param papel        papel da credencial
   * @param versaoSessao versão de sessão atual da credencial
   * @return o token e o instante em que expira
   */
  public TokenAcesso emitir(UUID credencialId, PapelUsuario papel, int versaoSessao) {
    Instant agora = clock.instant();
    Instant expiraEm = agora.plus(propriedades.validade());
    String token = Jwts.builder()
        .header().type("JWT").and()
        .id(UUID.randomUUID().toString())
        .subject(credencialId.toString())
        .issuer(propriedades.emissor())
        .audience().add(propriedades.audiencia()).and()
        .issuedAt(Date.from(agora))
        .expiration(Date.from(expiraEm))
        .claim(CLAIM_PAPEL, papel.name())
        .claim(CLAIM_VERSAO_SESSAO, versaoSessao)
        .signWith(chave, Jwts.SIG.HS256)
        .compact();
    return new TokenAcesso(token, expiraEm);
  }

  /**
   * Confere o token e extrai o que ele afirma.
   *
   * <p>Só prova que o token foi emitido por este backend e ainda não expirou. Se a credencial
   * continua ativa, com o mesmo papel e a mesma versão de sessão, é pergunta para o banco — quem
   * chama precisa fazê-la.
   *
   * @param token token compacto, sem o prefixo {@code Bearer}
   * @return o que o token afirma, ou vazio se ele for inválido por qualquer motivo
   */
  public Optional<TokenConferido> conferir(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    try {
      Jws<Claims> assinado = parser.parseSignedClaims(token);
      // Lista fechada de algoritmo: so o HS256 que este servico emite. A chave HMAC de 256 bits ja
      // recusa HS384/HS512 (fraca demais) e RS/ES (tipo errado), e o jjwt recusa "alg: none";
      // a checagem explicita impede que uma troca futura de chave abra outro algoritmo em silencio.
      if (!Jwts.SIG.HS256.getId().equals(assinado.getHeader().getAlgorithm())) {
        log.debug("JWT com algoritmo fora da lista.");
        return Optional.empty();
      }
      Claims claims = assinado.getPayload();
      Integer versao = claims.get(CLAIM_VERSAO_SESSAO, Integer.class);
      String papel = claims.get(CLAIM_PAPEL, String.class);
      if (claims.getSubject() == null || versao == null || papel == null
          || claims.getExpiration() == null) {
        log.debug("JWT sem claim obrigatoria.");
        return Optional.empty();
      }
      return Optional.of(new TokenConferido(
          UUID.fromString(claims.getSubject()), PapelUsuario.valueOf(papel), versao));
    } catch (JwtException | IllegalArgumentException e) {
      // Assinatura, algoritmo, emissor, audiencia, expiracao, formato: o cliente nao sabe qual.
      log.debug("JWT recusado: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private static byte[] bytesDoSegredo(JwtProperties propriedades) {
    String segredo = propriedades.segredo();
    boolean ausente = segredo == null || segredo.isBlank();
    boolean deExemplo = !ausente && segredo.toUpperCase(Locale.ROOT).contains("CHANGE_ME");
    if (ausente || deExemplo) {
      if (!propriedades.permitirSegredoEfemero()) {
        throw new IllegalStateException(deExemplo
            ? "JWT_SECRET ainda e o valor de exemplo do .env.example: gere um segredo aleatorio."
            : "JWT_SECRET ausente: defina um segredo aleatorio de pelo menos "
                + MINIMO_BYTES_SEGREDO + " bytes (ex.: openssl rand -base64 48).");
      }
      // Em dev, o valor de exemplo de um .env copiado vale como ausente: conhecido de todos, ele
      // nunca pode assinar token, e travar a subida local por isso so atrapalharia.
      log.warn("JWT_SECRET {}: usando segredo efemero. Tokens deixam de valer a cada reinicio. "
          + "Aceitavel so em dev/test.", deExemplo ? "e o valor de exemplo" : "ausente");
      byte[] aleatorio = new byte[MINIMO_BYTES_SEGREDO];
      new SecureRandom().nextBytes(aleatorio);
      return aleatorio;
    }
    byte[] bytes = segredo.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MINIMO_BYTES_SEGREDO) {
      throw new IllegalStateException("JWT_SECRET curto demais: o HS256 exige pelo menos "
          + MINIMO_BYTES_SEGREDO + " bytes.");
    }
    return bytes;
  }

  /**
   * Token emitido.
   *
   * @param token    JWT compacto
   * @param expiraEm instante de expiração
   */
  public record TokenAcesso(String token, Instant expiraEm) {

    /** Sem o token: ele é a credencial em si. */
    @Override
    public String toString() {
      return "TokenAcesso[expiraEm=" + expiraEm + "]";
    }
  }

  /**
   * O que um token válido afirma.
   *
   * @param credencialId id da credencial ({@code sub})
   * @param papel        papel no momento da emissão
   * @param versaoSessao versão de sessão no momento da emissão
   */
  public record TokenConferido(UUID credencialId, PapelUsuario papel, int versaoSessao) {
  }
}
