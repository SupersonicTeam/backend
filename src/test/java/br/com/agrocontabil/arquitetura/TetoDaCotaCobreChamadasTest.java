package br.com.agrocontabil.arquitetura;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.RotaDados;
import br.com.agrocontabil.assistente.RotaLegislacao;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.orquestracao.LlmClassificadorIntencao;
import br.com.agrocontabil.config.AssistenteProperties;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

/**
 * A reserva da cota semanal (#166) só é um teto de verdade se contar todas as chamadas que uma
 * pergunta pode fazer ao modelo. {@link AssistenteProperties.Cota#tetoDaPergunta} soma três —
 * classificação, escolha das funções de dados e lei, cada uma com o próprio teto de saída
 * ({@code app.assistente.tetos}, #270). Este teste quebra se surgir outro ponto que chame o modelo
 * (ou o mesmo ponto chamar duas vezes), ou se alguém chamar o {@link ChatModel} por fora da porta
 * {@link ModeloLinguagem}: nos dois casos a pergunta passaria a consumir mais do que o reservado.
 * Quem acrescentar uma chamada revê {@link AssistenteProperties.Cota#CHAMADAS_POR_PERGUNTA}, a
 * fórmula e este teste.
 *
 * <p>Desde a #499 toda chamada passa por {@link ContextoAssistente#chamar}, o ponto único que
 * confere a reserva, avisa o streaming (o comentário que percebe o cliente que saiu) e guarda a
 * estimativa cobrada no cancelamento: as chamadas são contadas nele, e só ele chama a porta.
 */
class TetoDaCotaCobreChamadasTest {

  private static JavaClasses producao;

  @BeforeAll
  static void importar() {
    producao = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("br.com.agrocontabil");
  }

  @Test
  @DisplayName("#166/#270/#499: exatamente as 3 chamadas da formula, uma por classe")
  void soAsTresChamadasDaFormula() {
    List<JavaMethodCall> chamadas = producao.stream()
        .flatMap(c -> c.getMethodCallsFromSelf().stream())
        .filter(c -> c.getTargetOwner().isEquivalentTo(ContextoAssistente.class)
            && c.getName().equals("chamar"))
        .toList();

    Map<String, Long> porClasse = chamadas.stream().collect(Collectors.groupingBy(
        c -> c.getOriginOwner().getName(), Collectors.counting()));

    assertThat(porClasse).containsOnly(
        Map.entry(LlmClassificadorIntencao.class.getName(), 1L),
        Map.entry(RotaDados.class.getName(), 1L),
        Map.entry(RotaLegislacao.class.getName(), 1L));
    assertThat(chamadas).hasSize(AssistenteProperties.Cota.CHAMADAS_POR_PERGUNTA);
  }

  @Test
  @DisplayName("#499: so o ContextoAssistente.chamar chama a porta do modelo, uma vez")
  void portaSoPeloContexto() {
    List<JavaMethodCall> chamadas = producao.stream()
        .flatMap(c -> c.getMethodCallsFromSelf().stream())
        .filter(c -> c.getTargetOwner().isEquivalentTo(ModeloLinguagem.class)
            && c.getName().equals("chamar"))
        .toList();

    assertThat(chamadas).singleElement().satisfies(c -> {
      assertThat(c.getOriginOwner().getName()).isEqualTo(ContextoAssistente.class.getName());
      assertThat(c.getOrigin().getName()).isEqualTo("chamar");
    });
  }

  @Test
  @DisplayName("#166: ninguem chama o ChatModel por fora do ModeloLinguagem")
  void chatModelSoPelaPorta() {
    List<String> origens = producao.stream()
        .flatMap(c -> c.getMethodCallsFromSelf().stream())
        .filter(c -> c.getTargetOwner().isAssignableTo(ChatModel.class)
            && (c.getName().equals("call") || c.getName().equals("stream")))
        .map(c -> c.getOriginOwner().getName())
        .distinct()
        .toList();

    assertThat(origens).containsOnly(ModeloLinguagem.class.getName());
  }
}
