package br.com.agrocontabil.common;

import static org.assertj.core.api.Assertions.assertThat;

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
  @DisplayName("chave de acesso (44 digitos), UUID e valor monetario ficam intactos")
  void naoConfundeComDocumento() {
    String chave = "51261012345678000195550010000001231000001234";
    String texto = "chave=" + chave + " id=7f1c2e3a-1b2c-4d5e-8f90-1234567890ab valor=R$ 123.456.789,01";

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
  @DisplayName("o conversor do Logback mascara a mensagem ja formatada (inclusive argumentos)")
  void conversorDoLogback() {
    LoggingEvent evento = new LoggingEvent("fqcn",
        new LoggerContext().getLogger("teste"), Level.INFO,
        "Cadastro de {} com e-mail {}", null, new Object[] {"52998224725", "a@b.com"});

    assertThat(new MensagemLogMascaradaConverter().convert(evento))
        .isEqualTo("Cadastro de [CPF] com e-mail [EMAIL]");
  }
}
