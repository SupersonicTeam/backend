package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.ModeloPedidoRequest;
import br.com.agrocontabil.dto.ModeloPedidoResponse;
import br.com.agrocontabil.service.ModeloPedidoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Modelos de pedido de documento do escritório (issue #517). Só contador (produtor 403); qualquer
 * membro lê, só o administrador altera; modelo de outro escritório é 404.
 *
 * <p>O modelo só preenche o formulário: o pedido continua em
 * {@code POST /api/contador/produtores/{produtorId}/pendencias}, sem campo novo.
 */
@RestController
@RequestMapping("/api/contador/modelos-pedido")
@Tag(name = "Modelos de pedido do escritorio",
    description = "Catalogo que preenche o pedido de documento ao cliente (issue #517)")
public class ModeloPedidoController {

  private final ModeloPedidoService modeloService;

  /**
   * Cria o controller.
   *
   * @param modeloService catálogo de modelos de pedido
   */
  public ModeloPedidoController(ModeloPedidoService modeloService) {
    this.modeloService = modeloService;
  }

  /** Modelos do escritório. */
  @GetMapping
  @Operation(summary = "Modelos de pedido do escritorio, na ordem do titulo (qualquer membro)",
      description = "Ordem do titulo sem acento nem caixa; no maximo 50, sem paginacao. Corpo em "
          + "array e total em X-Total-Count.")
  public ResponseEntity<List<ModeloPedidoResponse>> listar() {
    List<ModeloPedidoResponse> modelos = modeloService.listar();
    return ResponseEntity.ok()
        .header(GuiaFiscalController.HEADER_TOTAL, String.valueOf(modelos.size()))
        .body(modelos);
  }

  /** Cria um modelo. */
  @PostMapping
  @Operation(summary = "Cria modelo de pedido (administrador)")
  @ApiResponses({
      @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "403", description = "PAPEL_NAO_AUTORIZADO (produtor, "
          + "CONTADOR ou ASSISTENTE; sem escritorio: rejectedValue SEM_ESCRITORIO)",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409", description = "MODELO_PEDIDO_DUPLICADO ou "
          + "LIMITE_MODELOS_PEDIDO",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<ModeloPedidoResponse> criar(
      @Valid @RequestBody ModeloPedidoRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(modeloService.criar(request));
  }

  /** Edita um modelo. */
  @PutMapping("/{id}")
  @Operation(summary = "Edita modelo de pedido; substitui todos os campos (administrador)")
  @ApiResponses({
      @ApiResponse(responseCode = "404", description = "Modelo inexistente ou de outro escritorio",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409", description = "MODELO_PEDIDO_DUPLICADO",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ModeloPedidoResponse atualizar(@PathVariable UUID id,
      @Valid @RequestBody ModeloPedidoRequest request) {
    return modeloService.atualizar(id, request);
  }

  /** Apaga um modelo. */
  @DeleteMapping("/{id}")
  @Operation(summary = "Apaga modelo de pedido; pedidos ja criados nao mudam (administrador)")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Apagado"),
      @ApiResponse(responseCode = "404", description = "Modelo inexistente ou de outro escritorio",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<Void> apagar(@PathVariable UUID id) {
    modeloService.apagar(id);
    return ResponseEntity.noContent().build();
  }
}
