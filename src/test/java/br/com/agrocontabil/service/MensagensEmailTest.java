package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.service.MensagensEmail.Mensagem;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MensagensEmail — textos dos tres e-mails (#460)")
class MensagensEmailTest {

  private static final String LINK = "https://www.agroconia.com.br/redefinir-senha#token=abc";

  @Test
  @DisplayName("redefinicao: acento, marca, prazo em minutos e o link inteiro")
  void redefinicao() {
    Mensagem m = MensagensEmail.redefinicaoSenha(LINK, Duration.ofMinutes(30));
    assertThat(m.assunto()).isEqualTo("Redefinição de senha do AgroConIA");
    assertThat(m.texto())
        .contains("em até 30 minutos:")
        .contains("\n" + LINK + "\n")
        .contains("vale uma vez só")
        .contains("Se você não pediu")
        .contains("AgroConIA\nEste e-mail é automático. Se precisar de ajuda, responda a ele: a "
            + "resposta chega à equipe do AgroConIA.\n");
  }

  @Test
  @DisplayName("convite da carteira: nome entre aspas, prazo em dias e nunca o nome no assunto")
  void conviteCarteira() {
    Mensagem m = MensagensEmail.conviteCarteira("Contabilidade São João",
        "https://www.agroconia.com.br/escritorio", Duration.ofDays(14));
    assertThat(m.assunto()).isEqualTo("Convite de escritório de contabilidade no AgroConIA")
        .doesNotContain("São João");
    assertThat(m.texto())
        .contains("O escritório de contabilidade \"Contabilidade São João\" convidou você")
        .contains("em até 14 dias:")
        .contains("\nhttps://www.agroconia.com.br/escritorio\n");
  }

  @Test
  @DisplayName("convite de equipe: nome vazio vira frase sem aspas")
  void conviteEquipeSemNome() {
    Mensagem m = MensagensEmail.conviteEquipe(" ",
        "https://www.agroconia.com.br/equipe/convite#token=x", Duration.ofDays(7));
    assertThat(m.assunto()).isEqualTo("Convite para a equipe de um escritório no AgroConIA");
    assertThat(m.texto())
        .contains("convite para a equipe de um escritório de contabilidade no AgroConIA")
        .doesNotContain("\"\"")
        .contains("em até 7 dias");
    assertThat(MensagensEmail.conviteCarteira(null, "x", Duration.ofDays(1)).texto())
        .contains("Um escritório de contabilidade convidou você");
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
