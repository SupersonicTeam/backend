package br.com.agrocontabil.security;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.EnderecoEscritorioProperties;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("Host da requisicao: principal ou de escritorio (#464)")
class HostDaRequisicaoTest {

  private final HostDaRequisicao host = new HostDaRequisicao(propriedades("agroconia.com.br"));

  private static EnderecoEscritorioProperties propriedades(String base) {
    return new EnderecoEscritorioProperties(base, "",
        new EnderecoEscritorioProperties.Endereco(Duration.ofDays(30), Duration.ofDays(90)),
        new EnderecoEscritorioProperties.Publico(30, 10000));
  }

  @ParameterizedTest
  @ValueSource(strings = {"www.agroconia.com.br", "agroconia.com.br", "WWW.AgroConIA.com.br",
      "www.agroconia.com.br.", "localhost", "127.0.0.1", "agroconia.com.br.evil.com",
      "137.131.248.10.sslip.io", "outro.com.br", ""})
  @DisplayName("host principal: o dominio base, o www e tudo fora do dominio base")
  void principal(String serverName) {
    assertThat(host.rotuloDeEscritorio(serverName)).isEmpty();
  }

  @Test
  @DisplayName("host de escritorio: o que vem antes do dominio base, em minusculas e sem ponto final")
  void escritorio() {
    assertThat(host.rotuloDeEscritorio("ruraloeste.agroconia.com.br")).contains("ruraloeste");
    assertThat(host.rotuloDeEscritorio("RuralOeste.Agroconia.com.br.")).contains("ruraloeste");
    // Dois rotulos: e de escritorio, mas nenhum slug casa (o login recusa).
    assertThat(host.rotuloDeEscritorio("x.ruraloeste.agroconia.com.br"))
        .contains("x.ruraloeste");
    assertThat(host.rotuloDeEscritorio((String) null)).isEmpty();
  }

  @Test
  @DisplayName("le o getServerName da requisicao (o Tomcat ja aplicou o X-Forwarded-Host confiavel)")
  void lePeloServerName() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setServerName("ruraloeste.agroconia.com.br");
    // O cabecalho cru nunca e lido aqui: so o RemoteIpValve decide se ele vale.
    request.addHeader("X-Forwarded-Host", "outro.agroconia.com.br");
    assertThat(host.rotuloDeEscritorio(request)).isEqualTo(Optional.of("ruraloeste"));
  }

  @Test
  @DisplayName("em dev o dominio base e localhost: ruraloeste.localhost e de escritorio")
  void dev() {
    HostDaRequisicao dev = new HostDaRequisicao(propriedades("localhost"));
    assertThat(dev.rotuloDeEscritorio("ruraloeste.localhost")).contains("ruraloeste");
    assertThat(dev.rotuloDeEscritorio("localhost")).isEmpty();
  }
}
