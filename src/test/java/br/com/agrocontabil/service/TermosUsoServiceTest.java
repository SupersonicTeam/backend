package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.LgpdProperties;
import br.com.agrocontabil.config.TermosProperties;
import br.com.agrocontabil.dto.SecaoTermosResponse;
import br.com.agrocontabil.dto.TermosUsoResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Termos de uso (#498): o texto diz o que o sistema faz hoje, com a versão que o cadastro grava,
 * sem HTML, com acento e sem a cláusula jurídica que a equipe não decidiu.
 */
@DisplayName("TermosUsoService: texto dos termos de uso (#498)")
class TermosUsoServiceTest {

  private static final String CONTATO = "contato@gwnext.com.br";

  /** Radicais que em português levam acento: o texto chega à tela do titular. */
  private static final Pattern SEM_ACENTO = Pattern.compile(
      "(?iu)\\b(identificacao|obrigacao|legislacao|demonstracao|exclusao|politica|contabil|"
          + "escritorio|informacao|verificacao|seguranca|apuracao|aprovacao|emissao|transmissao|"
          + "importacao|eliminacao|publicacao|conferencia|tributario)\\b");

  private final TermosUsoService service = new TermosUsoService(
      new TermosProperties("2026-11-24"), new LgpdProperties("GWNext", CONTATO, "2026-11-17"));

  private String todoOTexto() {
    TermosUsoResponse termos = service.termos();
    List<String> partes = new ArrayList<>(List.of(termos.versao(), termos.controlador(),
        termos.contato()));
    for (SecaoTermosResponse s : termos.secoes()) {
      partes.add(s.titulo());
      partes.addAll(s.paragrafos());
      partes.addAll(s.itens());
    }
    return String.join("\n", partes);
  }

  @Test
  @DisplayName("a versão é a que o cadastro grava, e o controlador e o contato vêm da LGPD")
  void cabecalho() {
    TermosUsoResponse termos = service.termos();

    assertThat(termos.versao()).isEqualTo("2026-11-24");
    assertThat(termos.controlador()).isEqualTo("GWNext");
    assertThat(termos.contato()).isEqualTo(CONTATO);
  }

  @Test
  @DisplayName("as seções têm id único, título e conteúdo, na ordem do texto")
  void secoes() {
    List<SecaoTermosResponse> secoes = service.termos().secoes();

    assertThat(secoes).extracting(SecaoTermosResponse::id).containsExactly(
        "quem-somos", "demonstracao", "calculo", "ceres", "conta", "escritorio",
        "dados-e-exclusao", "disponibilidade", "mudancas", "contato");
    assertThat(secoes).allSatisfy(s -> {
      assertThat(s.titulo()).isNotBlank();
      assertThat(s.paragrafos().size() + s.itens().size()).as(s.id()).isPositive();
      assertThat(s.id()).matches("[a-z]+(-[a-z]+)*");
    });
  }

  @Test
  @DisplayName("fala de quem oferece o serviço: a GWNext, e a Supersonic Team só como equipe do TCC")
  void quemSomos() {
    assertThat(todoOTexto()).contains("GWNext").contains("Supersonic Team")
        .contains("Trabalho de Conclusão de Curso").contains("não é a dona");
  }

  @Test
  @DisplayName("diz que a SEFAZ é simulada e que a nota não tem valor fiscal")
  void demonstracao() {
    assertThat(todoOTexto()).contains("simulada").contains("não tem valor fiscal");
  }

  @Test
  @DisplayName("o cálculo vale para o regime regular, não substitui o contador e deixa o ICMS com ele")
  void calculo() {
    assertThat(todoOTexto()).contains("regime regular").contains("art. 164")
        .contains("não substitui o contador").contains("não calcula o ICMS devido");
  }

  @Test
  @DisplayName("a Ceres pode errar, cita a lei e o enquadramento é com o contador")
  void ceres() {
    assertThat(todoOTexto()).contains("A Ceres pode errar")
        .contains("parecer jurídico nem contábil")
        .contains("enquadramento do seu caso é com o contador");
  }

  @Test
  @DisplayName("a exclusão remete à política e diz que a guarda fiscal é de 5 anos")
  void exclusao() {
    assertThat(todoOTexto()).contains("Política de privacidade").contains("5 anos")
        .contains("não é apagado por essa exclusão");
  }

  @Test
  @DisplayName("diz que a versão 2026-11-11 é provisória e anterior ao texto")
  void versaoProvisoria() {
    assertThat(TermosUsoService.VERSAO_PROVISORIA).isEqualTo("2026-11-11");
    assertThat(todoOTexto()).contains("versão 2026-11-11").contains("é provisória")
        .contains("o texto ainda não existia");
  }

  @Test
  @DisplayName("não traz foro, limitação de responsabilidade nem promessa de reaceite")
  void semClausulaNaoDecidida() {
    String texto = todoOTexto().toLowerCase();

    assertThat(texto).doesNotContain("foro").doesNotContain("limita a responsabilidade")
        .doesNotContain("isenta a gwnext").doesNotContain("aceitar de novo")
        .doesNotContain("novo aceite");
  }

  /**
   * SHA-256 do texto inteiro da versão 2026-11-24. Mudou o texto: este teste quebra. Suba a versão
   * (yml, deploy/compose.yaml e .env.example), troque a data abaixo e o hash pelo novo.
   */
  private static final String VERSAO_DO_HASH = "2026-11-24";
  private static final String HASH_DO_TEXTO =
      "c6207a8b03c18977bac770d26f811c8a2e7e35c5772456022241825277f66008";

  @Test
  @DisplayName("o texto só muda junto com a versão: o hash do texto é o da versão vigente")
  void textoFixadoNaVersao() throws Exception {
    byte[] resumo = java.security.MessageDigest.getInstance("SHA-256")
        .digest(todoOTexto().getBytes(java.nio.charset.StandardCharsets.UTF_8));

    assertThat(service.termos().versao()).isEqualTo(VERSAO_DO_HASH);
    assertThat(java.util.HexFormat.of().formatHex(resumo))
        .as("texto dos termos mudou sem mudar a versão " + VERSAO_DO_HASH)
        .isEqualTo(HASH_DO_TEXTO);
  }

  @Test
  @DisplayName("texto puro, sem HTML nem marcação, e com acento")
  void textoPuroComAcento() {
    String texto = todoOTexto();

    assertThat(texto).doesNotContain("<").doesNotContain(">").doesNotContain("**");
    assertThat(SEM_ACENTO.matcher(texto).find()).as("palavra sem acento no texto").isFalse();
  }
}
