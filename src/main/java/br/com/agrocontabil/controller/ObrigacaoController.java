package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.ObrigacaoRequest;
import br.com.agrocontabil.dto.ObrigacaoResponse;
import br.com.agrocontabil.service.ObrigacaoFiscalService;
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
 * Catálogo de obrigações do escritório (issue #181). Só contador (produtor 403); qualquer membro
 * lê, só o administrador altera; obrigação de outro escritório é 404. Os modelos do sistema são de
 * leitura: editar ou desativar um modelo cria a versão do escritório.
 */
@RestController
@RequestMapping("/api/contador/obrigacoes")
@Tag(name = "Obrigacoes do escritorio",
    description = "Catalogo que alimenta o calendario fiscal dos clientes (issue #181)")
public class ObrigacaoController {

  private final ObrigacaoFiscalService obrigacaoService;

  public ObrigacaoController(ObrigacaoFiscalService obrigacaoService) {
    this.obrigacaoService = obrigacaoService;
  }

  /** Catálogo efetivo. */
  @GetMapping
  @Operation(summary = "Catalogo efetivo: proprias, versoes do escritorio e modelos nao mexidos",
      description = "origemCatalogo = MODELO, PERSONALIZADA ou PROPRIA. Corpo em array e total em "
          + "X-Total-Count.")
  public ResponseEntity<List<ObrigacaoResponse>> catalogo() {
    List<ObrigacaoResponse> obrigacoes = obrigacaoService.catalogo();
    return ResponseEntity.ok()
        .header(GuiaFiscalController.HEADER_TOTAL, String.valueOf(obrigacoes.size()))
        .body(obrigacoes);
  }

  /** Cria uma obrigação própria. */
  @PostMapping
  @Operation(summary = "Cria obrigacao propria do escritorio (administrador)")
  @ApiResponses({
      @ApiResponse(responseCode = "400", description = "OBRIGACAO_INVALIDA ou VALIDATION_ERROR",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<ObrigacaoResponse> criar(@Valid @RequestBody ObrigacaoRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(obrigacaoService.criar(request));
  }

  /** Edita (no modelo, pela versão do escritório). */
  @PutMapping("/{id}")
  @Operation(summary = "Edita a obrigacao; num modelo, cria ou atualiza a versao do escritorio")
  public ObrigacaoResponse atualizar(@PathVariable UUID id,
      @Valid @RequestBody ObrigacaoRequest request) {
    return obrigacaoService.atualizar(id, request);
  }

  /** Tira do calendário (desativa). */
  @DeleteMapping("/{id}")
  @Operation(summary = "Tira a obrigacao do calendario (ativa = false; da para reativar)")
  public ObrigacaoResponse desativar(@PathVariable UUID id) {
    return obrigacaoService.desativar(id);
  }

  /** Volta ao calendário. */
  @PostMapping("/{id}/reativar")
  @Operation(summary = "Volta a obrigacao ao calendario")
  public ObrigacaoResponse reativar(@PathVariable UUID id) {
    return obrigacaoService.reativar(id);
  }

  /** Restaura o modelo. */
  @PostMapping("/{id}/restaurar-padrao")
  @Operation(summary = "Apaga a versao do escritorio e volta ao modelo do sistema")
  public ObrigacaoResponse restaurarPadrao(@PathVariable UUID id) {
    return obrigacaoService.restaurarPadrao(id);
  }
}
