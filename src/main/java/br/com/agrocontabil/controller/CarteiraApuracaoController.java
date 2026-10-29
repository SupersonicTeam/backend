package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.model.AgrupamentoApuracao;
import br.com.agrocontabil.service.ApuracaoService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Apuração de um cliente da carteira, pelo escritório: consulta (issue #255) e reabertura (issue
 * #224). Só contador, só a carteira ativa (404 fora dela).
 */
@RestController
@Validated
@RequestMapping("/api/contador/produtores/{produtorId}/apuracao")
@Tag(name = "Carteira do contador")
public class CarteiraApuracaoController {

  private final ApuracaoService apuracaoService;
  private final CarteiraIndicadoresService indicadoresService;

  public CarteiraApuracaoController(ApuracaoService apuracaoService,
      CarteiraIndicadoresService indicadoresService) {
    this.apuracaoService = apuracaoService;
    this.indicadoresService = indicadoresService;
  }

  /**
   * Apuração do cliente: mesmos parâmetros e mesmo response de {@code GET /api/apuracao}
   * (issue #255).
   *
   * @param produtorId  cliente da carteira ativa
   * @param periodo     um mês ({@code YYYY-MM}); devolve um objeto
   * @param de          início da faixa ({@code YYYY-MM}); devolve uma lista
   * @param ate         fim da faixa ({@code YYYY-MM})
   * @param ultimos     atalho de faixa: 6, 12, 36 ou 60 meses até o mês corrente
   * @param agrupamento {@code MES} (padrão), {@code TRIMESTRE}, {@code SEMESTRE} ou {@code ANO}
   * @return a apuração do mês, ou a lista da faixa em ordem crescente
   */
  @GetMapping
  @Operation(
      summary = "Consulta a apuracao do cliente (um mes ou uma faixa de meses)",
      description = "Mesmos parametros e mesmo response de GET /api/apuracao, para um cliente da "
          + "carteira ativa do escritorio: periodo=YYYY-MM devolve um objeto; de/ate ou ultimos "
          + "devolvem uma lista crescente, opcionalmente agrupada. Qualquer membro do escritorio; "
          + "produtor 403; fora da carteira ativa 404.")
  @ApiResponses({
      @ApiResponse(responseCode = "200",
          description = "Objeto com periodo; lista em ordem crescente com de/ate ou ultimos",
          content = @Content(mediaType = "application/json", schema = @Schema(
              oneOf = {ApuracaoResponse.class, ApuracaoResponse[].class}))),
      @ApiResponse(responseCode = "400",
          description = "Formato invalido (VALIDATION_ERROR) ou faixa invalida "
              + "(FAIXA_APURACAO_INVALIDA)",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "403", description = "Produtor (PAPEL_NAO_AUTORIZADO)",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "404", description = "Produtor fora da carteira ativa",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<?> apuracao(
      @PathVariable UUID produtorId,
      @Parameter(description = "Um mes (YYYY-MM)", example = "2026-07")
      @RequestParam(required = false)
      @Pattern(regexp = ApuracaoController.MES, message = ApuracaoController.MENSAGEM_MES)
      String periodo,
      @Parameter(description = "Inicio da faixa (YYYY-MM)", example = "2026-01")
      @RequestParam(required = false)
      @Pattern(regexp = ApuracaoController.MES, message = ApuracaoController.MENSAGEM_MES)
      String de,
      @Parameter(description = "Fim da faixa (YYYY-MM), inclusivo", example = "2026-06")
      @RequestParam(required = false)
      @Pattern(regexp = ApuracaoController.MES, message = ApuracaoController.MENSAGEM_MES)
      String ate,
      @Parameter(description = "Ultimos N meses ate o corrente: 6, 12, 36 ou 60")
      @RequestParam(required = false)
      Integer ultimos,
      @Parameter(description = "MES (padrao), TRIMESTRE, SEMESTRE ou ANO")
      @RequestParam(required = false)
      AgrupamentoApuracao agrupamento) {
    if (periodo != null) {
      return ResponseEntity.ok(
          indicadoresService.apuracaoDoMes(produtorId, periodo, de, ate, ultimos, agrupamento));
    }
    return ResponseEntity.ok(
        indicadoresService.apuracaoDaFaixa(produtorId, de, ate, ultimos, agrupamento));
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
