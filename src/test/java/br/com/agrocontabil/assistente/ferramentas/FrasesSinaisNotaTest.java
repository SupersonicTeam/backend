package br.com.agrocontabil.assistente.ferramentas;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.porta.DadosProdutorPorta.Sinal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FrasesSinaisNotaTest {

  private static final OffsetDateTime LIMITE =
      OffsetDateTime.of(2026, 11, 26, 10, 0, 0, 0, ZoneOffset.ofHours(-3));

  @Test
  @DisplayName("autorizada no prazo: 'confira' e o limite com a hora; nunca 'esta errada'")
  void autorizadaNoPrazo() {
    String texto = FrasesSinaisNota.texto(116, null, List.of(new Sinal("PRODUTO_PARECE_SERVICO",
        "CFOP_GENERICO", "AUTORIZADA_NO_PRAZO", 1, "Frete", "6949", LIMITE, null, null, null, null,
        null)));

    assertThat(texto).isEqualTo("Na nota 116, o item 'Frete' saiu com CFOP 6.949 (outra saída não"
        + " especificada) e parece um serviço. Serviço normalmente não sai em nota de produto:"
        + " confira com seu contador. A nota já foi autorizada: se for preciso, dá para"
        + " cancelá-la até 26/11/2026, às 10:00 (horário de Brasília).");
    assertThat(texto.toLowerCase(Locale.ROOT)).doesNotContain("errad");
  }

  @Test
  @DisplayName("autorizada fora do prazo nao manda 'antes de emitir'; serie quando o numero repete")
  void foraDoPrazoESerie() {
    assertThat(FrasesSinaisNota.texto(116, 2, List.of(new Sinal("PRODUTO_PARECE_SERVICO",
        "CFOP_GENERICO", "AUTORIZADA_FORA_DO_PRAZO", 1, "Frete", "5949", null, null, null, null,
        null, null))))
        .startsWith("Na nota 116 (série 2), o item 'Frete'")
        .doesNotContain("antes de emitir")
        .endsWith("o prazo de 30 dias para cancelar passou: veja com seu contador como corrigir.");
  }

  @Test
  @DisplayName("NCM fora da referencia; descricao sem quebra de linha e cortada por code point")
  void outros() {
    assertThat(FrasesSinaisNota.texto(9, null, List.of(new Sinal("PRODUTO_PARECE_SERVICO",
        "NCM_FORA_DA_REFERENCIA", "ANTES_DE_EMITIR", 1, "Aluguel\n\nde trator", "5101", null, null,
        null, null, null, null))))
        .contains("o item 'Aluguel de trator' saiu com um NCM que não está na tabela")
        .endsWith("antes de emitir.");
    String emoji = "a".repeat(59) + "\uD83D\uDE9C" + "b".repeat(10);
    assertThat(FrasesSinaisNota.curta(emoji)).endsWith("\uD83D\uDE9C...");
    assertThat(FrasesSinaisNota.texto(9, null, List.of())).isEmpty();
  }

  @Test
  @DisplayName("#276 PR 2: valor fora do padrao e duplicidade, com os valores do dado")
  void valorEDuplicidade() {
    assertThat(FrasesSinaisNota.texto(116, null, List.of(new Sinal("VALOR_UNITARIO_FORA_PADRAO",
        "ACIMA_DO_PADRAO", "ANTES_DE_EMITIR", 1, "Milho em grao", "5101", null, "KG",
        new java.math.BigDecimal("1450.00"), new java.math.BigDecimal("1.45"), null, null))))
        .isEqualTo("Na nota 116, o preço do item 'Milho em grao' (R$ 1.450,00 por KG) está bem"
            + " acima do que você costuma cobrar (perto de R$ 1,45 por KG). Confira o valor e a"
            + " unidade antes de emitir.");
    assertThat(FrasesSinaisNota.texto(116, 2, List.of(new Sinal("POSSIVEL_DUPLICIDADE",
        "MESMA_VENDA", "ANTES_DE_EMITIR", null, null, null, null, null, null, null, 116L, 1))))
        .isEqualTo("A nota 116 (série 2) tem o mesmo destinatário, valor e itens da nota 116"
            + " (série 1), do mesmo dia. Confira se não é a mesma venda emitida duas vezes.");
    assertThat(FrasesSinaisNota.texto(9, null, List.of(new Sinal("VALOR_UNITARIO_FORA_PADRAO",
        "ABAIXO_DO_PADRAO", "ANTES_DE_EMITIR", 1, "Ovo", "5101", null, "un\n",
        new java.math.BigDecimal("0.0010"), new java.math.BigDecimal("0.0040"), null, null))))
        .contains("(R$ 0,001 por un) está bem abaixo")
        .contains("(perto de R$ 0,004 por un)");
  }

  @Test
  @DisplayName("ICMS_A_DEFINIR: o item, o padrao do sistema e o contador, antes de emitir")
  void icmsADefinir() {
    String texto = FrasesSinaisNota.texto(42, null, List.of(new Sinal("ICMS_A_DEFINIR",
        "SEM_CST_ICMS", "ANTES_DE_EMITIR", 1, "Touro Nelore PO", "6101", null, null, null, null,
        null, null)));

    assertThat(texto).isEqualTo("Na nota 42, o item 'Touro Nelore PO' está sem o CST do ICMS. O"
        + " sistema só preenche sozinho na venda dentro do estado da sua produção (CFOP 5.101) a"
        + " quem tem inscrição estadual: combine o CST com seu contador antes de emitir, senão a"
        + " emissão é barrada.");
  }

  @Test
  @DisplayName("ICMS_PADRAO_A_CONFIRMAR: o 51 do padrao, o que foi confirmado e o contador")
  void icmsPadraoAConfirmar() {
    assertThat(FrasesSinaisNota.texto(7, null, List.of(new Sinal("ICMS_PADRAO_A_CONFIRMAR",
        "PADRAO_NAO_CONFIRMADO", "ANTES_DE_EMITIR", 1, "Milho em grao", "5101", null, null, null,
        null, null, null))))
        .isEqualTo("Na nota 7, o item 'Milho em grao' vai sair com o ICMS diferido (CST 51), que o"
            + " sistema aplica por padrão na venda dentro do estado da sua produção. Esse"
            + " tratamento só foi confirmado para a soja em grão em MT: confira com seu contador o"
            + " ICMS deste produto no seu estado antes de emitir.");
  }

  @Test
  @DisplayName("CCLASSTRIB_NAO_ADMITIDO: o codigo nao vale para o NCM, corrigir antes de emitir (#389)")
  void cClassTribNaoAdmitido() {
    assertThat(FrasesSinaisNota.texto(7, null, List.of(new Sinal("CCLASSTRIB_NAO_ADMITIDO",
        "CODIGO_NAO_VALE_PARA_O_NCM", "ANTES_DE_EMITIR", 1, "Soja em grao", "5101", null, null,
        null, null, null, null))))
        .isEqualTo("Na nota 7, o item 'Soja em grao' tem um cClassTrib que não vale para o NCM"
            + " dele, então o imposto mostrado não vale na emissão: corrija o código (ou retire-o"
            + " do item) antes de emitir, senão a emissão é barrada.");
  }
}
