package br.com.agrocontabil.controller;

import br.com.agrocontabil.dto.VencimentoFiscalResponse;
import br.com.agrocontabil.service.CalendarioFiscalService;
import io.swagger.v3.oas.annotations.Operation;
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
      description = "Retorna os vencimentos do produtor a partir das guias fiscais anexadas "
          + "pelo Contador. Lista vazia se nenhuma guia ou produtor inexistente.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Vencimentos retornados com sucesso")
  })
  public ResponseEntity<List<VencimentoFiscalResponse>> vencimentos(
      @PathVariable UUID produtorId) {
    return ResponseEntity.ok(calendarioFiscalService.listarVencimentos(produtorId));
  }
}
