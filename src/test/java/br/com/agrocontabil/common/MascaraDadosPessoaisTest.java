package br.com.agrocontabil.common;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.ExcecaoLogMascaradaConverter;
import br.com.agrocontabil.config.MensagemLogMascaradaConverter;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MascaraDadosPessoais — dado pessoal fora dos logs (#148)")
class MascaraDadosPessoaisTest {

  @Test
  @DisplayName("CPF, CNPJ, e-mail e telefone viram marcadores, com ou sem pontuacao")
  void mascaraDocumentosEContato() {
    assertThat(MascaraDadosPessoais.mascarar(
        "cpf=529.982.247-25 doc=52998224725 cnpj=11.222.333/0001-81 raw=11222333000181"))
        .isEqualTo("cpf=[CPF] doc=[CPF] cnpj=[CNPJ] raw=[CNPJ]");
    assertThat(MascaraDadosPessoais.mascarar("contato joao@fazenda.com.br fone (65) 99999-1234"))
        .isEqualTo("contato [EMAIL] fone [TELEFONE]");
  }

  @Test
  @DisplayName("telefone sem parenteses (com o hifen) e CPF com espacos tambem")
  void formatosAlternativos() {
    assertThat(MascaraDadosPessoais.mascarar("a 11 98765-4321 b +55 65 3322-1100 c"))
        .isEqualTo("a [TELEFONE] b [TELEFONE] c");
    assertThat(MascaraDadosPessoais.mascarar("cpf 529 982 247-25")).isEqualTo("cpf [CPF]");
  }

  @Test
  @DisplayName("chave de acesso (44 digitos), UUID e valor monetario ficam intactos")
  void naoConfundeComDocumento() {
    String chave = "51261012345678000195550010000001231000001234";
    String texto = "chave=" + chave + " id=7f1c2e3a-1b2c-4d5e-8f90-1234567890ab valor=R$ 123.456.789,01"
        + " total=1234567";

    assertThat(MascaraDadosPessoais.mascarar(texto)).isEqualTo(texto);
  }

  @Test
  @DisplayName("IP truncado: ultimo octeto no IPv4, /64 no IPv6")
  void truncaIp() {
    assertThat(MascaraDadosPessoais.truncarIp("203.0.113.77")).isEqualTo("203.0.113.0");
    assertThat(MascaraDadosPessoais.truncarIp("2001:db8:1234:5678:aaaa:bbbb:cccc:dddd"))
        .isEqualTo("2001:db8:1234:5678::/64");
    assertThat(MascaraDadosPessoais.truncarIp(null)).isNull();
  }

  @Test
  @DisplayName("IPv6 comprimido e IPv4 mapeado nao saem inteiros; o que nao e IP vira [IP]")
  void truncaFormasComprimidas() {
    assertThat(MascaraDadosPessoais.truncarIp("2804:14c::5")).doesNotEndWith("5").endsWith("/64")
        .startsWith("2804:14c:");
    assertThat(MascaraDadosPessoais.truncarIp("::ffff:203.0.113.7")).isEqualTo("203.0.113.0");
    assertThat(MascaraDadosPessoais.truncarIp("evil.example.com")).isEqualTo("[IP]");
    assertThat(MascaraDadosPessoais.truncarIp("fe80::1%eth0")).isEqualTo("[IP]");
  }

  @Test
  @DisplayName("o conversor do Logback mascara a mensagem ja formatada (inclusive argumentos)")
  void conversorDoLogback() {
    LoggingEvent evento = new LoggingEvent("fqcn",
        new LoggerContext().getLogger("teste"), Level.INFO,
        "Cadastro de {} com e-mail {}", null, new Object[] {"52998224725", "a@b.com"});

    assertThat(new MensagemLogMascaradaConverter().convert(evento))
        .isEqualTo("Cadastro de [CPF] com e-mail [EMAIL]");
  }

  @Test
  @DisplayName("o conversor de stack trace mascara a mensagem da excecao e a da causa")
  void conversorDeExcecao() {
    LoggerContext contexto = new LoggerContext();
    LoggingEvent evento = new LoggingEvent("fqcn", contexto.getLogger("teste"), Level.ERROR,
        "falhou", new IllegalStateException("insert falhou",
            new RuntimeException("Key (email)=(joao@fazenda.com.br) already exists; cpf 52998224725")),
        null);
    ExcecaoLogMascaradaConverter conversor = new ExcecaoLogMascaradaConverter();
    conversor.setContext(contexto);
    conversor.start();

    String saida = conversor.convert(evento);

    assertThat(saida).contains("Key (email)=([EMAIL])").contains("cpf [CPF]")
        .doesNotContain("joao@").doesNotContain("52998224725");
  }

  @Test
  @DisplayName("referencia de id: estavel, curta e sem o UUID (#222)")
  void referenciaDeId() {
    java.util.UUID id = java.util.UUID.fromString("72f7d6a7-c101-401b-a58b-50216b04caf9");

    String ref = MascaraDadosPessoais.referencia(id);

    assertThat(ref).matches("ref:[0-9a-f]{12}").isEqualTo(MascaraDadosPessoais.referencia(id))
        .doesNotContain("72f7d6a7");
    assertThat(MascaraDadosPessoais.referencia(java.util.UUID.randomUUID())).isNotEqualTo(ref);
    assertThat(MascaraDadosPessoais.referencia(null)).isEqualTo("ref:-");
  }
}
