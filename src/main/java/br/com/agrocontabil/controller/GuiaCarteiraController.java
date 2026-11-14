package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.GuiaFiscalResponse;
import br.com.agrocontabil.dto.NovaGuiaFiscalRequest;
import br.com.agrocontabil.service.GuiaFiscalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Guias fiscais de um cliente da carteira do escritório (issue #273), no padrão
 * {@code /api/contador/produtores/{produtorId}/...} da #177: só contador com vínculo ATIVO com o
 * produtor (produtor 403, fora da carteira 404). A regra é do {@link GuiaFiscalService}.
 */
@RestController
@Validated
@RequestMapping("/api/contador/produtores/{produtorId}/guias")
@Tag(name = "Guias Fiscais",
    description = "Guias fiscais (ICMS/FUNRURAL/DARF/GPS) anexadas pelo Contador a um produtor")
public class GuiaCarteiraController {

  private final GuiaFiscalService guiaFiscalService;

  public GuiaCarteiraController(GuiaFiscalService guiaFiscalService) {
    this.guiaFiscalService = guiaFiscalService;
  }

  /** Cria uma guia para um cliente da carteira. */
  @PostMapping
  @Operation(
      summary = "Cria uma guia fiscal para um cliente da carteira",
      description = "So contador com vinculo ATIVO com o produtor do path (fora da carteira, 404; "
          + "produtor, 403). Um produtorId no corpo e ignorado. Retorna 201 com Location "
          + "apontando para a listagem do cliente.")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Guia criada",
          headers = @Header(name = "Location", description = "URI da listagem de guias do cliente",
              schema = @Schema(type = "string", format = "uri"))),
      @ApiResponse(responseCode = "400", description = "Dados invalidos",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "403", description = "Somente contador (PAPEL_NAO_AUTORIZADO)",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "404", description = "Produtor fora da carteira ativa",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<GuiaFiscalResponse> criar(
      @PathVariable UUID produtorId, @RequestBody @Valid NovaGuiaFiscalRequest request) {
    GuiaFiscalResponse response = guiaFiscalService.criarDaCarteira(produtorId, request);
    URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
        .path("/api/contador/produtores/{produtorId}/guias").buildAndExpand(produtorId)
        .toUri();
    return ResponseEntity.created(location).body(response);
  }

  /** Lista as guias de um cliente da carteira. */
  @GetMapping
  @Operation(
      summary = "Lista as guias fiscais de um cliente da carteira, paginadas",
      description = "So contador com vinculo ATIVO com o produtor do path (fora da carteira, 404; "
          + "produtor, 403). Mais recentes primeiro (vencimento desc, id desc). O corpo e um "
          + "array; o total vai no header X-Total-Count. Padrao: page=0, size=100 (maximo 200).")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Pagina retornada",
          headers = @Header(name = GuiaFiscalController.HEADER_TOTAL,
              description = "Total de guias do cliente, somando todas as paginas",
              schema = @Schema(type = "integer"))),
      @ApiResponse(responseCode = "400", description = "page ou size fora da faixa",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "403", description = "Somente contador (PAPEL_NAO_AUTORIZADO)",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "404", description = "Produtor fora da carteira ativa",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<List<GuiaFiscalResponse>> listar(
      @PathVariable UUID produtorId,
      @Parameter(description = "Pagina, a partir de 0 (maximo 10000)")
      @RequestParam(defaultValue = "0") @Min(0) @Max(GuiaFiscalService.PAGINA_MAXIMA) int page,
      @Parameter(description = "Guias por pagina (1 a 200)")
      @RequestParam(defaultValue = "" + GuiaFiscalService.TAMANHO_PAGINA_PADRAO)
      @Min(1) @Max(GuiaFiscalService.TAMANHO_PAGINA_MAXIMO) int size) {
    Page<GuiaFiscalResponse> pagina = guiaFiscalService.listarDaCarteira(produtorId, page, size);
    return ResponseEntity.ok()
        .header(GuiaFiscalController.HEADER_TOTAL, String.valueOf(pagina.getTotalElements()))
        .body(pagina.getContent());
  }
}
