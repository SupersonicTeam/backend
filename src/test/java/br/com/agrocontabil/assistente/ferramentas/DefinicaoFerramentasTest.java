package br.com.agrocontabil.assistente.ferramentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import br.com.agrocontabil.service.ApuracaoService;
import br.com.agrocontabil.service.ConsultaDadosProdutorService;
import br.com.agrocontabil.service.CreditoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * O que o modelo enxerga das funções (issue #143): o isolamento vale pelo desenho — nenhuma função
 * aceita produtor, então nenhum prompt consegue pedir dados de outro. Se alguém acrescentar um
 * parâmetro de produtor/usuário/conta numa função nova, este teste quebra.
 */
class DefinicaoFerramentasTest {

  private final List<ToolDefinition> definicoes = Arrays.stream(ToolCallbacks.from(
          new FerramentasDadosProdutor(mock(ConsultaDadosProdutorService.class),
              mock(ApuracaoService.class), mock(CreditoService.class))))
      .map(callback -> callback.getToolDefinition())
      .toList();

  @Test
  @DisplayName("As cinco funcoes da issue #142 estao expostas")
  void cincoFuncoes() {
    assertThat(definicoes).extracting(ToolDefinition::name).containsExactlyInAnyOrder(
        "totalComprasPorInsumo", "consultarApuracao", "consultarCreditos", "listarNotas",
        "proximosVencimentos");
  }

  @Test
  @DisplayName("Nenhum parametro visivel ao modelo identifica produtor, usuario, conta ou id")
  void nenhumParametroDeIdentidade() throws Exception {
    for (ToolDefinition definicao : definicoes) {
      JsonNode propriedades = new ObjectMapper().readTree(definicao.inputSchema()).path("properties");
      List<String> nomes = new ArrayList<>();
      propriedades.fieldNames().forEachRemaining(nome -> nomes.add(nome.toLowerCase(Locale.ROOT)));

      assertThat(nomes).as("parametros de %s", definicao.name()).isNotEmpty()
          .noneMatch(nome -> nome.contains("produtor") || nome.contains("usuario")
              || nome.contains("contador") || nome.contains("cpf") || nome.contains("cnpj")
              || nome.equals("id") || nome.endsWith("id") || nome.contains("uuid")
              || nome.contains("context"));
    }
  }

  @Test
  @DisplayName("Toda funcao tem descricao que diz quando usa-la (faz parte do prompt)")
  void descricoesPresentes() {
    assertThat(definicoes).allSatisfy(d -> assertThat(d.description()).hasSizeGreaterThan(80));
  }
}
