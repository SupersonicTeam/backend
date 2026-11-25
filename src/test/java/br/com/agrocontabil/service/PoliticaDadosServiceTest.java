package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.LgpdProperties;
import br.com.agrocontabil.dto.OperadorExternoResponse;
import br.com.agrocontabil.dto.PoliticaDadosResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Política de dados (#148, #498): a Resend e a Anthropic citadas pelo nome, com a hipótese do
 * art. 33, a retenção e o que o titular pode pedir. O que o provedor não confirma por escrito
 * fica escrito como pendente.
 */
@DisplayName("PoliticaDadosService: política com os operadores de fora do Brasil (#498)")
class PoliticaDadosServiceTest {

  private static final String ENCARREGADO = "contato@gwnext.com.br";

  /** Radicais que em português levam acento: o texto da política chega à tela do titular. */
  private static final Pattern SEM_ACENTO = Pattern.compile(
      "(?iu)\\b(identificacao|obrigatorio|obrigatorios|execucao|informacao|transferencia|"
          + "retencao|verificacao|politica|servico|endereco|exclusao|seguranca|violacao|"
          + "referencia|aplicacao|escritorio|inscricao|apuracoes|tributarios)\\b");

  private final PoliticaDadosService service =
      new PoliticaDadosService(new LgpdProperties("GWNext", ENCARREGADO, "2026-11-24"));

  private OperadorExternoResponse operador(String nome) {
    return service.politica().operadoresExternos().stream()
        .filter(o -> o.nome().equals(nome)).findFirst().orElseThrow();
  }

  @Test
  @DisplayName("versão, controlador e encarregado vêm da configuração")
  void cabecalho() {
    PoliticaDadosResponse politica = service.politica();

    assertThat(politica.versao()).isEqualTo("2026-11-24");
    assertThat(politica.controlador()).isEqualTo("GWNext");
    assertThat(politica.contatoEncarregado()).isEqualTo(ENCARREGADO);
    assertThat(politica.comoExercer()).contains("/api/usuarios/{id}/dados").contains(ENCARREGADO);
  }

  @Test
  @DisplayName("cita a Resend e a Anthropic pelo nome, nesta ordem")
  void operadoresPeloNome() {
    assertThat(service.politica().operadoresExternos())
        .extracting(OperadorExternoResponse::nome)
        .containsExactly("Resend", "Anthropic");
  }

  @Test
  @DisplayName("cada operador traz finalidade, local, dados, hipótese do art. 33, retenção e direitos")
  void operadorCompleto() {
    for (OperadorExternoResponse o : service.politica().operadoresExternos()) {
      assertThat(o.servico()).as(o.nome()).isNotBlank();
      assertThat(o.localizacao()).as(o.nome()).isNotBlank();
      assertThat(o.finalidade()).as(o.nome()).isNotBlank();
      assertThat(o.dadosEnviados()).as(o.nome()).isNotEmpty();
      assertThat(o.dadosNaoEnviados()).as(o.nome()).isNotEmpty();
      assertThat(o.baseTransferencia()).as(o.nome())
          .contains("se apoia no art. 33, IX").contains("art. 7º, V")
          .contains("Resolução CD/ANPD nº 19/2024");
      assertThat(o.retencao()).as(o.nome()).isNotBlank();
      assertThat(o.comoExercerDireitos()).as(o.nome()).contains(ENCARREGADO)
          .contains("não alcança");
    }
  }

  @Test
  @DisplayName("Resend: 30 dias nos EUA, sem as cláusulas-padrão da ANPD, e-mail isolado só pelo suporte")
  void resend() {
    OperadorExternoResponse resend = operador("Resend");

    assertThat(resend.localizacao()).contains("Estados Unidos");
    assertThat(resend.retencao()).contains("30 dias").contains("90 dias");
    assertThat(resend.baseTransferencia()).contains("não assina");
    assertThat(resend.dadosEnviados()).anyMatch(d -> d.contains("endereço de e-mail"));
    assertThat(resend.dadosNaoEnviados()).anyMatch(d -> d.contains("Nome, CPF/CNPJ e telefone"));
    assertThat(resend.comoExercerDireitos()).contains("suporte");
  }

  @Test
  @DisplayName("Anthropic: 30 dias conferidos no Console, 2 anos se sinalizado, sem prometer retenção zero")
  void anthropic() {
    OperadorExternoResponse anthropic = operador("Anthropic");

    assertThat(anthropic.retencao())
        .contains("30 dias").contains("segurança e proteção").contains("24/11/2026")
        .contains("a retenção zero não está ativa").contains("2 anos")
        .contains("não promete retenção zero").doesNotContain("Em verificação");
    assertThat(anthropic.localizacao())
        .contains("Fora do Brasil").contains("roteamento global").contains("Estados Unidos");
    assertThat(anthropic.dadosEnviados())
        .anyMatch(d -> d.contains("continuidade da conversa") && d.contains("motivo de uma devolução"))
        .anyMatch(d -> d.contains("não retira um nome escrito por extenso"));
    assertThat(anthropic.dadosNaoEnviados())
        .anyMatch(d -> d.contains("Nome, documento, contato e endereço"));
    assertThat(anthropic.comoExercerDireitos()).contains("pergunta isolada");
  }

  @Test
  @DisplayName("as listas gerais citam os dois operadores e a exclusão avisa o que ela não alcança")
  void listasGerais() {
    PoliticaDadosResponse politica = service.politica();

    assertThat(String.join(" ", politica.compartilhamento()))
        .contains("Resend").contains("Anthropic");
    // A retenção de cada operador é uma redação só: a lista e o operador não podem divergir.
    assertThat(politica.retencao())
        .anyMatch(r -> r.startsWith("Resend: " + PoliticaDadosService.RETENCAO_RESEND))
        .contains("Anthropic: " + PoliticaDadosService.RETENCAO_ANTHROPIC);
    assertThat(operador("Resend").retencao()).isEqualTo(PoliticaDadosService.RETENCAO_RESEND);
    assertThat(operador("Anthropic").retencao())
        .isEqualTo(PoliticaDadosService.RETENCAO_ANTHROPIC);
    assertThat(politica.basesLegais())
        .anyMatch(b -> b.contains("art. 33, IX") && b.contains("se apoia"));
    assertThat(politica.comoExercer()).contains("Resend").contains("Anthropic")
        .contains("não apaga o que");
  }

  @Test
  @DisplayName("texto que chega ao titular leva acento")
  void textoAcentuado() {
    PoliticaDadosResponse politica = service.politica();
    List<String> textos = new ArrayList<>();
    textos.addAll(politica.dadosColetados());
    textos.addAll(politica.finalidades());
    textos.addAll(politica.basesLegais());
    textos.addAll(politica.compartilhamento());
    textos.addAll(politica.retencao());
    textos.addAll(politica.direitos());
    textos.add(politica.comoExercer());
    for (OperadorExternoResponse o : politica.operadoresExternos()) {
      textos.add(o.servico());
      textos.add(o.localizacao());
      textos.add(o.finalidade());
      textos.addAll(o.dadosEnviados());
      textos.addAll(o.dadosNaoEnviados());
      textos.add(o.baseTransferencia());
      textos.add(o.retencao());
      textos.add(o.comoExercerDireitos());
    }

    assertThat(textos).allSatisfy(t -> assertThat(SEM_ACENTO.matcher(t).find())
        .as("palavra sem acento em: " + t).isFalse());
  }
}
