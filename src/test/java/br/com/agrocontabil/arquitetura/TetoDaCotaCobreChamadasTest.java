package br.com.agrocontabil.arquitetura;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.RotaDados;
import br.com.agrocontabil.assistente.RotaLegislacao;
import br.com.agrocontabil.assistente.continuidade.ReconferenciaLei;
import br.com.agrocontabil.assistente.continuidade.ReescritaContinuidade;
import br.com.agrocontabil.assistente.explicacao.ExplicadorResposta;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.orquestracao.LlmClassificadorIntencao;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.config.CaminhoCota;
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
  @DisplayName("#166/#270/#499/#268: os 5 pontos dos caminhos e o da explicacao, um por classe")
  void soOsPontosDosCaminhosEODaExplicacao() {
    List<JavaMethodCall> chamadas = producao.stream()
        .flatMap(c -> c.getMethodCallsFromSelf().stream())
        .filter(c -> c.getTargetOwner().isEquivalentTo(ContextoAssistente.class)
            && c.getName().equals("chamar"))
        .toList();

    Map<String, Long> porClasse = chamadas.stream().collect(Collectors.groupingBy(
        c -> c.getOriginOwner().getName(), Collectors.counting()));

    // As 3 da pergunta comum (CHAMADAS_POR_PERGUNTA) e as 2 da continuidade (#499): a reescrita
    // e a reconferencia de lei. Cada caminho do CaminhoCota soma as que pode fazer; o com
    // reescrita e o mais caro e e o que a subida confere. A da explicacao (#268) fica fora dos
    // caminhos: tem reserva propria, feita depois do acerto da pergunta.
    assertThat(porClasse).containsOnly(
        Map.entry(LlmClassificadorIntencao.class.getName(), 1L),
        Map.entry(RotaDados.class.getName(), 1L),
        Map.entry(RotaLegislacao.class.getName(), 1L),
        Map.entry(ReescritaContinuidade.class.getName(), 1L),
        Map.entry(ReconferenciaLei.class.getName(), 1L),
        Map.entry(ExplicadorResposta.class.getName(), 1L));
    assertThat(AssistenteProperties.Cota.CHAMADAS_POR_PERGUNTA)
        .isEqualTo(CaminhoCota.NORMAL.chamadas());
    assertThat(CaminhoCota.REESCRITA.chamadas()).isEqualTo(4);
  }

  @Test
  @DisplayName("#499/#511: reservas por caminho com os padroes, 1 credito por token: 31.744, "
      + "24.576, 34.816 e 43.776; o caminho sem modelo nao tem teto (paga a leitura)")
  void reservasPorCaminho() {
    AssistenteProperties propriedades = new AssistenteProperties();
    AssistenteProperties.Cota cota = propriedades.getCota();
    AssistenteProperties.Tetos tetos = propriedades.getTetos();
    assertThat(cota.tetoDaPergunta(tetos, 1.0, CaminhoCota.SEM_MODELO)).isZero();
    // 3 x 8.192 + (256 + 512 + 1.024) x 4.
    assertThat(cota.tetoDaPergunta(tetos, 1.0, CaminhoCota.NORMAL)).isEqualTo(31_744);
    assertThat(cota.tetoDaPergunta(tetos, 1.0)).isEqualTo(31_744);
    // 2 x 8.192 + (1.024 + 1.024) x 4.
    assertThat(cota.tetoDaPergunta(tetos, 1.0, CaminhoCota.RECONFERENCIA)).isEqualTo(24_576);
    // 3 x 8.192 + (512 + 1.024 + 1.024) x 4.
    assertThat(cota.tetoDaPergunta(tetos, 1.0, CaminhoCota.RECONFERENCIA_MISTO))
        .isEqualTo(34_816);
    // 4 x 8.192 + (192 + 2.560) x 4.
    assertThat(cota.tetoDaPergunta(tetos, 1.0, CaminhoCota.REESCRITA)).isEqualTo(43_776);
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
