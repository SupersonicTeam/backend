package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.EmailProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("TetoEnvioEmail — teto de envios em janelas deslizantes (#460)")
class TetoEnvioEmailTest {

  /** Relógio que o teste avança. */
  static final class Relogio extends Clock {
    private Instant agora = Instant.parse("2026-11-20T23:30:00Z");

    void avancar(Duration tempo) {
      agora = agora.plus(tempo);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zona) {
      return this;
    }

    @Override
    public Instant instant() {
      return agora;
    }
  }

  private final Relogio relogio = new Relogio();

  private TetoEnvioEmail teto(int porHora, int porDia) {
    return new TetoEnvioEmail(new EmailProperties("agroconia.com.br", "contato@gwnext.com.br",
        porHora, porDia, 5, List.of()), relogio);
  }

  private static int reservar(TetoEnvioEmail teto, int vezes) {
    int aceitos = 0;
    for (int i = 0; i < vezes; i++) {
      if (teto.reservar(TipoEmail.REDEFINICAO_SENHA)) {
        aceitos++;
      }
    }
    return aceitos;
  }

  @Test
  @DisplayName("24 horas: o 81º e-mail e descartado, mesmo virando o dia UTC")
  void tetoDiario(CapturedOutput saida) {
    TetoEnvioEmail teto = teto(0, 80);
    assertThat(reservar(teto, 80)).isEqualTo(80);
    assertThat(saida).contains("64 de 80 envios permitidos em 24 horas");
    // Meia-noite UTC (21h em Brasilia): a cota da Resend zera, mas a janela corrida nao.
    relogio.avancar(Duration.ofMinutes(31));
    assertThat(teto.reservar(TipoEmail.CONVITE_EQUIPE)).isFalse();
    assertThat(saida).contains("Teto de e-mail de 24 horas atingido (80): e-mail de "
        + "CONVITE_EQUIPE descartado (1 seguido(s))");
    // 24 horas depois do primeiro envio, a janela anda e libera.
    relogio.avancar(Duration.ofHours(24));
    assertThat(reservar(teto, 80)).isEqualTo(80);
    assertThat(teto.reservar(TipoEmail.CONVITE_CARTEIRA)).isFalse();
  }

  @Test
  @DisplayName("1 hora: o 41º e-mail na hora e descartado; uma hora depois volta")
  void tetoPorHora() {
    TetoEnvioEmail teto = teto(40, 320);
    assertThat(reservar(teto, 50)).isEqualTo(40);
    relogio.avancar(Duration.ofMinutes(59));
    assertThat(teto.reservar(TipoEmail.REDEFINICAO_SENHA)).isFalse();
    relogio.avancar(Duration.ofMinutes(2));
    assertThat(reservar(teto, 50)).isEqualTo(40);
  }

  @Test
  @DisplayName("as duas janelas juntas: o diario barra antes de o horario encher")
  void duasJanelas() {
    TetoEnvioEmail teto = teto(40, 100);
    int aceitos = 0;
    for (int hora = 0; hora < 5; hora++) {
      aceitos += reservar(teto, 40);
      relogio.avancar(Duration.ofHours(1).plusSeconds(1));
    }
    assertThat(aceitos).isEqualTo(100);
  }

  @Test
  @DisplayName("zero nas duas janelas: sem teto")
  void semTeto() {
    assertThat(reservar(teto(0, 0), 1000)).isEqualTo(1000);
  }

  @Test
  @DisplayName("sob abuso, o aviso de descarte sai no primeiro e depois a cada 100")
  void avisoDeDescarteLimitado(CapturedOutput saida) {
    TetoEnvioEmail teto = teto(0, 1);
    reservar(teto, 1 + 250);
    assertThat(saida.getOut().lines().filter(l -> l.contains("atingido")).count()).isEqualTo(3);
  }
}
