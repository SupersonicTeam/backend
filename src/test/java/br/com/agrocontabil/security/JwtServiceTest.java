package br.com.agrocontabil.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.JwtProperties;
import br.com.agrocontabil.security.JwtService.TokenAcesso;
import br.com.agrocontabil.security.JwtService.TokenConferido;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("JwtService — emissao e conferencia estrita do JWT de acesso (#206)")
class JwtServiceTest {

  private static final String SEGREDO = "segredo-de-teste-agrocontabil-com-mais-de-32-bytes";
  private static final Instant AGORA = Instant.parse("2026-10-19T12:00:00Z");
  private static final UUID CREDENCIAL = UUID.randomUUID();

  private static JwtProperties propriedades(String segredo, boolean efemero) {
    return new JwtProperties(segredo, "agrocontabil-backend", "agrocontabil-api",
        Duration.ofMinutes(15), Duration.ofSeconds(30), efemero);
  }

  private static JwtService servico(Instant instante) {
    return new JwtService(propriedades(SEGREDO, false), Clock.fixed(instante, ZoneOffset.UTC));
  }

  private static SecretKey chave() {
    return Keys.hmacShaKeyFor(SEGREDO.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("token emitido confere e devolve credencial, papel e versao de sessao")
  void emiteEConfere() {
    JwtService jwt = servico(AGORA);
    TokenAcesso emitido = jwt.emitir(CREDENCIAL, PapelUsuario.CONTADOR, 3);

    assertThat(emitido.expiraEm()).isEqualTo(AGORA.plus(Duration.ofMinutes(15)));
    assertThat(jwt.conferir(emitido.token()))
        .contains(new TokenConferido(CREDENCIAL, PapelUsuario.CONTADOR, 3));
    assertThat(emitido.toString()).doesNotContain(emitido.token());
  }

  @Test
  @DisplayName("token nao carrega dado pessoal: so ids, papel e versao")
  void semDadoPessoal() {
    String token = servico(AGORA).emitir(CREDENCIAL, PapelUsuario.PRODUTOR, 0).token();
    String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),
        StandardCharsets.UTF_8);

    assertThat(payload).contains("\"sub\":\"" + CREDENCIAL + "\"", "\"papel\":\"PRODUTOR\"",
        "\"sv\":0", "\"iss\":\"agrocontabil-backend\"", "\"jti\"");
    assertThat(payload).doesNotContain("email", "cpf", "nome");
  }

  @Test
  @DisplayName("expirado e recusado; dentro da tolerancia de relogio ainda vale")
  void expiracao() {
    String token = servico(AGORA).emitir(CREDENCIAL, PapelUsuario.PRODUTOR, 0).token();

    assertThat(servico(AGORA.plus(Duration.ofMinutes(15)).plusSeconds(20)).conferir(token))
        .isPresent();
    assertThat(servico(AGORA.plus(Duration.ofMinutes(15)).plusSeconds(31)).conferir(token))
        .isEmpty();
  }

  @Test
  @DisplayName("assinatura adulterada ou payload trocado: recusado")
  void adulterado() {
    JwtService jwt = servico(AGORA);
    String token = jwt.emitir(CREDENCIAL, PapelUsuario.PRODUTOR, 0).token();
    String[] partes = token.split("\\.");

    // Promove o papel no payload mantendo a assinatura original.
    String payload = new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8)
        .replace("PRODUTOR", "CONTADOR");
    String forjado = partes[0] + "."
        + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + "." + partes[2];

