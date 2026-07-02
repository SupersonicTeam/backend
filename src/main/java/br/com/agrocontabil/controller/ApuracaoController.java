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
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

  /**
   * Reabre uma apuração fechada do produtor autenticado (issue Agrocontabil#42 — RN005).
   *
   * <p>Delega ao {@link ApuracaoService#reabrir(UUID)}, que aplica ownership (404), precondição de
   * status FECHADO (409) e o limite de 3 meses retroativos (422). Registra quem reabriu e quando.
   *
   * @param id identificador da apuração
   * @return apuração reaberta ({@code status == ABERTO})
   */
  @PostMapping("/{id}/reabrir")
  @Operation(
      summary = "Reabre uma apuracao mensal fechada",
      description = "Transita uma apuracao de FECHADO para ABERTO, registrando quem reabriu e "
          + "quando (auditoria). A reabertura so e permitida dentro de 3 meses retroativos ao mes "
          + "atual (RN005). Restrito ao produtor autenticado (anti-IDOR).")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Apuracao reaberta com sucesso"),
      @ApiResponse(responseCode = "404",
          description = "Apuracao inexistente ou de outro produtor",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409",
          description = "Apuracao nao esta em status FECHADO",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "422",
          description = "Periodo fora do limite de 3 meses retroativos (RN005)",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<ApuracaoResponse> reabrir(
      @Parameter(description = "Identificador da apuracao") @PathVariable UUID id) {
    return ResponseEntity.ok(apuracaoService.reabrir(id));
  }
}
