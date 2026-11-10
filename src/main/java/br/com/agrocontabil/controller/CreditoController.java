package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.CreditoSaldoResponse;
import br.com.agrocontabil.service.CreditoService;
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
 * Endpoint REST de consulta do saldo de crédito tributário de IBS/CBS (issue Agrocontabil#30 —
 * RN003).
 *
 * <p>Restrito ao produtor autenticado — o recorte por produtor é feito na camada de serviço
 * (anti-IDOR). O controller apenas valida o parâmetro e delega ao {@link CreditoService}.
 */
@RestController
@RequestMapping("/api/creditos")
@Validated
@Tag(name = "Creditos Tributarios",
    description = "Saldo de credito de IBS/CBS das notas de entrada (RN003)")
public class CreditoController {

  private final CreditoService creditoService;

  public CreditoController(CreditoService creditoService) {
    this.creditoService = creditoService;
  }

  /**
   * Retorna o saldo acumulado de crédito de IBS/CBS do produtor autenticado no período.
   *
   * @param periodo período no formato {@code YYYY-MM} (mês 01–12)
   * @return saldo consolidado do período
   */
  @GetMapping
  @Operation(
      summary = "Consulta o saldo de credito tributario por periodo",
      description = "Retorna o saldo acumulado de credito de IBS/CBS das notas de entrada "
          + "(origem IMPORTADA) do produtor autenticado, emitidas no mes informado. "
          + "O periodo usa o formato YYYY-MM (ex.: 2026-07). Retorna saldo zerado quando "
          + "nao ha notas no periodo.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Saldo consultado com sucesso"),
      @ApiResponse(responseCode = "400",
          description = "Periodo ausente ou em formato invalido (esperado YYYY-MM)",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<CreditoSaldoResponse> saldo(
      @Parameter(description = "Periodo no formato YYYY-MM (mes 01-12)", example = "2026-07")
      @RequestParam
      @Pattern(
          regexp = "\\d{4}-(0[1-9]|1[0-2])",
          message = "Período deve estar no formato YYYY-MM com mês entre 01 e 12 (ex.: 2026-07).")
      String periodo) {
    return ResponseEntity.ok(creditoService.consultarSaldo(periodo));
  }
}
