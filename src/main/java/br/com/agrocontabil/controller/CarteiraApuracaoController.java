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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Apuração de um cliente da carteira, pelo escritório (issue #224). Só contador, só a carteira
 * ativa (404 fora dela).
 */
@RestController
@Validated
@RequestMapping("/api/contador/produtores/{produtorId}/apuracao")
@Tag(name = "Carteira do contador")
public class CarteiraApuracaoController {

  private final ApuracaoService apuracaoService;

  public CarteiraApuracaoController(ApuracaoService apuracaoService) {
    this.apuracaoService = apuracaoService;
  }

  /**
   * Reabre a apuração fechada de um mês do cliente (RN005).
   *
   * @param produtorId cliente da carteira ativa
   * @param periodo    mês da apuração ({@code YYYY-MM})
   * @return a apuração reaberta ({@code status == ABERTO})
   */
  @PostMapping("/{periodo}/reabrir")
  @Operation(
      summary = "Reabre a apuracao fechada de um mes do cliente (RN005)",
      description = "Transita a apuracao do mes de FECHADO para ABERTO, registrando quem reabriu e "
          + "quando, ate 3 meses retroativos ao mes atual. So ADMINISTRADOR e CONTADOR do "
          + "escritorio (ASSISTENTE 403); produtor 403; fora da carteira ativa 404.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Apuracao reaberta"),
      @ApiResponse(responseCode = "400", description = "Periodo fora do formato YYYY-MM",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "403",
          description = "Produtor, ou funcao ASSISTENTE no escritorio (PAPEL_NAO_AUTORIZADO)",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "404",
          description = "Produtor fora da carteira ativa, ou mes sem apuracao consolidada",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409", description = "Apuracao nao esta FECHADA",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "422",
          description = "Mes fora dos 3 meses retroativos (REABERTURA_FORA_PRAZO)",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ApuracaoResponse reabrir(
      @PathVariable UUID produtorId,
      @Parameter(description = "Mes da apuracao (YYYY-MM)", example = "2026-07")
      @PathVariable @Pattern(regexp = ApuracaoController.MES,
          message = ApuracaoController.MENSAGEM_MES) String periodo) {
    return apuracaoService.reabrirPeloContador(produtorId, periodo).apuracao();
  }
}
