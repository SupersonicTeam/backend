package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.GuiaFiscalRequest;
import br.com.agrocontabil.dto.GuiaFiscalResponse;
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
 * Endpoints REST para o Contador anexar guias fiscais a um produtor (issue #74).
 *
 * <p>Ainda não existe entidade/login de Contador no backend (issue #93, Sprint 8), então as três
 * operações são, na prática, do próprio produtor: o {@code produtorId} recebido no corpo ou na
 * query precisa ser o do produtor autenticado, sob pena de 404 (issue #94). Todas seguem o padrão
 * anti-IDOR do restante da API.
 *
 * <p>O controller não contém regra de negócio: apenas recebe o DTO validado, delega ao
 * {@link GuiaFiscalService} e mapeia o resultado para a resposta HTTP.
 */
@RestController
@Validated
@RequestMapping("/api/contador/guias")
@Tag(name = "Guias Fiscais",
    description = "Guias fiscais (ICMS/FUNRURAL/DARF/GPS) anexadas pelo Contador a um produtor")
public class GuiaFiscalController {

  /**
   * Header com o total de guias do produtor (issue #103). O corpo da listagem segue sendo um
   * array para não quebrar o Angular; o total fica aqui, exposto no CORS ({@code CorsConfig}).
   */
  public static final String HEADER_TOTAL = "X-Total-Count";

  private final GuiaFiscalService guiaFiscalService;

  public GuiaFiscalController(GuiaFiscalService guiaFiscalService) {
    this.guiaFiscalService = guiaFiscalService;
  }

  /**
   * Cria uma guia fiscal para o produtor informado no request.
   */
  @PostMapping
  @Operation(
      summary = "Cria uma guia fiscal",
      description = "Cria a guia com status PENDENTE para o produtorId informado no corpo, que "
          + "precisa ser o do produtor autenticado (404 caso contrario). Retorna 201 com header "
          + "Location apontando para a listagem do produtor.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "201",
          description = "Guia criada com sucesso",
          headers = @Header(
              name = "Location",
              description = "URI da listagem de guias do produtor",
              schema = @Schema(type = "string", format = "uri"))),
      @ApiResponse(responseCode = "400", description = "Dados invalidos",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "404",
          description = "Produtor nao encontrado ou nao pertence ao usuario autenticado",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<GuiaFiscalResponse> criar(
      @RequestBody @Valid GuiaFiscalRequest request) {
    GuiaFiscalResponse response = guiaFiscalService.criar(request);
    URI location = ServletUriComponentsBuilder
        .fromCurrentContextPath()
        .path("/api/contador/guias")
        .queryParam("produtorId", response.produtorId())
        .build()
        .toUri();
    return ResponseEntity.created(location).body(response);
  }

  /**
   * Lista as guias fiscais do produtor informado.
   */
  @GetMapping
  @Operation(
      summary = "Lista guias fiscais de um produtor, paginadas",
      description = "Retorna uma pagina das guias fiscais do produtorId informado, mais recentes "
          + "primeiro (vencimento desc, id desc). O corpo continua sendo um array, compativel com "
          + "o cliente que nao pagina; o total de guias vai no header X-Total-Count. Padrao: "
          + "page=0, size=100 (maximo 200). O produtorId precisa ser o do produtor autenticado "
          + "(404 caso contrario). Array vazio se ele nao tiver guias.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = "Pagina retornada com sucesso",
          headers = @Header(
              name = HEADER_TOTAL,
              description = "Total de guias do produtor, somando todas as paginas",
              schema = @Schema(type = "integer"))),
      @ApiResponse(responseCode = "400", description = "page ou size fora da faixa",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "404",
          description = "Produtor nao encontrado ou nao pertence ao usuario autenticado",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<List<GuiaFiscalResponse>> listar(
      @RequestParam UUID produtorId,
      @Parameter(description = "Pagina, a partir de 0")
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @Parameter(description = "Guias por pagina (1 a 200)")
      @RequestParam(defaultValue = "" + GuiaFiscalService.TAMANHO_PAGINA_PADRAO)
      @Min(1) @Max(GuiaFiscalService.TAMANHO_PAGINA_MAXIMO) int size) {
    Page<GuiaFiscalResponse> pagina = guiaFiscalService.listar(produtorId, page, size);
    return ResponseEntity.ok()
        .header(HEADER_TOTAL, String.valueOf(pagina.getTotalElements()))
        .body(pagina.getContent());
  }

  /**
   * Marca como paga a guia fiscal indicada, para o produtor autenticado.
   */
  @PatchMapping("/{id}/pagar")
  @Operation(
      summary = "Marca uma guia fiscal como paga",
      description = "Acao do produtor autenticado. Retorna 404 se a guia nao existir ou nao "
          + "pertencer ao produtor autenticado (anti-IDOR) e 409 se ja estiver paga.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Guia marcada como paga"),
      @ApiResponse(responseCode = "404",
          description = "Guia nao encontrada ou nao pertence ao produtor autenticado",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409", description = "Guia ja esta paga",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<GuiaFiscalResponse> pagar(@PathVariable UUID id) {
    return ResponseEntity.ok(guiaFiscalService.marcarComoPaga(id));
  }
}
