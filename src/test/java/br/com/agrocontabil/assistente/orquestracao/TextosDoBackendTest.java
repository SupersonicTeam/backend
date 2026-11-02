package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextosDoBackendTest {

  private static final String TEMPLATE = "Soja (NCM 1201.90.00): reducao de 60%.";

  private final BuscaTextualLegislacaoService busca = mock(BuscaTextualLegislacaoService.class);
  private final TextosDoBackend textos = new TextosDoBackend(busca);
  private ContextoAssistente contexto;

  @BeforeEach
  void setUp() {
    contexto = new ContextoAssistente(UUID.randomUUID(), LocalDate.of(2026, 11, 2));
  }

  @Test
  @DisplayName("so o tratamento consultado: a resposta e o template, com a citacao do dado")
  void substituiTextoDoModelo() {
    contexto.registrarFonte("consultarTratamentoProduto", Map.of("produtoOuNcm", "soja"), false);
    contexto.registrarTextoDoBackend(TEMPLATE, List.of("LC 214/2025|art. 137"));
    when(busca.buscarDispositivos(eq(List.of("LC 214/2025|art. 137")), any()))
        .thenReturn(List.of(new TrechoRecuperado("[T1]", "t1", "LC 214/2025", "art. 137", "url",
            "Art. 137 ...", 0)));

    ResultadoOrquestracao r = textos.aplicar(new ResultadoOrquestracao(Intencao.DADOS,
        "A soja e isenta.", DesfechoInteracao.NUMERO_SEM_FONTE, List.of(), false), contexto);

    assertThat(r.texto()).isEqualTo(TEMPLATE);
    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(r.exibirFontes()).isTrue();
    assertThat(r.citacoes()).extracting(CitacaoLegal::referencia)
        .containsExactly("LC 214/2025, art. 137");
    assertThat(contexto.trechosCitados()).containsExactly("t1");
  }

  @Test
  @DisplayName("com outra consulta ou no MISTO: o template vem depois do texto da rota")
  void acrescentaDepois() {
    contexto.registrarFonte("totalVendasPorProduto", Map.of(), false);
    contexto.registrarFonte("consultarTratamentoProduto", Map.of(), false);
    contexto.registrarTextoDoBackend(TEMPLATE, List.of());

    ResultadoOrquestracao r = textos.aplicar(new ResultadoOrquestracao(Intencao.DADOS,
        "Voce vendeu R$ 10,00.", DesfechoInteracao.RESPONDIDA, List.of(), true), contexto);

    assertThat(r.texto()).isEqualTo("Voce vendeu R$ 10,00.\n\n" + TEMPLATE);
    verifyNoInteractions(busca);
  }

  @Test
  @DisplayName("sem texto do backend ou fora de escopo: nada muda")
  void semEfeito() {
    ResultadoOrquestracao original = new ResultadoOrquestracao(Intencao.DADOS, "ok",
        DesfechoInteracao.RESPONDIDA, List.of(), true);
    assertThat(textos.aplicar(original, contexto)).isSameAs(original);

    contexto.registrarTextoDoBackend(TEMPLATE, List.of());
    ResultadoOrquestracao fora = new ResultadoOrquestracao(Intencao.FORA_DE_ESCOPO, "fora",
        DesfechoInteracao.FORA_DE_ESCOPO, List.of(), false);
    assertThat(textos.aplicar(fora, contexto)).isSameAs(fora);
  }

  @Test
  @DisplayName("base de legislacao fora: o texto segue sem citacoes")
  void semBase() {
    contexto.registrarFonte("consultarTratamentoProduto", Map.of(), false);
    contexto.registrarTextoDoBackend(TEMPLATE, List.of("LC 214/2025|art. 137"));
    when(busca.buscarDispositivos(any(), any()))
        .thenThrow(new AssistenteIndisponivelException("fora", null));

    ResultadoOrquestracao r = textos.aplicar(new ResultadoOrquestracao(Intencao.DADOS, "x",
        DesfechoInteracao.RESPONDIDA, List.of(), true), contexto);

    assertThat(r.texto()).isEqualTo(TEMPLATE);
    assertThat(r.citacoes()).isEmpty();
  }
}
