package br.com.agrocontabil.arquitetura;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.RotaDados;
import br.com.agrocontabil.assistente.RotaLegislacao;
import br.com.agrocontabil.assistente.explicacao.ExplicadorResposta;
import br.com.agrocontabil.assistente.fidelidade.JuizFidelidadeLei;
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
 *
 * <p>Desde a #268 há um quarto ponto, a explicação depois da resposta de dados
 * ({@link ExplicadorResposta}), que fica <strong>fora</strong> da fórmula da pergunta: ela tem
 * reserva própria, feita só quando a explicação vai acontecer
 * ({@link AssistenteProperties.Cota#tetoDaExplicacao}, uma chamada).
 *
 * <p>Desde a #269 há um quinto, o juiz da fidelidade da lei ({@link JuizFidelidadeLei}), que roda
 * no meio da pergunta e entra na reserva dela como parcela própria, só com ele ativo
 * ({@link AssistenteProperties#tetoDaPergunta}, {@link AssistenteProperties.Cota#tetoDoJuiz}).
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
  @DisplayName("#166/#270/#499/#268: as 3 chamadas da formula e a da explicacao, uma por classe")
  void soAsTresChamadasDaFormulaEADaExplicacao() {
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
        Map.entry(RotaLegislacao.class.getName(), 1L),
        Map.entry(ExplicadorResposta.class.getName(), 1L),
        Map.entry(JuizFidelidadeLei.class.getName(), 1L));
    // A da explicacao (#268) tem reserva propria e a do juiz (#269) e parcela propria da reserva
    // da pergunta, so com ele ativo: as outras sao as da formula.
    assertThat(chamadas.stream()
        .filter(c -> !c.getOriginOwner().isEquivalentTo(ExplicadorResposta.class)
            && !c.getOriginOwner().isEquivalentTo(JuizFidelidadeLei.class)))
        .hasSize(AssistenteProperties.Cota.CHAMADAS_POR_PERGUNTA);
  }

  @Test
  @DisplayName("#269: com o juiz da lei ativo, a reserva da pergunta ganha a chamada dele: "
      + "31.744 + 8.192 + 128 x 4 = 40.448 com os padroes; desligado, nada muda")
  void reservaComOJuizDaLei() {
    AssistenteProperties propriedades = new AssistenteProperties();
    assertThat(propriedades.tetoDaPergunta(1.0)).isEqualTo(31_744);
    // So o juiz, sem a camada 1: ele nao roda e a reserva nao muda.
    propriedades.getFidelidadeLei().getJuiz().setEnabled(true);
    assertThat(propriedades.tetoDaPergunta(1.0)).isEqualTo(31_744);
    // Camada 1 sem o juiz: tambem nao muda (a camada 1 nao chama o modelo a mais).
    propriedades.getFidelidadeLei().getJuiz().setEnabled(false);
    propriedades.getFidelidadeLei().setEnabled(true);
    assertThat(propriedades.tetoDaPergunta(1.0)).isEqualTo(31_744);
    propriedades.getFidelidadeLei().getJuiz().setEnabled(true);
    assertThat(propriedades.tetoDaPergunta(1.0)).isEqualTo(40_448);
    assertThat(propriedades.getCota().tetoDoJuiz(propriedades.getTetos().getJuiz(), 2.0))
        .isEqualTo(17_408);
    // Configuracao justificativa: a parcela do juiz usa o teto dele (512): 8.192 + 512 x 4 = 10.240.
    propriedades.getFidelidadeLei().getJuiz()
        .setConfiguracao(AssistenteProperties.FidelidadeLei.Juiz.Configuracao.JUSTIFICATIVA);
    assertThat(propriedades.tetoDaPergunta(1.0)).isEqualTo(41_984);
  }

  @Test
  @DisplayName("#268/#511: a reserva da explicacao cobre uma chamada com o teto proprio: "
      + "8.192 + 256 x 4 = 9.216 com os padroes")
  void reservaDaExplicacao() {
    AssistenteProperties propriedades = new AssistenteProperties();
    assertThat(propriedades.getCota().tetoDaExplicacao(propriedades.getTetos(), 1.0))
        .isEqualTo(9_216);
    // O fator do modelo vale como nas outras chamadas.
    assertThat(propriedades.getCota().tetoDaExplicacao(propriedades.getTetos(), 2.0))
        .isEqualTo(18_432);
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
