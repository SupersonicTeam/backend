package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.service.MensagensEmail.Mensagem;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MensagensEmail — os tres e-mails em texto e HTML (#460)")
class MensagensEmailTest {

  private static final String CONTATO = "contato@gwnext.com.br";
  private static final String LINK_SENHA =
      "https://www.agroconia.com.br/redefinir-senha#token=abc-DEF_123";
  private static final String LINK_CARTEIRA = "https://www.agroconia.com.br/escritorio";
  private static final String LINK_EQUIPE = "https://www.agroconia.com.br/equipe/convite#token=x";
  /** 24/11/2026 às 15h30 em Brasília, dado em UTC: a data sai no fuso de Brasília. */
  private static final OffsetDateTime VENCE = OffsetDateTime.parse("2026-11-24T18:30:00Z");

  @Test
  @DisplayName("redefinicao: assunto, pre-cabecalho, prazo, botao e o link com o token no fragmento")
  void redefinicao() {
    Mensagem m = MensagensEmail.redefinicaoSenha(LINK_SENHA, Duration.ofMinutes(30), CONTATO);
    assertThat(m.assunto()).isEqualTo("Link para criar uma nova senha no AgroConIA");
    assertThat(m.preCabecalho()).contains("30 minutos e só uma vez");
    assertThat(m.texto())
        .contains("Criar nova senha:\n" + LINK_SENHA + "\n")
        .contains("O link vale por 30 minutos e só uma vez.")
        .contains("Se não foi você que pediu, ignore este e-mail: a sua senha não muda.")
        .contains(ModeloEmail.SEGURANCA)
        .contains("Este é um e-mail automático. Dúvidas: " + CONTATO)
        .contains("Política de dados: https://www.agroconia.com.br/politica-dados")
        .contains("AgroConIA, da GWNext Technologies LTDA");
    assertThat(m.html())
        .contains("<a href=\"" + LINK_SENHA + "\" target=\"_blank\"")
        .contains(">Criar nova senha</a>")
        .contains("<html lang=\"pt-BR\">")
        .contains(m.preCabecalho())
        .contains("src=\"https://www.agroconia.com.br/apple-touch-icon.png\"")
        .contains("href=\"https://www.agroconia.com.br/politica-dados\"")
        .contains("href=\"mailto:" + CONTATO + "\"")
        .doesNotContain("?token")
        .doesNotContainIgnoringCase("<script")
        .doesNotContainIgnoringCase("<link ");
  }

  @Test
  @DisplayName("convite da carteira: nome em negrito, data em Brasilia e /escritorio")
  void conviteCarteira() {
    Mensagem m = MensagensEmail.conviteCarteira("Contabilidade São João", LINK_CARTEIRA, VENCE,
        CONTATO);
    assertThat(m.assunto()).isEqualTo("Convite de escritório de contabilidade no AgroConIA")
        .doesNotContain("São João");
    assertThat(m.preCabecalho()).isEqualTo("Contabilidade São João quer atender você pelo "
        + "AgroConIA. O convite vale até 24/11/2026.");
    assertThat(m.texto())
        .contains("O escritório Contabilidade São João convidou você para a carteira")
        .contains("O convite vale até 24/11/2026, às 15h30 (horário de Brasília).")
        .contains("Ver convite:\n" + LINK_CARTEIRA + "\n")
        .contains("- Ele não edita as suas notas e não vê a sua senha.");
    assertThat(m.html()).contains("<strong>Contabilidade São João</strong>")
        .contains(">Ver convite</a>");
  }

  @Test
  @DisplayName("convite de equipe: prazo, link com token e nome vazio sem aspas")
  void conviteEquipe() {
    Mensagem m = MensagensEmail.conviteEquipe(" ", LINK_EQUIPE, VENCE, CONTATO);
    assertThat(m.assunto()).isEqualTo("Convite para a equipe de um escritório no AgroConIA");
    assertThat(m.texto())
        .contains("Um escritório de contabilidade convidou você para a equipe dele no AgroConIA.")
        .contains("O convite vale até 24/11/2026, às 15h30 (horário de Brasília) e só uma vez.")
        .contains("Entrar na equipe:\n" + LINK_EQUIPE + "\n");
    assertThat(m.html()).doesNotContain("<strong></strong>").doesNotContain("<strong> </strong>");
  }

  @Test
  @DisplayName("nome do escritorio e link sao escapados no HTML")
  void escapaHtml() {
    Mensagem m = MensagensEmail.conviteCarteira("Escritório <b>\"A&B\"</b>",
        "https://www.agroconia.com.br/escritorio?a=1&b=\"2\"", VENCE, CONTATO);
    assertThat(m.html())
        .contains("Escritório &lt;b&gt;&quot;A&amp;B&quot;&lt;/b&gt;")
        .contains("href=\"https://www.agroconia.com.br/escritorio?a=1&amp;b=&quot;2&quot;\"")
        .doesNotContain("<b>\"A&B\"</b>");
  }

  @Test
  @DisplayName("sem contato configurado, o rodape so diz que e automatico")
  void semContato() {
    Mensagem m = MensagensEmail.redefinicaoSenha(LINK_SENHA, Duration.ofMinutes(30), "");
    assertThat(m.texto()).contains("Este é um e-mail automático.\n").doesNotContain("Dúvidas");
    assertThat(m.html()).doesNotContain("mailto:").doesNotContain("Dúvidas");
  }

  @Test
  @DisplayName("data e hora em Brasilia, inclusive perto da meia-noite UTC")
  void dataEmBrasilia() {
    assertThat(MensagensEmail.dataHora(OffsetDateTime.parse("2026-11-25T02:59:00Z")))
        .isEqualTo("24/11/2026, às 23h59");
    assertThat(MensagensEmail.data(OffsetDateTime.parse("2026-11-25T03:00:00Z")))
        .isEqualTo("25/11/2026");
  }

  @Test
  @DisplayName("prazo: plural, horas quando nao fecha o dia e nunca arredonda para cima")
  void prazo() {
    assertThat(MensagensEmail.prazo(Duration.ofMinutes(1))).isEqualTo("1 minuto");
    assertThat(MensagensEmail.prazo(Duration.ofSeconds(10))).isEqualTo("1 minuto");
    assertThat(MensagensEmail.prazo(Duration.ofMinutes(59))).isEqualTo("59 minutos");
    assertThat(MensagensEmail.prazo(Duration.ofHours(1))).isEqualTo("1 hora");
    assertThat(MensagensEmail.prazo(Duration.ofMinutes(90))).isEqualTo("1 hora");
    assertThat(MensagensEmail.prazo(Duration.ofHours(12))).isEqualTo("12 horas");
    assertThat(MensagensEmail.prazo(Duration.ofHours(36))).isEqualTo("36 horas");
    assertThat(MensagensEmail.prazo(Duration.ofDays(1))).isEqualTo("1 dia");
    assertThat(MensagensEmail.prazo(Duration.ofDays(14))).isEqualTo("14 dias");
  }
}
