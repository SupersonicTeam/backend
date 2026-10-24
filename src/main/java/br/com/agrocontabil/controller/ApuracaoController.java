package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.model.AgrupamentoApuracao;
import br.com.agrocontabil.service.ApuracaoFaixaService;
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

  /** Mês {@code YYYY-MM} com mês 01–12. */
  private static final String MES = "\\d{4}-(0[1-9]|1[0-2])";

  private static final String MENSAGEM_MES =
      "Use o formato YYYY-MM com mes entre 01 e 12 (ex.: 2026-07).";

  private final ApuracaoService apuracaoService;
  private final ApuracaoFaixaService apuracaoFaixaService;

  public ApuracaoController(ApuracaoService apuracaoService,
      ApuracaoFaixaService apuracaoFaixaService) {
    this.apuracaoService = apuracaoService;
    this.apuracaoFaixaService = apuracaoFaixaService;
  }

  /**
   * Apuração do produtor autenticado: um mês ({@code periodo}) ou uma faixa ({@code de}/{@code ate}
   * ou {@code ultimos}), opcionalmente agrupada (issue #179).
   *
   * @param periodo     um mês ({@code YYYY-MM}); devolve um objeto
   * @param de          início da faixa ({@code YYYY-MM}); devolve uma lista
   * @param ate         fim da faixa ({@code YYYY-MM})
   * @param ultimos     atalho de faixa: 6, 12, 36 ou 60 meses até o mês corrente
   * @param agrupamento {@code MES} (padrão), {@code TRIMESTRE}, {@code SEMESTRE} ou {@code ANO}
   * @return a apuração do mês, ou a lista da faixa em ordem crescente
   */
  @GetMapping
  @Operation(
      summary = "Consulta a apuracao de um mes ou de uma faixa de meses",
      description = "Com periodo=YYYY-MM devolve a apuracao de IBS/CBS do mes (objeto): "
          + "debito das saidas AUTORIZADAS, credito das entradas IMPORTADAS e saldo; persistida "
          + "se ja consolidada, projecao ao vivo (ABERTO) se nao. Com de/ate (YYYY-MM, ate 60 "
          + "meses) ou ultimos (6, 12, 36 ou 60) devolve uma lista em ordem crescente, sem "
          + "buraco: mes sem nota vem com zeros e totalNotas 0. agrupamento soma os meses por "
          + "TRIMESTRE (2026-T1), SEMESTRE (2026-S1) ou ANO (2026); o grupo so e FECHADO se todos "
          + "os meses fecharam e vem parcial quando a faixa o corta ou ele ainda nao terminou.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Apuracao consultada com sucesso"),
      @ApiResponse(responseCode = "400",
          description = "Formato invalido (VALIDATION_ERROR) ou faixa invalida "
              + "(FAIXA_APURACAO_INVALIDA: combinacao, ordem, acima de 60 meses ou ultimos "
              + "fora de 6/12/36/60)",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<?> apuracao(
      @Parameter(description = "Um mes (YYYY-MM)", example = "2026-07")
      @RequestParam(required = false)
      @Pattern(regexp = MES, message = MENSAGEM_MES)
      String periodo,
      @Parameter(description = "Inicio da faixa (YYYY-MM)", example = "2026-01")
      @RequestParam(required = false)
      @Pattern(regexp = MES, message = MENSAGEM_MES)
      String de,
      @Parameter(description = "Fim da faixa (YYYY-MM), inclusivo", example = "2026-06")
      @RequestParam(required = false)
      @Pattern(regexp = MES, message = MENSAGEM_MES)
      String ate,
      @Parameter(description = "Ultimos N meses ate o corrente: 6, 12, 36 ou 60")
      @RequestParam(required = false)
      Integer ultimos,
      @Parameter(description = "MES (padrao), TRIMESTRE, SEMESTRE ou ANO")
      @RequestParam(required = false)
      AgrupamentoApuracao agrupamento) {
    if (periodo != null) {
      apuracaoFaixaService.exigirSemFaixa(de, ate, ultimos, agrupamento);
      return ResponseEntity.ok(apuracaoService.consultar(periodo));
    }
    return ResponseEntity.ok(apuracaoFaixaService.consultar(de, ate, ultimos, agrupamento));
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
