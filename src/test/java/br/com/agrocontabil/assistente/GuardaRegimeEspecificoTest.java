package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Guarda do regime específico da LC 214/2025, arts. 172 a 307 (issue #269). */
class GuardaRegimeEspecificoTest {

  private static CitacaoLegal c(String norma, String dispositivo) {
    return new CitacaoLegal(norma, dispositivo, "https://exemplo", "texto");
  }

  private static CitacaoLegal lc214(String dispositivo) {
    return c("LC 214/2025", dispositivo);
  }

  @Test
  @DisplayName("Faixa: o art. 172 abre o título dos regimes específicos e o art. 307 o fecha")
  void limitesDaFaixa() {
    assertThat(GuardaRegimeEspecifico.emRegimeEspecifico("LC 214/2025", "art. 171, § 4º")).isFalse();
    assertThat(GuardaRegimeEspecifico.emRegimeEspecifico("LC 214/2025", "art. 172")).isTrue();
    assertThat(GuardaRegimeEspecifico.emRegimeEspecifico("LC 214/2025", "art. 307, § 1º")).isTrue();
    assertThat(GuardaRegimeEspecifico.emRegimeEspecifico("LC 214/2025", "art. 308")).isFalse();
    // O Imposto Seletivo (art. 409) e a regra geral ficam fora.
    assertThat(GuardaRegimeEspecifico.emRegimeEspecifico("LC 214/2025", "art. 409, § 1º")).isFalse();
    assertThat(GuardaRegimeEspecifico.emRegimeEspecifico("LC 214/2025", "art. 12")).isFalse();
  }

  @Test
  @DisplayName("Outra norma, anexo e dispositivo sem artigo nunca são regime específico")
  void foraDaNormaOuSemArtigo() {
    assertThat(GuardaRegimeEspecifico.emRegimeEspecifico("LC 227/2026", "art. 200")).isFalse();
    assertThat(GuardaRegimeEspecifico.emRegimeEspecifico("EC 132/2023", "art. 250")).isFalse();
    assertThat(GuardaRegimeEspecifico.emRegimeEspecifico("LC 214/2025", "Anexo VIII, item 200"))
        .isFalse();
    assertThat(GuardaRegimeEspecifico.emRegimeEspecifico("LC 214/2025", null)).isFalse();
  }

  @Test
  @DisplayName("Pergunta geral com citação só de combustível ou de imóvel é barrada")
  void barraPerguntaGeral() {
    assertThat(GuardaRegimeEspecifico.barra("Sobre qual valor se calcula o IBS e a CBS?",
        List.of(lc214("art. 173, § 2º"), lc214("art. 173")))).isTrue();
    assertThat(GuardaRegimeEspecifico.barra(
        "Quando se considera que aconteceu o fato gerador do IBS e da CBS numa venda?",
        List.of(lc214("art. 254, inciso I")))).isTrue();
  }

  @Test
  @DisplayName("Pergunta que cita o setor do capítulo citado passa (cooperativa, imóvel, combustível)")
  void perguntaComSetorPassa() {
    assertThat(GuardaRegimeEspecifico.barra(
        "Uma cooperativa de produtores pode ter alíquota zero de IBS e CBS?",
        List.of(lc214("art. 271, § 1º, inciso II")))).isFalse();
    assertThat(GuardaRegimeEspecifico.barra("Quando surge o fato gerador na venda de um imóvel?",
        List.of(lc214("art. 254, inciso I")))).isFalse();
    assertThat(GuardaRegimeEspecifico.barra("O IBS incide sobre o COMBUSTÍVEL do trator?",
        List.of(lc214("art. 172")))).isFalse();
  }

  @Test
  @DisplayName("Palavras do produtor rural para o mesmo setor também contam (fazenda, terra, "
      + "arrendamento, CPR, financiamento)")
  void vocabularioDoProdutorRural() {
    assertThat(GuardaRegimeEspecifico.barra(
        "Quando surge o fato gerador na venda da minha fazenda?",
        List.of(lc214("art. 254, inciso I")))).isFalse();
    assertThat(GuardaRegimeEspecifico.barra("O arrendamento da terra paga IBS e CBS?",
        List.of(lc214("art. 252")))).isFalse();
    assertThat(GuardaRegimeEspecifico.barra("A CPR paga IBS e CBS?",
        List.of(lc214("art. 200")))).isFalse();
    assertThat(GuardaRegimeEspecifico.barra("Os juros do financiamento do trator pagam IBS?",
        List.of(lc214("art. 200")))).isFalse();
    // "rural" sozinho não é setor: pergunta geral do produtor rural continua sendo geral.
    assertThat(GuardaRegimeEspecifico.barra("Como o produtor rural calcula o IBS e a CBS?",
        List.of(lc214("art. 254")))).isTrue();
  }

  @Test
  @DisplayName("Basta uma citação fora da faixa (regra geral, outra lei, Imposto Seletivo) para passar")
  void citacaoMistaPassa() {
    assertThat(GuardaRegimeEspecifico.barra("Sobre qual valor se calcula o IBS e a CBS?",
        List.of(lc214("art. 12"), lc214("art. 173")))).isFalse();
    assertThat(GuardaRegimeEspecifico.barra("O que é o Imposto Seletivo?",
        List.of(lc214("art. 409, § 2º")))).isFalse();
    assertThat(GuardaRegimeEspecifico.barra("Qual a contribuição ao Senar?",
        List.of(c("Lei 9.528/1997", "art. 6º")))).isFalse();
    assertThat(GuardaRegimeEspecifico.barra("Sobre qual valor se calcula?",
        List.of(lc214("art. 173"), c("LC 227/2026", "art. 200")))).isFalse();
  }

  @Test
  @DisplayName("Sem citação ou sem pergunta: nada a barrar")
  void semDados() {
    assertThat(GuardaRegimeEspecifico.barra("Qualquer coisa", List.of())).isFalse();
    assertThat(GuardaRegimeEspecifico.barra("Qualquer coisa", null)).isFalse();
    assertThat(GuardaRegimeEspecifico.barra(null, List.of(lc214("art. 173")))).isFalse();
  }

  @Test
  @DisplayName("Setor de outro capítulo não salva: pergunta de imóvel com citação de combustível")
  void setorDeOutroCapituloNaoSalva() {
    assertThat(GuardaRegimeEspecifico.barra("Quando surge o fato gerador na venda de um imóvel?",
        List.of(lc214("art. 173")))).isTrue();
  }

  @Test
  @DisplayName("O desfecho novo cabe na coluna da auditoria (VARCHAR(40), changeSet 0025): sem changeSet")
  void desfechoCabeNaColuna() {
    assertThat(DesfechoInteracao.REGIME_ESPECIFICO_SEM_SETOR.name().length())
        .isLessThanOrEqualTo(40);
  }
}
