package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.AlertaTributarioResponse;
import br.com.agrocontabil.service.AlertaTributarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alertas tributários do produtor autenticado (issue #140, RN010). O recorte por produtor é feito
 * no service (anti-IDOR); o controller só delega.
 */
@RestController
@RequestMapping("/api/alertas")
@Tag(name = "Alertas", description = "Prazos tributarios que vencem em menos de 5 dias")
public class AlertaTributarioController {

  private final AlertaTributarioService alertaService;

  public AlertaTributarioController(AlertaTributarioService alertaService) {
    this.alertaService = alertaService;
  }

  /**
   * Alertas ativos do produtor autenticado, do mais urgente ao mais distante.
   *
   * @return até 50 alertas
   */
  @GetMapping
  @Operation(summary = "Lista os alertas tributarios do produtor",
      description = "Guias pendentes, fechamento da apuracao do mes e fim do prazo de cancelamento "
          + "de NF-e (RN006) que vencem em menos de 5 dias. Gerados por job diario as 9h; um "
          + "alerta cujo motivo deixou de existir (guia paga, nota cancelada, apuracao fechada) "
          + "some na hora. So o produtor: contador recebe 403.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Alertas do produtor (pode ser vazio)"),
      @ApiResponse(responseCode = "401", description = "Sem identidade valida",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "403", description = "Papel sem acesso (contador)",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<List<AlertaTributarioResponse>> listar() {
    return ResponseEntity.ok(alertaService.listarDoProdutorAutenticado());
  }
}
