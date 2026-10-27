package br.com.agrocontabil.security;

import br.com.agrocontabil.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
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
 * do hash) ou com o valor de exemplo do {@code .env.example}. Desde a #222 recusa também os
 * segredos <em>públicos</em> — placeholders conhecidos ({@code CHANGE_ME}, "troque", "mude este",
 * "secret", "exemplo", o default do compose de orquestração) e o segredo do profile {@code test} —
 * e o de entropia baixa (menos de {@value #MINIMO_CARACTERES_DISTINTOS} caracteres distintos): só
 * 32 bytes não bastavam, e o default do compose (49 bytes) era aceito em homolog e prod. Com o
 * segredo conhecido, qualquer um assina token válido. Em {@code dev}/{@code test} um placeholder
 * vale como ausente (segredo efêmero), e o segredo do profile {@code test} é aceito: é ele que os
 * testes de integração usam.
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

  /** Mínimo de caracteres distintos do segredo (#222): barra "aaaa…" e "0123…0123". */
  static final int MINIMO_CARACTERES_DISTINTOS = 16;

  /**
   * Marcas de placeholder (#222), comparadas em minúsculas e só com letras e dígitos: um segredo
   * aleatório (openssl rand -base64 48) praticamente nunca as contém, e um texto de exemplo quase
   * sempre contém uma.
   */
  private static final List<String> MARCAS_DE_PLACEHOLDER = List.of(
      "changeme", "change", "troque", "mudeeste", "mudeesse", "secret", "exemplo", "example",
      "placeholder");

  /**
   * Segredos publicados (#222): o default do compose de orquestração e o do profile {@code test}.
   * Fora de dev/test, qualquer um deles é recusado — estão no repositório ou no {@code .env.example}.
   */
  private static final List<String> SEGREDOS_PUBLICOS = List.of(
      "troque-este-segredo-em-producao-min-32-caracteres",
      "segredo-de-teste-agrocontabil-com-mais-de-32-bytes");

  /** Algoritmo JCA da chave: o do HS256, qualquer que seja o tamanho do segredo. */
  private static final String ALGORITMO_CHAVE = "HmacSHA256";

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
    // HmacSHA256 explicito: Keys.hmacShaKeyFor escolheria HmacSHA512 para segredo de 64+ bytes (o
    // que o openssl rand -base64 48 do .env.example gera), e a chave deixaria de casar com o HS256.
    this.chave = new SecretKeySpec(bytesDoSegredo(propriedades), ALGORITMO_CHAVE);
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
    boolean deExemplo = !ausente && placeholder(segredo);
    if (ausente || deExemplo) {
      if (!propriedades.permitirSegredoEfemero()) {
        // Nunca o segredo na mensagem: ela vai para o log da subida.
        throw new IllegalStateException(deExemplo
            ? "JWT_SECRET e um valor de exemplo conhecido (placeholder ou default de compose): "
                + "gere um segredo aleatorio (openssl rand -base64 48)."
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
    if (!propriedades.permitirSegredoEfemero()) {
      // Em dev/test o segredo do profile test precisa valer; fora deles, publico e recusado.
      if (SEGREDOS_PUBLICOS.contains(segredo.strip())) {
        throw new IllegalStateException("JWT_SECRET e um valor de exemplo conhecido (publicado no "
            + "repositorio): gere um segredo aleatorio (openssl rand -base64 48).");
      }
      if (segredo.chars().distinct().count() < MINIMO_CARACTERES_DISTINTOS) {
        throw new IllegalStateException("JWT_SECRET com entropia baixa: menos de "
            + MINIMO_CARACTERES_DISTINTOS + " caracteres distintos. Gere um segredo aleatorio "
            + "(openssl rand -base64 48).");
      }
    }
    return bytes;
  }

  /** Contém uma marca de placeholder, ignorando caixa, hífen, sublinhado e espaço. */
  private static boolean placeholder(String segredo) {
    String normalizado = segredo.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    return MARCAS_DE_PLACEHOLDER.stream().anyMatch(normalizado::contains);
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
