package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.RelatorioConformidadeResponse;
import br.com.agrocontabil.service.RelatorioConformidadeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints REST do relatório de conformidade tributária (issue Agrocontabil#32 — UC004).
 *
 * <p>Restrito ao produtor autenticado — o recorte por produtor é feito na camada de serviço
 * (anti-IDOR). O controller apenas valida o parâmetro e delega ao
 * {@link RelatorioConformidadeService}. Oferece a versão JSON (consolidado) e a exportação em
 * PDF (mesma convenção do endpoint de DANFE: subpath {@code /pdf}).
 */
@RestController
@RequestMapping("/api/relatorios/conformidade")
@Validated
@Tag(name = "Relatorio de Conformidade",
    description = "Consolidado de debitos x creditos de IBS/CBS por periodo (UC004)")
public class RelatorioConformidadeController {

  /** Formato aceito para o período: {@code YYYY-MM} com mês entre 01 e 12. */
  private static final String PERIODO_REGEX = "\\d{4}-(0[1-9]|1[0-2])";
  private static final String PERIODO_MSG =
      "Periodo deve estar no formato YYYY-MM com mes entre 01 e 12 (ex.: 2026-07).";

  private final RelatorioConformidadeService relatorioService;

  public RelatorioConformidadeController(RelatorioConformidadeService relatorioService) {
    this.relatorioService = relatorioService;
  }

  /**
   * Retorna o relatório de conformidade consolidado (JSON) do produtor autenticado no período.
   *
   * @param periodo período no formato {@code YYYY-MM}
   * @return consolidado de débitos, créditos e saldo do período
   */
  @GetMapping
  @Operation(
      summary = "Consulta o relatorio de conformidade (JSON)",
      description = "Consolida os debitos de IBS/CBS das saidas autorizadas com os creditos das "
          + "entradas do produtor autenticado no periodo (YYYY-MM), apurando o saldo a pagar "
          + "(negativo = saldo credor).")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Relatorio gerado com sucesso"),
      @ApiResponse(responseCode = "400",
          description = "Periodo ausente ou em formato invalido (esperado YYYY-MM)",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<RelatorioConformidadeResponse> consolidado(
      @Parameter(description = "Periodo no formato YYYY-MM (mes 01-12)", example = "2026-07")
      @RequestParam
      @Pattern(regexp = PERIODO_REGEX, message = PERIODO_MSG)
      String periodo) {
    return ResponseEntity.ok(relatorioService.gerar(periodo));
  }

  /**
   * Exporta o relatório de conformidade do período em PDF (JasperReports).
   *
   * @param periodo período no formato {@code YYYY-MM}
   * @return PDF do relatório com {@code Content-Disposition: attachment}
   */
  @GetMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
  @Operation(
      summary = "Exporta o relatorio de conformidade em PDF",
      description = "Gera e retorna o PDF do relatorio de conformidade consolidado do produtor "
          + "autenticado no periodo (YYYY-MM).")
  @ApiResponses({
      @ApiResponse(responseCode = "200",
          description = "PDF do relatorio retornado com sucesso",
          content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE)),
      @ApiResponse(responseCode = "400",
          description = "Periodo ausente ou em formato invalido (esperado YYYY-MM)",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<byte[]> exportarPdf(
      @Parameter(description = "Periodo no formato YYYY-MM (mes 01-12)", example = "2026-07")
      @RequestParam
      @Pattern(regexp = PERIODO_REGEX, message = PERIODO_MSG)
      String periodo) {
    byte[] pdf = relatorioService.gerarPdf(periodo);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"conformidade-" + periodo + ".pdf\"")
        .body(pdf);
  }
}