    assertThat(jwt.conferir(forjado)).isEmpty();
    assertThat(jwt.conferir(partes[0] + "." + partes[1] + ".assinaturaQualquer")).isEmpty();
  }

  @Test
  @DisplayName("alg none (token sem assinatura) e recusado")
  void algNone() {
    String semAssinatura = Jwts.builder()
        .subject(CREDENCIAL.toString())
        .issuer("agrocontabil-backend").audience().add("agrocontabil-api").and()
        .expiration(Date.from(AGORA.plusSeconds(600)))
        .claim("papel", "CONTADOR").claim("sv", 0)
        .compact();

    assertThat(servico(AGORA).conferir(semAssinatura)).isEmpty();
  }

  @Test
  @DisplayName("assinado com o mesmo segredo mas outro algoritmo (HS512) e recusado")
  void outroAlgoritmo() {
    byte[] longo = (SEGREDO + SEGREDO).getBytes(StandardCharsets.UTF_8);
    // Quem conhecesse o segredo nao deveria conseguir trocar de algoritmo; aqui a chave e outra
    // (64 bytes, para o HS512 aceitar), entao a assinatura tambem nao confere.
    String hs512 = Jwts.builder()
        .subject(CREDENCIAL.toString())
        .issuer("agrocontabil-backend").audience().add("agrocontabil-api").and()
        .expiration(Date.from(AGORA.plusSeconds(600)))
        .claim("papel", "PRODUTOR").claim("sv", 0)
        .signWith(Keys.hmacShaKeyFor(longo), Jwts.SIG.HS512)
        .compact();

    assertThat(servico(AGORA).conferir(hs512)).isEmpty();
  }

  @Test
  @DisplayName("mesmo segredo, outro emissor ou outra audiencia: recusado")
  void emissorEAudiencia() {
    String outroEmissor = Jwts.builder()
        .subject(CREDENCIAL.toString())
        .issuer("outro-sistema").audience().add("agrocontabil-api").and()
        .expiration(Date.from(AGORA.plusSeconds(600)))
        .claim("papel", "PRODUTOR").claim("sv", 0)
        .signWith(chave(), Jwts.SIG.HS256).compact();
    String outraAudiencia = Jwts.builder()
        .subject(CREDENCIAL.toString())
        .issuer("agrocontabil-backend").audience().add("outra-api").and()
        .expiration(Date.from(AGORA.plusSeconds(600)))
        .claim("papel", "PRODUTOR").claim("sv", 0)
        .signWith(chave(), Jwts.SIG.HS256).compact();

    assertThat(servico(AGORA).conferir(outroEmissor)).isEmpty();
    assertThat(servico(AGORA).conferir(outraAudiencia)).isEmpty();
  }

  @Test
  @DisplayName("sem exp, sem sv ou com papel desconhecido: recusado")
  void claimsObrigatorias() {
    String semExp = Jwts.builder()
        .subject(CREDENCIAL.toString())
        .issuer("agrocontabil-backend").audience().add("agrocontabil-api").and()
        .claim("papel", "PRODUTOR").claim("sv", 0)
        .signWith(chave(), Jwts.SIG.HS256).compact();
    String semVersao = Jwts.builder()
        .subject(CREDENCIAL.toString())
        .issuer("agrocontabil-backend").audience().add("agrocontabil-api").and()
        .expiration(Date.from(AGORA.plusSeconds(600)))
        .claim("papel", "PRODUTOR")
        .signWith(chave(), Jwts.SIG.HS256).compact();
    String papelAdmin = Jwts.builder()
        .subject(CREDENCIAL.toString())
        .issuer("agrocontabil-backend").audience().add("agrocontabil-api").and()
        .expiration(Date.from(AGORA.plusSeconds(600)))
        .claim("papel", "ADMIN").claim("sv", 0)
        .signWith(chave(), Jwts.SIG.HS256).compact();

    JwtService jwt = servico(AGORA);
    assertThat(jwt.conferir(semExp)).isEmpty();
    assertThat(jwt.conferir(semVersao)).isEmpty();
    assertThat(jwt.conferir(papelAdmin)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "nao-e-jwt", "a.b.c"})
  @DisplayName("lixo no lugar do token: recusado sem excecao")
  void lixo(String token) {
    assertThat(servico(AGORA).conferir(token)).isEmpty();
    assertThat(servico(AGORA).conferir(null)).isEmpty();
  }

  @Test
  @DisplayName("segredo ausente, curto ou de exemplo impede a subida")
  void segredoFraco() {
    Clock clock = Clock.fixed(AGORA, ZoneOffset.UTC);
    assertThatThrownBy(() -> new JwtService(propriedades(null, false), clock))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("ausente");
    assertThatThrownBy(() -> new JwtService(propriedades("curto-demais", false), clock))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("curto");
    assertThatThrownBy(() -> new JwtService(propriedades("CHANGE_ME_MIN_32_CHARS_xxxxxxxxxxxx",
        false), clock))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("exemplo");
  }

  @Test
  @DisplayName("em dev, ausente ou de exemplo vira segredo efemero (tokens nao sobrevivem ao reinicio)")
  void segredoEfemero() {
    Clock clock = Clock.fixed(AGORA, ZoneOffset.UTC);
    JwtService primeiro = new JwtService(propriedades("", true), clock);
    JwtService reiniciado = new JwtService(propriedades("CHANGE_ME_MIN_32_CHARS", true), clock);

    String token = primeiro.emitir(CREDENCIAL, PapelUsuario.PRODUTOR, 0).token();
    assertThat(primeiro.conferir(token)).isPresent();
    assertThat(reiniciado.conferir(token)).isEmpty();
    assertThatCode(() -> new JwtService(propriedades(SEGREDO, true), clock))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("toString das propriedades nao mostra o segredo")
  void propriedadesSemSegredo() {
    assertThat(propriedades(SEGREDO, false).toString()).doesNotContain(SEGREDO);
  }
}
