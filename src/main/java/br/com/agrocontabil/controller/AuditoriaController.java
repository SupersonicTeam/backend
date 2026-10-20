package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.RegistroAuditoriaResponse;
import br.com.agrocontabil.service.auditoria.AuditoriaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consulta da auditoria das operações críticas (issue #149, RNF012). O controller só delega. */
@RestController
@Validated
@Tag(name = "Auditoria", description = "Registro das operacoes criticas, guardado por 5 anos")
public class AuditoriaController {

  private final AuditoriaService auditoriaService;

  public AuditoriaController(AuditoriaService auditoriaService) {
    this.auditoriaService = auditoriaService;
  }

  /**
   * Registros de auditoria de um produtor da carteira do contador.
   *
   * @param produtorId produtor
   * @param page       página
   * @param size       itens por página
   * @return os registros, mais recentes primeiro; o total em {@code X-Total-Count}
   */
  @GetMapping("/api/auditoria")
  @Operation(
      summary = "Lista a auditoria de um produtor da carteira (so contador)",
      description = "Operacoes criticas sobre os dados do produtor (notas, guias, apuracao, conta), "
          + "com sucesso ou falha, mais recentes primeiro. Sem dado pessoal: ids, papel, acao, "
          + "estado antes/depois e codigo do erro. Corpo em array; total em X-Total-Count. "
          + "Padrao page=0, size=50 (maximo 200).")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Pagina retornada",
          headers = @Header(name = GuiaFiscalController.HEADER_TOTAL,
              description = "Total de registros do produtor",
              schema = @Schema(type = "integer"))),
      @ApiResponse(responseCode = "400", description = "page ou size fora da faixa",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "403", description = "Quem pede nao e contador",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "404", description = "Produtor fora da carteira do contador",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<List<RegistroAuditoriaResponse>> listar(
      @RequestParam UUID produtorId,
      @Parameter(description = "Pagina, a partir de 0 (maximo 10000)")
      @RequestParam(defaultValue = "0") @Min(0) @Max(AuditoriaService.PAGINA_MAXIMA) int page,
      @Parameter(description = "Registros por pagina (1 a 200)")
      @RequestParam(defaultValue = "" + AuditoriaService.TAMANHO_PAGINA_PADRAO)
      @Min(1) @Max(AuditoriaService.TAMANHO_PAGINA_MAXIMO) int size) {
    Page<RegistroAuditoriaResponse> pagina = auditoriaService.listar(produtorId, page, size);
    return ResponseEntity.ok()
        .header(GuiaFiscalController.HEADER_TOTAL, String.valueOf(pagina.getTotalElements()))
        .body(pagina.getContent());
  }
}
