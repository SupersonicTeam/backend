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

    assertThat(ref).matches("ref:[0-9a-f]{6}-[0-9a-f]{6}")
        .isEqualTo(MascaraDadosPessoais.referencia(id))
        .doesNotContain("72f7d6a7");
    assertThat(MascaraDadosPessoais.referencia(java.util.UUID.randomUUID())).isNotEqualTo(ref);
    assertThat(MascaraDadosPessoais.referencia(null)).isEqualTo("ref:-");
  }

  @Test
  @DisplayName("referencia passa intacta pela mascara mesmo com hash so de digitos (#222)")
  void referenciaImuneAMascara() {
    // SHA-256 deste UUID comeca com 46696269333f: 11 digitos e uma letra. Sem o separador, a
    // regra de CPF trocava o trecho e o log saia "ref:[CPF]f", inutil para correlacao.
    java.util.UUID id = java.util.UUID.fromString("00000000-0000-0000-0000-000000000191");

    String ref = MascaraDadosPessoais.referencia(id);

    assertThat(ref).isEqualTo("ref:466962-69333f");
    assertThat(MascaraDadosPessoais.mascarar("login recusado " + ref + " bloqueada"))
        .isEqualTo("login recusado " + ref + " bloqueada");
  }

  @Test
  @DisplayName("referencia de qualquer hash nao casa CPF, CNPJ nem telefone")
  void referenciaSoDeDigitosNaoCasaNenhumaRegra() {
    // Pior caso de cada regra: as duas metades so de digitos.
    for (String ref : new String[] {"ref:123456-789012", "ref:000000-000000",
        "ref:529982-247250", "ref:119999-912345"}) {
      assertThat(MascaraDadosPessoais.mascarar(ref)).isEqualTo(ref);
      String linha = "conta " + ref + ".";
      assertThat(MascaraDadosPessoais.mascarar(linha)).isEqualTo(linha);
    }
    // Sequencia fixa (nao randomUUID): uma falha aponta o UUID e se repete localmente.
    for (long i = 0; i < 2_000; i++) {
      java.util.UUID id = new java.util.UUID(0x222L, i);
      String ref = MascaraDadosPessoais.referencia(id);
      assertThat(MascaraDadosPessoais.mascarar(ref)).as("uuid %s", id).isEqualTo(ref);
    }
  }

  @Test
  @DisplayName("referencia sai intacta pelo conversor do Logback, o caminho real do log")
  void referenciaPeloConversorDoLogback() {
    java.util.UUID id = java.util.UUID.fromString("00000000-0000-0000-0000-000000000191");
    LoggingEvent evento = new LoggingEvent("fqcn",
        new LoggerContext().getLogger("teste"), Level.WARN,
        "Login recusado: credencial={}", null, new Object[] {MascaraDadosPessoais.referencia(id)});

    assertThat(new MensagemLogMascaradaConverter().convert(evento))
        .isEqualTo("Login recusado: credencial=ref:466962-69333f");
  }

  @Test
  @DisplayName("UUID e token hex com 11 digitos colados em letra nao viram [CPF]")
  void tokenAlfanumericoNaoViraDocumento() {
    // Antes, a fronteira so excluia digito e ponto: o ultimo grupo do UUID saia "[CPF]a".
    String texto = "nota=72f7d6a7-c101-401b-a58b-12345678901a jti=ab12345678901"
        + " hash=1122233300018bcd sha=ab11222333000181";

    assertThat(MascaraDadosPessoais.mascarar(texto)).isEqualTo(texto);
  }

  @Test
  @DisplayName("documento separado por qualquer nao alfanumerico segue mascarado")
  void documentoComSeparadorSegueMascarado() {
    assertThat(MascaraDadosPessoais.mascarar(
        "cpf_52998224725 (52998224725) cpf:52998224725 \"52998224725\" -52998224725-"
            + " cnpj_11222333000181 [11.222.333/0001-81]"))
        .isEqualTo("cpf_[CPF] ([CPF]) cpf:[CPF] \"[CPF]\" -[CPF]-"
            + " cnpj_[CNPJ] [[CNPJ]]");
  }

  @Test
  @DisplayName("limite aceito: documento colado em letra ou digito nao e mascarado")
  void documentoColadoEmLetraNaoEMascarado() {
    // Decisao do PR #310: sem a fronteira alfanumerica, todo token hex/UUID/base64 com 11
    // digitos colados em letra virava [CPF]. CPF grudado em texto, sem separador, e raro em log.
    assertThat(MascaraDadosPessoais.mascarar("cpf52998224725")).isEqualTo("cpf52998224725");
  }
}
