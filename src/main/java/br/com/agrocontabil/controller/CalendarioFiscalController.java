package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.VencimentoFiscalResponse;
import br.com.agrocontabil.service.CalendarioFiscalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint REST do calendário fiscal do produtor (issue #74).
 *
 * <p>Fecha a lacuna que já quebrava a tela de Calendário do Angular: o {@code CalendarioApi} do
 * frontend chama {@code GET /calendario-fiscal/:produtorId/vencimentos} sem fallback mock. O
 * controller apenas delega ao {@link CalendarioFiscalService}, que hoje reúsa as guias fiscais
 * do produtor como única fonte de vencimento.
 *
 * <p>Restrito ao produtor autenticado — o {@code produtorId} do path precisa bater com o produtor
 * autenticado (anti-IDOR, verificado na camada de serviço); caso contrário, 404.
 */
@RestController
@RequestMapping("/api/calendario-fiscal")
@Tag(name = "Calendario Fiscal", description = "Vencimentos fiscais do produtor")
public class CalendarioFiscalController {

  private final CalendarioFiscalService calendarioFiscalService;

  public CalendarioFiscalController(CalendarioFiscalService calendarioFiscalService) {
    this.calendarioFiscalService = calendarioFiscalService;
  }

  /**
   * Lista os vencimentos fiscais do produtor indicado.
   */
  @GetMapping("/{produtorId}/vencimentos")
  @Operation(
      summary = "Lista os vencimentos fiscais do produtor",
      description = "Retorna os vencimentos do produtor autenticado a partir das guias fiscais "
          + "anexadas pelo Contador. O produtorId do path precisa ser o do produtor autenticado; "
          + "retorna 404 caso contrario (anti-IDOR). Lista vazia se nenhuma guia.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Vencimentos retornados com sucesso"),
      @ApiResponse(responseCode = "404",
          description = "produtorId nao corresponde ao produtor autenticado",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<List<VencimentoFiscalResponse>> vencimentos(
      @PathVariable UUID produtorId) {
    return ResponseEntity.ok(calendarioFiscalService.listarVencimentos(produtorId));
  }
}
