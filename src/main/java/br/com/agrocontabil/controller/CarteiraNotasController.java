package br.com.agrocontabil.controller;

import br.com.agrocontabil.dto.FiltroNotasRequest;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.dto.NotaFiscalValidacaoResponse;
import br.com.agrocontabil.service.CarteiraNotasService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notas de um cliente da carteira, na leitura do escritório (issue #177). Só contador, só a
 * carteira ativa (404 fora dela).
 */
@RestController
@RequestMapping("/api/contador/produtores/{produtorId}/notas")
@Tag(name = "Carteira do contador")
public class CarteiraNotasController {

  private final CarteiraNotasService carteiraNotasService;

  public CarteiraNotasController(CarteiraNotasService carteiraNotasService) {
    this.carteiraNotasService = carteiraNotasService;
  }

  /** Notas do cliente, com os filtros de {@code GET /api/notas}. */
  @GetMapping
  @Operation(summary = "Notas do cliente",
      description = "Mesmos filtros e ordenacao de GET /api/notas. Corpo em array e total em "
          + "X-Total-Count.")
  public ResponseEntity<List<NotaFiscalResponse>> listar(@PathVariable UUID produtorId,
      @Valid @ParameterObject FiltroNotasRequest filtro, @ParameterObject Pageable pageable) {
    Page<NotaFiscalResponse> pagina = carteiraNotasService.listar(produtorId, filtro, pageable);
    return ResponseEntity.ok()
        .header(GuiaFiscalController.HEADER_TOTAL, String.valueOf(pagina.getTotalElements()))
        .body(pagina.getContent());
  }

  /** Uma nota do cliente. */
  @GetMapping("/{notaId}")
  @Operation(summary = "Nota do cliente (404 se for de outro produtor)")
  public NotaFiscalResponse buscar(@PathVariable UUID produtorId, @PathVariable UUID notaId) {
    return carteiraNotasService.buscar(produtorId, notaId);
  }

  /** Preview da DANFE de nota não emitida, para a revisão. */
  @GetMapping(value = "/{notaId}/danfe-preview", produces = MediaType.APPLICATION_PDF_VALUE)
  @Operation(summary = "Preview da DANFE da nota do cliente (rascunho, em revisao ou aprovada)")
  public ResponseEntity<byte[]> previewDanfe(@PathVariable UUID produtorId,
      @PathVariable UUID notaId) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=danfe-preview-" + notaId + ".pdf")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(carteiraNotasService.previewDanfe(produtorId, notaId));
  }

  /** Histórico de inconsistências de uma nota do cliente. */
  @GetMapping("/{notaId}/validacoes")
  @Operation(summary = "Historico de inconsistencias da nota do cliente")
  public List<NotaFiscalValidacaoResponse> validacoes(@PathVariable UUID produtorId,
      @PathVariable UUID notaId) {
    return carteiraNotasService.validacoes(produtorId, notaId);
  }
}
