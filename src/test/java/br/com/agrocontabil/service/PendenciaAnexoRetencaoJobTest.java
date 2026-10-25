package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import br.com.agrocontabil.repository.PendenciaAnexoRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PendenciaAnexoRetencaoJob — anexos com mais de 5 anos saem (#180)")
class PendenciaAnexoRetencaoJobTest {

  private final Clock clock =
      Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));

  @Mock private PendenciaAnexoRepository anexoRepository;

  @Test
  @DisplayName("apaga os enviados antes de hoje menos a retencao")
  void apagaAnterioresAoCorte() {
    new PendenciaAnexoRetencaoJob(anexoRepository, 5, clock).aplicar();

    verify(anexoRepository).apagarAnterioresA(OffsetDateTime.now(clock).minusYears(5));
  }

  @Test
  @DisplayName("retencao abaixo de 5 anos impede a subida")
  void retencaoMinima() {
    assertThatThrownBy(() -> new PendenciaAnexoRetencaoJob(anexoRepository, 4, clock))
        .isInstanceOf(IllegalStateException.class);
  }
}
