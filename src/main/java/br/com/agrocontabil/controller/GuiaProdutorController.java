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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Guias fiscais do <strong>produtor autenticado</strong> (issue #273), fora de
 * {@code /api/contador}: o produtor vem da identidade, nenhuma rota aceita {@code produtorId}.
 * Contador recebe 403. A regra é do {@link GuiaFiscalService}, a mesma das rotas antigas
 * ({@code /api/contador/guias}, {@code deprecated}) e das da carteira.
 */
@RestController
@Validated
@RequestMapping("/api/guias")
@Tag(name = "Guias Fiscais",
    description = "Guias fiscais (ICMS/FUNRURAL/DARF/GPS) anexadas pelo Contador a um produtor")
public class GuiaProdutorController {

  private final GuiaFiscalService guiaFiscalService;

  public GuiaProdutorController(GuiaFiscalService guiaFiscalService) {
    this.guiaFiscalService = guiaFiscalService;
  }

  /** Cria uma guia para o produtor autenticado. */
  @PostMapping
  @Operation(
      summary = "Cria uma guia fiscal do produtor autenticado",
      description = "Cria a guia PENDENTE para o produtor autenticado; o produtor nunca vem do "
          + "corpo (um produtorId enviado e ignorado). Contador recebe 403. Retorna 201 com "
          + "Location apontando para a listagem.")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Guia criada",
          headers = @Header(name = "Location", description = "URI da listagem de guias",
              schema = @Schema(type = "string", format = "uri"))),
      @ApiResponse(responseCode = "400", description = "Dados invalidos",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "403", description = "Somente produtor (PAPEL_NAO_AUTORIZADO)",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<GuiaFiscalResponse> criar(
      @RequestBody @Valid NovaGuiaFiscalRequest request) {
    GuiaFiscalResponse response = guiaFiscalService.criarDoProdutorAutenticado(request);
    URI location = ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/guias")
        .build().toUri();
    return ResponseEntity.created(location).body(response);
  }

  /** Lista as guias do produtor autenticado. */
  @GetMapping
  @Operation(
      summary = "Lista as guias fiscais do produtor autenticado, paginadas",
      description = "Mais recentes primeiro (vencimento desc, id desc). O corpo e um array; o "
          + "total vai no header X-Total-Count. Padrao: page=0, size=100 (maximo 200). Contador "
          + "recebe 403.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Pagina retornada",
          headers = @Header(name = GuiaFiscalController.HEADER_TOTAL,
              description = "Total de guias, somando todas as paginas",
              schema = @Schema(type = "integer"))),
      @ApiResponse(responseCode = "400", description = "page ou size fora da faixa",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "403", description = "Somente produtor (PAPEL_NAO_AUTORIZADO)",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<List<GuiaFiscalResponse>> listar(
      @Parameter(description = "Pagina, a partir de 0 (maximo 10000)")
      @RequestParam(defaultValue = "0") @Min(0) @Max(GuiaFiscalService.PAGINA_MAXIMA) int page,
      @Parameter(description = "Guias por pagina (1 a 200)")
      @RequestParam(defaultValue = "" + GuiaFiscalService.TAMANHO_PAGINA_PADRAO)
      @Min(1) @Max(GuiaFiscalService.TAMANHO_PAGINA_MAXIMO) int size) {
    Page<GuiaFiscalResponse> pagina = guiaFiscalService.listarDoProdutorAutenticado(page, size);
    return ResponseEntity.ok()
        .header(GuiaFiscalController.HEADER_TOTAL, String.valueOf(pagina.getTotalElements()))
        .body(pagina.getContent());
  }

  /** Marca como paga uma guia do produtor autenticado. */
  @PatchMapping("/{id}/pagar")
  @Operation(
      summary = "Marca uma guia fiscal como paga",
      description = "Acao do produtor autenticado. 404 se a guia nao existir ou for de outro "
          + "produtor (anti-IDOR); 409 se ja estiver paga; contador recebe 403.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Guia marcada como paga"),
      @ApiResponse(responseCode = "403", description = "Somente produtor (PAPEL_NAO_AUTORIZADO)",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "404", description = "Guia nao encontrada",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409", description = "Guia ja esta paga",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<GuiaFiscalResponse> pagar(@PathVariable UUID id) {
    return ResponseEntity.ok(guiaFiscalService.marcarComoPaga(id));
  }
}
