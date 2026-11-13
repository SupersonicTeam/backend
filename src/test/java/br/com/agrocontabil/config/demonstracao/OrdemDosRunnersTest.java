package br.com.agrocontabil.config.demonstracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.DataLoader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.OrderUtils;

/**
 * O {@code DataLoader} roda antes do seed da demonstração (issue #470).
 *
 * <p>O seed fecha os meses antigos de todos os produtores do banco; sem {@code @Order}, a ordem
 * vinha do registro dos beans e a apuração mudava (11 contra 9 linhas por mês fechado) sem mudança
 * de código.
 */
@DisplayName("Ordem do DataLoader e do SeedDemonstracao (#470)")
class OrdemDosRunnersTest {

  @Test
  @DisplayName("os dois declaram a ordem, com o DataLoader primeiro")
  void dataLoaderAntesDoSeed() {
    Integer dataLoader = OrderUtils.getOrder(DataLoader.class);
    Integer seed = OrderUtils.getOrder(SeedDemonstracao.class);

    assertThat(dataLoader).as("@Order do DataLoader").isNotNull();
    assertThat(seed).as("@Order do SeedDemonstracao").isNotNull();
    assertThat(dataLoader).isLessThan(seed);
  }

  @Test
  @DisplayName("independente da ordem em que os beans são registrados, a ordenação põe o DataLoader antes")
  void ordenacaoNaoDependeDoRegistro() {
    List<Class<? extends CommandLineRunner>> registro =
        new ArrayList<>(List.of(SeedDemonstracao.class, DataLoader.class));
    registro.sort(Comparator.comparingInt(OrderUtils::getOrder));
    assertThat(registro).containsExactly(DataLoader.class, SeedDemonstracao.class);

    registro = new ArrayList<>(List.of(DataLoader.class, SeedDemonstracao.class));
    registro.sort(Comparator.comparingInt(OrderUtils::getOrder));
    assertThat(registro).containsExactly(DataLoader.class, SeedDemonstracao.class);
  }
}
