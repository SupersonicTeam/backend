package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.service.ApuracaoService;
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
 * Endpoint REST de consulta da apuração mensal de IBS/CBS (issue Agrocontabil#41 — RN004).
 *
 * <p>Restrito ao produtor autenticado — o recorte por produtor é feito na camada de serviço
 * (anti-IDOR). O controller apenas valida o parâmetro e delega ao {@link ApuracaoService}.
 */
@RestController
@RequestMapping("/api/apuracao")
@Validated
@Tag(name = "Apuracao Mensal",
    description = "Apuracao mensal consolidada de IBS/CBS por periodo (RN004)")
public class ApuracaoController {

  private final ApuracaoService apuracaoService;

  public ApuracaoController(ApuracaoService apuracaoService) {
    this.apuracaoService = apuracaoService;
  }

  /**
   * Retorna a apuração mensal do produtor autenticado no período informado.
   *
   * @param periodo período no formato {@code YYYY-MM} (mês 01–12)
   * @return apuração consolidada do período (zerada e aberta quando ainda não consolidada)
   */
  @GetMapping
  @Operation(
      summary = "Consulta a apuracao mensal por periodo",
      description = "Retorna a apuracao consolidada de IBS/CBS do produtor autenticado no mes "
          + "informado: debito das saidas AUTORIZADAS, credito das entradas IMPORTADAS e o saldo "
          + "(debito - credito). O periodo usa o formato YYYY-MM (ex.: 2026-07). Retorna apuracao "
          + "zerada e ABERTA quando o periodo ainda nao foi consolidado.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Apuracao consultada com sucesso"),
      @ApiResponse(responseCode = "400",
          description = "Periodo ausente ou em formato invalido (esperado YYYY-MM)",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<ApuracaoResponse> apuracao(
      @Parameter(description = "Periodo no formato YYYY-MM (mes 01-12)", example = "2026-07")
      @RequestParam
      @Pattern(
          regexp = "\\d{4}-(0[1-9]|1[0-2])",
          message = "Periodo deve estar no formato YYYY-MM com mes entre 01 e 12 (ex.: 2026-07).")
      String periodo) {
    return ResponseEntity.ok(apuracaoService.consultar(periodo));
  }
}
