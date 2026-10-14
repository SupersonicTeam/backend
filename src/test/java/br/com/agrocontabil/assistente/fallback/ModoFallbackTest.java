package br.com.agrocontabil.assistente.fallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.PerguntaInterrompidaException;
import br.com.agrocontabil.assistente.ResultadoRota;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Modo fallback sem modelo (issue #146): intenção por palavras-chave, atalhos e trechos. */
@ExtendWith(MockitoExtension.class)
class ModoFallbackTest {

  private static final LocalDate HOJE = LocalDate.of(2026, 10, 14);

  @Mock private BuscaTextualLegislacaoService buscaTextual;

  private ModoFallback fallback;
  private ContextoAssistente contexto;

  @BeforeEach
  void setUp() {
    AssistenteProperties propriedades = new AssistenteProperties();
    propriedades.getFallback().setTamanhoMaximoTrecho(100);
    fallback = new ModoFallback(buscaTextual, propriedades);
    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource(delimiter = '|', value = {
      "Quanto comprei de fertilizante no semestre?|DADOS",
      "Quais guias EU tenho para pagar?|DADOS",
      "Qual a redução de alíquota para insumos?|LEGISLACAO",
      "O que diz a LC 214 sobre o produtor rural?|LEGISLACAO",
      "Os fertilizantes que eu comprei têm direito a redução?|MISTO",
      "Qual a capital da Franca?|LEGISLACAO"
  })
  @DisplayName("#146: intencao por palavras-chave, sem acento e sem diferenciar maiusculas")
  void classificaPorPalavrasChave(String pergunta, Intencao esperada) {
    assertThat(fallback.classificar(pergunta)).isEqualTo(esperada);
  }

  @Test
  @DisplayName("#146: atalhos pelas telas mencionadas; nenhuma mencionada, todas")
  void atalhos() {
    assertThat(ModoFallback.atalhos("Quanto de crédito eu tenho?"))
        .containsExactly(TelaAtalho.CREDITOS);
    assertThat(ModoFallback.atalhos("Minhas vendas e o saldo da apuração"))
        .containsExactly(TelaAtalho.NOTAS, TelaAtalho.APURACAO);
    assertThat(ModoFallback.atalhos("Quando vence a guia do FUNRURAL?"))
        .containsExactly(TelaAtalho.CALENDARIO);
    assertThat(ModoFallback.atalhos("Me ajuda?")).containsExactly(TelaAtalho.values());
  }

  @Test
  @DisplayName("#146: parte de dados e texto fixo, sem digito, com atalhos")
  void parteDados() {
    ResultadoRota r = fallback.parteDados("Quanto comprei de insumo?");

    assertThat(r.texto()).isEqualTo(ModoFallback.SEM_MODELO_DADOS).doesNotContainPattern("\\d");
    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.MODO_FALLBACK);
    assertThat(r.atalhos()).containsExactly(TelaAtalho.NOTAS);
    assertThat(r.citacoes()).isEmpty();
    verifyNoInteractions(buscaTextual);
  }

  @Test
  @DisplayName("#146: parte de lei: trechos verbatim citados, longos cortados com reticencias")
  void parteLeiResumeTrechoLongo() {
    String longo = "Art. 138. " + "Ficam reduzidas as aliquotas dos insumos agropecuarios. ".repeat(5);
    when(buscaTextual.buscar(anyString(), eq(HOJE), anyInt())).thenReturn(List.of(
        new TrechoRecuperado("T1", "id-1", "LC 214/2025", "art. 138", "url", longo, 0.3)));

    ResultadoRota r = fallback.parteLei("reducao insumos", contexto);

    assertThat(r.texto()).startsWith(ModoFallback.SEM_MODELO_LEI)
        .contains("- LC 214/2025, art. 138: Art. 138. Ficam reduzidas")
        .endsWith("...");
    // O trecho inteiro segue na citacao; o texto do chat e que e cortado.
    assertThat(r.citacoes()).singleElement()
        .satisfies(c -> assertThat(c.trecho()).isEqualTo(longo));
    assertThat(contexto.trechosCitados()).containsExactly("id-1");
  }

  @Test
  @DisplayName("#146: pergunta interrompida nao consulta a base")
  void parteLeiInterrompida() {
    contexto.interromper(DesfechoInteracao.CANCELADA);

    assertThatThrownBy(() -> fallback.parteLei("reducao", contexto))
        .isInstanceOf(PerguntaInterrompidaException.class);
    verifyNoInteractions(buscaTextual);
  }
}
