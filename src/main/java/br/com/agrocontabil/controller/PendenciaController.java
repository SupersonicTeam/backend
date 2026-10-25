package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.MotivoPendenciaRequest;
import br.com.agrocontabil.dto.PendenciaRequest;
import br.com.agrocontabil.dto.PendenciaResponse;
import br.com.agrocontabil.model.StatusPendencia;
import br.com.agrocontabil.service.PendenciaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Pendências e documentos (issue #180): o escritório pede em
 * {@code /api/contador/produtores/{produtorId}/pendencias} (qualquer membro, carteira ativa; 404
 * fora dela) e o produtor responde em {@code /api/pendencias} (só as dele; 404 para a de outro).
 */
@RestController
@Tag(name = "Pendencias e documentos",
    description = "Pedido de documento do escritorio e anexos do produtor (issue #180)")
public class PendenciaController {

  private static final String CONTADOR = "/api/contador/produtores/{produtorId}/pendencias";

  private final PendenciaService pendenciaService;

  public PendenciaController(PendenciaService pendenciaService) {
    this.pendenciaService = pendenciaService;
  }

  // --- escritorio ---

  /** Pendências do cliente. */
  @GetMapping(CONTADOR)
  @Operation(summary = "Pendencias do cliente; status repetivel (ENVIADA = recebidos)")
  public ResponseEntity<List<PendenciaResponse>> listar(@PathVariable UUID produtorId,
      @RequestParam(required = false) List<StatusPendencia> status) {
    return comTotal(pendenciaService.listar(produtorId, status));
  }

  /** Cria a pendência. */
  @PostMapping(CONTADOR)
  @Operation(summary = "Pede documentos ao cliente")
  public ResponseEntity<PendenciaResponse> criar(@PathVariable UUID produtorId,
      @Valid @RequestBody PendenciaRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(pendenciaService.criar(produtorId, request));
  }

  /** Detalhe com anexos. */
  @GetMapping(CONTADOR + "/{id}")
  @Operation(summary = "Pendencia com os anexos (metadados)")
  public PendenciaResponse detalhar(@PathVariable UUID produtorId, @PathVariable UUID id) {
    return pendenciaService.detalhar(produtorId, id);
  }

  /** Edita (só PENDENTE). */
  @PutMapping(CONTADOR + "/{id}")
  @Operation(summary = "Edita a pendencia enquanto PENDENTE")
  public PendenciaResponse editar(@PathVariable UUID produtorId, @PathVariable UUID id,
      @Valid @RequestBody PendenciaRequest request) {
    return pendenciaService.editar(id, produtorId, request);
  }

  /** Conclui (ENVIADA → CONCLUIDA). */
  @PostMapping(CONTADOR + "/{id}/concluir")
  @Operation(summary = "Aceita o que foi enviado")
  public PendenciaResponse concluir(@PathVariable UUID produtorId, @PathVariable UUID id) {
    return pendenciaService.concluir(id, produtorId);
  }

  /** Rejeita (ENVIADA → PENDENTE). */
  @PostMapping(CONTADOR + "/{id}/rejeitar")
  @Operation(summary = "Recusa o que foi enviado, com motivo; volta a PENDENTE")
  public PendenciaResponse rejeitar(@PathVariable UUID produtorId, @PathVariable UUID id,
      @Valid @RequestBody MotivoPendenciaRequest request) {
    return pendenciaService.rejeitar(id, produtorId, request.motivo());
  }

  /** Cancela. */
  @PostMapping(CONTADOR + "/{id}/cancelar")
  @Operation(summary = "Cancela o pedido, com motivo")
  public PendenciaResponse cancelar(@PathVariable UUID produtorId, @PathVariable UUID id,
      @Valid @RequestBody MotivoPendenciaRequest request) {
    return pendenciaService.cancelar(id, produtorId, request.motivo());
  }

  /** Reabre. */
  @PostMapping(CONTADOR + "/{id}/reabrir")
  @Operation(summary = "Reabre pedido concluido ou cancelado")
  public PendenciaResponse reabrir(@PathVariable UUID produtorId, @PathVariable UUID id) {
    return pendenciaService.reabrir(id, produtorId);
  }

  /** Download para o escritório. */
  @GetMapping(CONTADOR + "/{id}/anexos/{anexoId}")
  @Operation(summary = "Baixa o anexo (MIME conferido, attachment)")
  public ResponseEntity<byte[]> baixar(@PathVariable UUID produtorId, @PathVariable UUID id,
      @PathVariable UUID anexoId) {
    return arquivo(pendenciaService.baixar(produtorId, id, anexoId));
  }

  // --- produtor ---

  /** Pendências do produtor. */
  @GetMapping("/api/pendencias")
  @Operation(summary = "Pendencias do produtor, de todos os escritorios")
  public ResponseEntity<List<PendenciaResponse>> listarDoProdutor(
      @RequestParam(required = false) List<StatusPendencia> status) {
    return comTotal(pendenciaService.listarDoProdutor(status));
  }

  /** Detalhe para o produtor. */
  @GetMapping("/api/pendencias/{id}")
  @Operation(summary = "Pendencia com os anexos (metadados)")
  public PendenciaResponse detalharDoProdutor(@PathVariable UUID id) {
    return pendenciaService.detalharDoProdutor(id);
  }

  /** O produtor anexa um arquivo. */
  @PostMapping(value = "/api/pendencias/{id}/anexos",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(summary = "Anexa um arquivo (PDF, JPEG, PNG ou XML, ate 5 MB; 10 por pendencia)")
  @ApiResponses({
      @ApiResponse(responseCode = "400", description = "ANEXO_INVALIDO",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409",
          description = "LIMITE_ANEXOS ou TRANSICAO_PENDENCIA_INVALIDA",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<PendenciaResponse> anexar(@PathVariable UUID id,
      @RequestPart("arquivo") MultipartFile arquivo) throws IOException {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(pendenciaService.anexar(id, arquivo.getOriginalFilename(), arquivo.getBytes()));
  }

  /** Download para o produtor. */
  @GetMapping("/api/pendencias/{id}/anexos/{anexoId}")
  @Operation(summary = "Baixa o anexo (MIME conferido, attachment)")
  public ResponseEntity<byte[]> baixarDoProdutor(@PathVariable UUID id,
      @PathVariable UUID anexoId) {
    return arquivo(pendenciaService.baixarDoProdutor(id, anexoId));
  }

  private static ResponseEntity<List<PendenciaResponse>> comTotal(
      List<PendenciaResponse> pendencias) {
    return ResponseEntity.ok()
        .header(GuiaFiscalController.HEADER_TOTAL, String.valueOf(pendencias.size()))
        .body(pendencias);
  }

  /** Sempre attachment e com o MIME conferido no envio, nunca o declarado pelo cliente. */
  private static ResponseEntity<byte[]> arquivo(PendenciaService.Download download) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(download.mime()))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(download.nomeArquivo(), StandardCharsets.UTF_8).build().toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(download.conteudo());
  }
}
