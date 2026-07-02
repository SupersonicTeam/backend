package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.dto.RelatorioConformidadeResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes do {@link RelatorioConformidadePdfService} (issue Agrocontabil#32 — UC004).
 *
 * <p>Verifica que o template {@code reports/relatorio-conformidade.jrxml} compila e produz um
 * PDF válido (magic bytes {@code %PDF}) a partir do DTO consolidado.
 */
class RelatorioConformidadePdfServiceTest {

  private final RelatorioConformidadePdfService service = new RelatorioConformidadePdfService();

  @Test
  @DisplayName("Gera PDF valido (comeca com %PDF) a partir do relatorio consolidado")
  void geraPdfValido() {
    RelatorioConformidadeResponse relatorio = new RelatorioConformidadeResponse(
        "2026-07",
        new BigDecimal("10.00"), new BigDecimal("90.00"), new BigDecimal("100.00"), 5L,
        new BigDecimal("4.00"), new BigDecimal("36.00"), new BigDecimal("40.00"), 2L,
        new BigDecimal("6.00"), new BigDecimal("54.00"), new BigDecimal("60.00"));

    byte[] pdf = service.gerarPdf(relatorio, "Joao Produtor", "SC");

    assertThat(pdf).isNotEmpty();
    assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
  }
}
