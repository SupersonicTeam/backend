package br.com.agrocontabil.assistente.continuidade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.Produto;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.TratamentoConsultado;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** A resolução de produto sobre a porta do tratamento (#591). */
@ExtendWith(MockitoExtension.class)
class ResolucaoProdutoPelaTabelaTest {

  private static final LocalDate DIA = LocalDate.of(2026, 11, 10);

  @Mock private TratamentoProdutoPorta porta;

  @Test
  @DisplayName("Código de NCM resolve pela posição, sem consultar a porta")
  void codigoDeNcm() {
    ResolucaoProduto resolucao = new ResolucaoProdutoPelaTabela(porta);

    assertThat(resolucao.familias("1201", DIA)).containsExactly("ncm:1201");
    assertThat(resolucao.familias("1201.90.00", DIA))
        .containsExactly("ncm:1201");
    assertThat(resolucao.familias("12019000", DIA)).containsExactly("ncm:1201");
    verify(porta, never()).consultar(anyString(), any());
  }

  @Test
  @DisplayName("Nome: as posições dos NCMs conhecidos que a tabela achou")
  void nome() {
    when(porta.consultar("soja", DIA)).thenReturn(new TratamentoConsultado("soja", List.of(
        produto("12011000", true), produto("12019000", true), produto("15071000", true)), "v"));

    assertThat(new ResolucaoProdutoPelaTabela(porta).familias("soja", DIA))
        .containsExactlyInAnyOrder("ncm:1201", "ncm:1507");
  }

  @Test
  @DisplayName("NCM fora da referência, item de anexo sem NCM e termo vazio não resolvem")
  void naoResolve() {
    when(porta.consultar("adubo", DIA)).thenReturn(new TratamentoConsultado("adubo", List.of(
        produto(null, true), produto("99999999", false)), "v"));
    when(porta.consultar("nada", DIA)).thenReturn(new TratamentoConsultado("nada", List.of(),
        null));
    ResolucaoProduto resolucao = new ResolucaoProdutoPelaTabela(porta);

    assertThat(resolucao.familias("adubo", DIA)).isEmpty();
    assertThat(resolucao.familias("nada", DIA)).isEmpty();
    assertThat(resolucao.familias("  ", DIA)).isEmpty();
    assertThat(resolucao.familias(null, DIA)).isEmpty();
  }

  @Test
  @DisplayName("Falha da consulta não derruba a pergunta: o termo só não se resolve")
  void falhaDaPorta() {
    when(porta.consultar("soja", DIA)).thenThrow(new IllegalStateException("banco fora"));

    assertThat(new ResolucaoProdutoPelaTabela(porta).familias("soja", DIA)).isEmpty();
  }

  @Test
  @DisplayName("A resposta do termo vale por um minuto, acerto ou falha: decisão e orquestrador ouvem o mesmo")
  void respostaGuardada() {
    long[] agora = {1_000L};
    when(porta.consultar("soja", DIA))
        .thenThrow(new IllegalStateException("banco fora"))
        .thenReturn(new TratamentoConsultado("soja", List.of(produto("12019000", true)), "v"));
    ResolucaoProduto resolucao = new ResolucaoProdutoPelaTabela(porta, () -> agora[0]);

    assertThat(resolucao.familias("soja", DIA)).isEmpty();
    // A segunda pergunta da mesma requisição não vê a consulta que passou a funcionar.
    assertThat(resolucao.familias("soja", DIA)).isEmpty();
    verify(porta, times(1)).consultar("soja", DIA);
    // Vencida a validade, a tabela é consultada de novo.
    agora[0] += ResolucaoProdutoPelaTabela.VALIDADE_MS;
    assertThat(resolucao.familias("soja", DIA)).containsExactly("ncm:1201");
  }

  private static Produto produto(String ncm, boolean conhecido) {
    return new Produto(ncm, "descricao", 1, "000001", BigDecimal.ZERO, false, null, null, null,
        conhecido);
  }
}
