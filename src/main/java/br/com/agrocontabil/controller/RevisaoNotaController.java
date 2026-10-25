package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.DevolucaoNotaRequest;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.dto.NotaFiscalRevisaoResponse;
import br.com.agrocontabil.service.RevisaoNotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Revisão da nota pelo contador antes da emissão (issue #178): o produtor envia e retira; o
 * contador aprova ou devolve no padrão {@code /api/contador/produtores/{produtorId}/...}.
 */
@RestController
@Tag(name = "Revisao da nota", description = "Aprovacao do contador antes da emissao (#178)")
public class RevisaoNotaController {

  private final RevisaoNotaService revisaoService;

  public RevisaoNotaController(RevisaoNotaService revisaoService) {
    this.revisaoService = revisaoService;
  }

  /** O produtor envia para revisão. */
  @PostMapping("/api/notas/{id}/enviar-para-revisao")
  @Operation(summary = "Envia a nota para o contador revisar (roda a validacao fiscal antes)")
  @ApiResponses({
      @ApiResponse(responseCode = "409",
          description = "TRANSICAO_REVISAO_INVALIDA (status, ou sem contador vinculado)",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "422",
          description = "Validacao fiscal falhou: nota fica COM_INCONSISTENCIAS",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public NotaFiscalResponse enviar(@PathVariable UUID id) {
    return revisaoService.enviarParaRevisao(id);
  }

  /** O produtor retira da revisão. */
  @PostMapping("/api/notas/{id}/retirar-da-revisao")
  @Operation(summary = "Retira a nota da revisao: volta a RASCUNHO")
  public NotaFiscalResponse retirar(@PathVariable UUID id) {
    return revisaoService.retirarDaRevisao(id);
  }

  /** Histórico de revisão, para o produtor. */
  @GetMapping("/api/notas/{id}/revisoes")
  @Operation(summary = "Historico de revisao da nota (envios, aprovacoes, devolucoes e motivos)")
  public ResponseEntity<List<NotaFiscalRevisaoResponse>> historico(@PathVariable UUID id) {
    return comTotal(revisaoService.historico(id));
  }

  /** O contador aprova. */
  @PostMapping("/api/contador/produtores/{produtorId}/notas/{id}/aprovar")
  @Operation(summary = "Aprova a nota (administrador ou contador; assistente 403)")
  public NotaFiscalResponse aprovar(@PathVariable UUID produtorId, @PathVariable UUID id) {
    return revisaoService.aprovar(id, produtorId);
  }

  /** O contador devolve com motivo. */
  @PostMapping("/api/contador/produtores/{produtorId}/notas/{id}/devolver")
  @Operation(summary = "Devolve a nota ao produtor com motivo (5 a 500 caracteres)")
  public NotaFiscalResponse devolver(@PathVariable UUID produtorId, @PathVariable UUID id,
      @Valid @RequestBody DevolucaoNotaRequest request) {
    return revisaoService.devolver(id, produtorId, request.motivo());
  }

  /** Histórico de revisão, para o contador. */
  @GetMapping("/api/contador/produtores/{produtorId}/notas/{id}/revisoes")
  @Operation(summary = "Historico de revisao da nota do cliente")
  public ResponseEntity<List<NotaFiscalRevisaoResponse>> historicoNaCarteira(
      @PathVariable UUID produtorId, @PathVariable UUID id) {
    return comTotal(revisaoService.historicoNaCarteira(produtorId, id));
  }

  private static ResponseEntity<List<NotaFiscalRevisaoResponse>> comTotal(
      List<NotaFiscalRevisaoResponse> historico) {
    return ResponseEntity.ok()
        .header(GuiaFiscalController.HEADER_TOTAL, String.valueOf(historico.size()))
        .body(historico);
  }
}
