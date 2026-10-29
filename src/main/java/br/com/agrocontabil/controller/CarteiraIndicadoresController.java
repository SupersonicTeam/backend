package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.CreditoSaldoResponse;
import br.com.agrocontabil.dto.DashboardResponse;
import br.com.agrocontabil.dto.RelatorioConformidadeResponse;
import br.com.agrocontabil.service.CarteiraIndicadoresService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relatório de conformidade, créditos e dashboard de um cliente da carteira, pelo escritório
 * (issue #255). Mesmos parâmetros e mesmos responses das rotas do produtor
 * ({@code /api/relatorios/conformidade}, {@code /api/creditos}, {@code /api/dashboard}); qualquer
 * membro do escritório, só a carteira ativa (404 fora dela), produtor 403.
 */
@RestController
@Validated
@RequestMapping("/api/contador/produtores/{produtorId}")
@Tag(name = "Carteira do contador")
public class CarteiraIndicadoresController {

  private final CarteiraIndicadoresService indicadoresService;

  public CarteiraIndicadoresController(CarteiraIndicadoresService indicadoresService) {
    this.indicadoresService = indicadoresService;
  }

  /**
   * Relatório de conformidade do cliente no mês (JSON).
   *
   * @param produtorId cliente da carteira ativa
   * @param periodo    mês {@code YYYY-MM}
   * @return débitos × créditos × saldo
   */
  @GetMapping("/relatorios/conformidade")
  @Operation(
      summary = "Relatorio de conformidade do cliente (JSON)",
      description = "Mesmo response de GET /api/relatorios/conformidade, para um cliente da "
          + "carteira ativa. Produtor 403; fora da carteira ativa 404.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Relatorio gerado"),
      @ApiResponse(responseCode = "400", description = "Periodo ausente ou fora de YYYY-MM",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "403", description = "Produtor (PAPEL_NAO_AUTORIZADO)",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "404", description = "Produtor fora da carteira ativa",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public RelatorioConformidadeResponse relatorioConformidade(
      @PathVariable UUID produtorId,
      @Parameter(description = "Periodo no formato YYYY-MM (mes 01-12)", example = "2026-07")
      @RequestParam
      @Pattern(regexp = ApuracaoController.MES, message = ApuracaoController.MENSAGEM_MES)
      String periodo) {
    return indicadoresService.relatorioConformidade(produtorId, periodo);
  }

  /**
   * Relatório de conformidade do cliente no mês, em PDF.
   *
   * @param produtorId cliente da carteira ativa
   * @param periodo    mês {@code YYYY-MM}
   * @return PDF com {@code Content-Disposition: attachment}
   */
  @GetMapping(value = "/relatorios/conformidade/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
  @Operation(
      summary = "Relatorio de conformidade do cliente em PDF",
      description = "Mesmo PDF de GET /api/relatorios/conformidade/pdf, para um cliente da "
          + "carteira ativa. Produtor 403; fora da carteira ativa 404.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "PDF do relatorio",
          content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE)),
      @ApiResponse(responseCode = "400", description = "Periodo ausente ou fora de YYYY-MM",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "403", description = "Produtor (PAPEL_NAO_AUTORIZADO)",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "404", description = "Produtor fora da carteira ativa",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<byte[]> relatorioConformidadePdf(
      @PathVariable UUID produtorId,
      @Parameter(description = "Periodo no formato YYYY-MM (mes 01-12)", example = "2026-07")
      @RequestParam
      @Pattern(regexp = ApuracaoController.MES, message = ApuracaoController.MENSAGEM_MES)
      String periodo) {
    byte[] pdf = indicadoresService.relatorioConformidadePdf(produtorId, periodo);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"conformidade-" + periodo + ".pdf\"")
        .body(pdf);
  }

  /**
   * Saldo de crédito de IBS/CBS do cliente no mês.
   *
   * @param produtorId cliente da carteira ativa
   * @param periodo    mês {@code YYYY-MM}
   * @return saldo do período
   */
  @GetMapping("/creditos")
  @Operation(
      summary = "Saldo de credito do cliente por periodo",
      description = "Mesmo response de GET /api/creditos, para um cliente da carteira ativa. "
          + "Produtor 403; fora da carteira ativa 404.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Saldo consultado"),
      @ApiResponse(responseCode = "400", description = "Periodo ausente ou fora de YYYY-MM",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "403", description = "Produtor (PAPEL_NAO_AUTORIZADO)",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "404", description = "Produtor fora da carteira ativa",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public CreditoSaldoResponse creditos(
      @PathVariable UUID produtorId,
      @Parameter(description = "Periodo no formato YYYY-MM (mes 01-12)", example = "2026-07")
      @RequestParam
      @Pattern(regexp = ApuracaoController.MES, message = ApuracaoController.MENSAGEM_MES)
      String periodo) {
    return indicadoresService.creditos(produtorId, periodo);
  }

  /**
   * Dashboard do cliente no mês (ou no corrente).
   *
   * @param produtorId cliente da carteira ativa
   * @param periodo    mês {@code YYYY-MM}; opcional
   * @return o painel do cliente
   */
  @GetMapping("/dashboard")
  @Operation(
      summary = "Dashboard do cliente",
      description = "Mesmo response de GET /api/dashboard, para um cliente da carteira ativa; "
          + "sem periodo, o mes corrente. Produtor 403; fora da carteira ativa 404.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Dashboard montado"),
      @ApiResponse(responseCode = "400", description = "Periodo fora de YYYY-MM",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "403", description = "Produtor (PAPEL_NAO_AUTORIZADO)",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "404", description = "Produtor fora da carteira ativa",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public DashboardResponse dashboard(
      @PathVariable UUID produtorId,
      @Parameter(description = "Periodo no formato YYYY-MM (mes 01-12); opcional",
          example = "2026-07")
      @RequestParam(required = false)
      @Pattern(regexp = ApuracaoController.MES, message = ApuracaoController.MENSAGEM_MES)
      String periodo) {
    return indicadoresService.dashboard(produtorId, periodo);
  }
}
