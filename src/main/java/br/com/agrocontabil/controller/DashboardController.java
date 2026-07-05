package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.DashboardResponse;
import br.com.agrocontabil.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint REST agregador do dashboard financeiro do produtor (issue Agrocontabil#65 — Sprint 5).
 *
 * <p>Restrito ao produtor autenticado — o recorte por produtor é feito na camada de serviço
 * (anti-IDOR). O controller apenas valida o parâmetro opcional e delega ao {@link DashboardService},
 * que reúsa {@code ApuracaoService} e {@code NotaFiscalRepository} sem recomputar somas de tributo.
 */
@RestController
@RequestMapping("/api/dashboard")
@Validated
@Tag(name = "Dashboard",
    description = "Painel financeiro consolidado do produtor (KPIs de IBS/CBS, volume e notas)")
public class DashboardController {

  private final DashboardService dashboardService;

  public DashboardController(DashboardService dashboardService) {
    this.dashboardService = dashboardService;
  }

  /**
   * Retorna o dashboard do produtor autenticado para o período informado (ou o mês corrente).
   *
   * @param periodo período no formato {@code YYYY-MM} (mês 01–12); opcional — quando ausente, usa o
   *     mês corrente ({@code America/Sao_Paulo})
   * @return payload agregado do dashboard
   */
  @GetMapping
  @Operation(
      summary = "Monta o dashboard financeiro do produtor",
      description = "Agrega, para o produtor autenticado, os KPIs de IBS/CBS do periodo (reusando a "
          + "apuracao mensal), a serie de volume de notas dos ultimos 6 meses (entradas x saidas), "
          + "a distribuicao da carga tributaria (apenas IBS/CBS) e as ultimas notas. O periodo usa "
          + "o formato YYYY-MM (ex.: 2026-07); se omitido, assume o mes corrente. Nunca expoe "
          + "entidade JPA nem o CPF/CNPJ do produtor.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Dashboard montado com sucesso"),
      @ApiResponse(responseCode = "400",
          description = "Periodo em formato invalido (esperado YYYY-MM)",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<DashboardResponse> dashboard(
      @Parameter(description = "Periodo no formato YYYY-MM (mes 01-12); opcional", example = "2026-07")
      @RequestParam(required = false)
      @Pattern(
          regexp = "\\d{4}-(0[1-9]|1[0-2])",
          message = "Periodo deve estar no formato YYYY-MM com mes entre 01 e 12 (ex.: 2026-07).")
      String periodo) {
    return ResponseEntity.ok(dashboardService.montar(periodo));
  }
}
