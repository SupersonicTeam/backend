package br.com.agrocontabil.assistente.ferramentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import br.com.agrocontabil.assistente.porta.DadosProdutorPorta;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * O que o modelo enxerga das funções (issue #143): o isolamento vale pelo desenho — nenhuma função
 * aceita produtor, então nenhum prompt consegue pedir dados de outro. Se alguém acrescentar um
 * parâmetro de produtor/usuário/conta numa função nova, este teste quebra.
 */
class DefinicaoFerramentasTest {

  private final List<ToolDefinition> definicoes = Arrays.stream(ToolCallbacks.from(
          new FerramentasDadosProdutor(mock(DadosProdutorPorta.class))))
      .map(callback -> callback.getToolDefinition())
      .toList();

  @Test
  @DisplayName("As cinco da #142, alertas (#140), retencoes (#145) e as guias da #274")
  void cincoFuncoes() {
    assertThat(definicoes).extracting(ToolDefinition::name).containsExactlyInAnyOrder(
        "totalComprasPorInsumo", "consultarApuracao", "consultarCreditos", "listarNotas",
        "proximosVencimentos", "listarAlertas", "consultarRetencoes", "guiasEmAtraso",
        "vencimentosDoMes", "consultarNota", "totalVendasPorProduto");
  }

  @Test
  @DisplayName("Nenhum parametro visivel ao modelo identifica produtor, usuario, conta ou id")
  void nenhumParametroDeIdentidade() throws Exception {
    for (ToolDefinition definicao : definicoes) {
      JsonNode propriedades = new ObjectMapper().readTree(definicao.inputSchema()).path("properties");
      List<String> nomes = new ArrayList<>();
      propriedades.fieldNames().forEachRemaining(nome -> nomes.add(nome.toLowerCase(Locale.ROOT)));

      // listarAlertas (#140) e guiasEmAtraso (#274) nao tem parametro: o schema existe, so nao ha
      // propriedades.
      if (!Set.of("listarAlertas", "guiasEmAtraso").contains(definicao.name())) {
        assertThat(nomes).as("parametros de %s", definicao.name()).isNotEmpty();
      }
      assertThat(nomes).as("parametros de %s", definicao.name())
          .noneMatch(nome -> nome.contains("produtor") || nome.contains("usuario")
              || nome.contains("contador") || nome.contains("cpf") || nome.contains("cnpj")
              || nome.equals("id") || nome.endsWith("id") || nome.contains("uuid")
              || nome.contains("context"));
    }
  }

  @Test
  @DisplayName("#144: todo resultado de funcao passa pelo DataSanitizer antes de ir ao modelo")
  void todaFuncaoUsaConversorSanitizado() {
    List<Method> funcoes = Arrays.stream(FerramentasDadosProdutor.class.getDeclaredMethods())
        .filter(m -> m.isAnnotationPresent(Tool.class))
        .toList();

    assertThat(funcoes).hasSize(11).allSatisfy(m ->
        assertThat(m.getAnnotation(Tool.class).resultConverter())
            .as("resultConverter de %s", m.getName())
            .isEqualTo(ResultadoSanitizadoConverter.class));
  }

  @Test
  @DisplayName("#144: minimizacao por construcao - nenhum DTO ao modelo tem campo de dado pessoal")
  void dtosSemCamposPessoais() {
    List<Class<?>> dtos = Arrays.stream(RespostasFerramentas.class.getDeclaredClasses())
        .filter(Class::isRecord)
        .toList();
    assertThat(dtos).isNotEmpty();
    for (Class<?> dto : dtos) {
      for (RecordComponent campo : dto.getRecordComponents()) {
        assertThat(campo.getName().toLowerCase(Locale.ROOT))
            .as("%s.%s", dto.getSimpleName(), campo.getName())
            .doesNotContain("nome", "cpf", "cnpj", "endereco", "logradouro", "email",
                "telefone", "chave", "observacoes", "destinatario", "emitente", "produtor");
      }
    }
  }

  @Test
  @DisplayName("Toda funcao tem descricao que diz quando usa-la (faz parte do prompt)")
  void descricoesPresentes() {
    assertThat(definicoes).allSatisfy(d -> assertThat(d.description()).hasSizeGreaterThan(80));
  }
}
