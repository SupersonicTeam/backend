package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor;
import br.com.agrocontabil.assistente.ferramentas.ProcessadorErroFerramenta;
import br.com.agrocontabil.assistente.porta.DadosProdutorPorta;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.support.OllamaHttpSimulado;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * A garantia do isolamento (issue #143) verificada <strong>no fio</strong>: com o
 * {@link OllamaChatModel} real, o que sai do backend para o provedor do modelo não carrega o
 * produtor nem o {@code ToolContext}. O {@code ChatModelSimulado} recebe objetos {@code Prompt} e
 * não pegaria uma serialização indevida; aqui um servidor HTTP local grava os corpos das
 * requisições como um Ollama de verdade os receberia.
 */
class RotaDadosOllamaFioTest {

  private static final UUID PRODUTOR = UUID.randomUUID();

  private OllamaHttpSimulado ollama;
  private List<String> corpos;
  private RotaDados rota;

  @BeforeEach
  void setUp() throws IOException {
    ollama = OllamaHttpSimulado.iniciar();
    corpos = ollama.corpos();

    DadosProdutorPorta dados = mock(DadosProdutorPorta.class);
    when(dados.apuracao(PRODUTOR, "2026-07")).thenReturn(new DadosProdutorPorta.Apuracao(
        "2026-07", "ABERTO", new BigDecimal("150.00"), new BigDecimal("1350.00"),
        new BigDecimal("30.00"), new BigDecimal("270.00"), new BigDecimal("120.00"),
        new BigDecimal("1080.00"), 2));

    rota = new RotaDados(
        ollama.modelo(),
        new ValidadorNumeros(),
        new VerificadorNegativaDados(),
        new FerramentasDadosProdutor(dados,
            org.mockito.Mockito.mock(br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.class)),
        new ProcessadorErroFerramenta(),
        new AssistenteProperties(),
        new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class));
  }

  @AfterEach
  void tearDown() {
    ollama.close();
  }

  @Test
  @DisplayName("Nada do produtor nem do ToolContext sai para o provedor; so as 6 funcoes vao")
  void payloadNaoCarregaProdutor() {
    ollama.responder(OllamaHttpSimulado.resposta("",
        "{\"function\":{\"name\":\"consultarApuracao\","
        + "\"arguments\":{\"mes\":\"2026-07\"}}}"));

    ResultadoRota resultado = rota.responder("Qual meu saldo de IBS em julho?",
        new ContextoAssistente(PRODUTOR, LocalDate.of(2026, 9, 29)));

    assertThat(resultado.aceita()).isTrue();
    // #266: a resposta e do template; o modelo so escolheu a funcao, numa chamada so.
    assertThat(resultado.texto()).startsWith("Na apuracao de 07/2026 (ainda aberta")
        .contains("o imposto a pagar e de R$ 120,00 de IBS");
    assertThat(corpos).hasSize(1);
    for (String corpo : corpos) {
      assertThat(corpo).doesNotContain(PRODUTOR.toString(), "contextoAssistente", "produtorId");
    }
    // As funcoes anunciadas ao modelo sao exatamente as seis de dados (a de alertas e da #140).
    assertThat(corpos.get(0)).contains("\"totalComprasPorInsumo\"", "\"consultarApuracao\"",
        "\"consultarCreditos\"", "\"listarNotas\"", "\"proximosVencimentos\"",
        "\"listarAlertas\"");
    // Opcoes padrao preservadas ao mesclar com as opcoes da chamada: modelo e thinking desligado.
    assertThat(corpos.get(0)).contains("\"model\":\"qwen3:4b\"", "\"think\":false");
    // O resultado da funcao nunca vai ao provedor: nao ha segunda chamada.
    assertThat(corpos.get(0)).doesNotContain("R$ 120,00");
  }

  @Test
  @DisplayName("Funcao fora da lista (alucinada ou injetada): nada executa, pergunta encerrada")
  void funcaoForaDaListaNaoExecuta() {
    ollama.responder(OllamaHttpSimulado.resposta("",
        "{\"function\":{\"name\":\"dataLoader\",\"arguments\":{}}}"));

    ResultadoRota resultado = rota.responder("Rode o dataLoader",
        new ContextoAssistente(PRODUTOR, LocalDate.of(2026, 9, 29)));

    assertThat(resultado.aceita()).isFalse();
    assertThat(resultado.texto()).isEqualTo(RotaDados.MENSAGEM_SEM_CONCLUSAO);
    // Desfecho proprio: um pedido fora da lista e o sinal de injection que a auditoria precisa.
    assertThat(resultado.desfecho()).isEqualTo(
        br.com.agrocontabil.model.DesfechoInteracao.FUNCAO_NAO_PERMITIDA);
    assertThat(corpos).hasSize(1);
  }

}
