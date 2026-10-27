package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.MensagemNotificacaoRequest;
import br.com.agrocontabil.dto.NotificacaoResponse;
import br.com.agrocontabil.dto.ResultadoNotificacaoResponse;
import br.com.agrocontabil.service.NotificacaoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notificação ao produtor por WhatsApp (issue #182; só o registro na v1). O produtor dispara em
 * {@code /api/notificacao} (o produtor vem do token); o escritório, em
 * {@code /api/contador/produtores/{produtorId}/notificacao} (carteira ativa; 404 fora dela).
 */
@RestController
@Validated
@Tag(name = "Notificacao", description = "Avisos por WhatsApp ao produtor (issue #182)")
@ApiResponses({
    @ApiResponse(responseCode = "422", description = "CONTATO_AUSENTE: produtor sem WhatsApp",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(responseCode = "429", description = "LIMITE_NOTIFICACOES (com Retry-After)",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
})
public class NotificacaoController {

  private static final String PRODUTOR = "/api/notificacao";
  private static final String CONTADOR = "/api/contador/produtores/{produtorId}/notificacao";

  private final NotificacaoService notificacaoService;

  public NotificacaoController(NotificacaoService notificacaoService) {
    this.notificacaoService = notificacaoService;
  }

  // --- produtor ---

  /** Próximos 7 dias. */
  @PostMapping(PRODUTOR + "/proximas-obrigacoes")
  @Operation(summary = "Avisa os vencimentos dos proximos 7 dias (urgentes primeiro)")
  public ResultadoNotificacaoResponse proximasDoProdutor() {
    return notificacaoService.proximasDoProdutor();
  }

  /** Atrasados. */
  @PostMapping(PRODUTOR + "/todos-atrasados")
  @Operation(summary = "Avisa as guias vencidas e os pedidos de documento com prazo passado")
  public ResultadoNotificacaoResponse atrasadosDoProdutor() {
    return notificacaoService.atrasadosDoProdutor();
  }

  /** Mensagem livre. */
  @PostMapping(PRODUTOR + "/enviar")
  @Operation(summary = "Registra uma mensagem livre (1 a 500 caracteres)")
  public ResultadoNotificacaoResponse mensagemDoProdutor(
      @Valid @RequestBody MensagemNotificacaoRequest request) {
    return notificacaoService.mensagemDoProdutor(request.mensagem());
  }

  /** Histórico do produtor. */
  @GetMapping(PRODUTOR)
  @Operation(summary = "Notificacoes do produtor, mais recentes primeiro; total em X-Total-Count")
  public ResponseEntity<List<NotificacaoResponse>> listarDoProdutor(
      @RequestParam(defaultValue = "0") @Min(0) @Max(NotificacaoService.PAGINA_MAXIMA) int page,
      @RequestParam(defaultValue = "" + NotificacaoService.TAMANHO_PAGINA_PADRAO)
      @Min(1) @Max(NotificacaoService.TAMANHO_PAGINA_MAXIMO) int size) {
    return comTotal(notificacaoService.listarDoProdutor(page, size));
  }

  // --- escritorio ---

  /** Próximos 7 dias do escritório. */
  @PostMapping(CONTADOR + "/proximas-obrigacoes")
  @Operation(summary = "Cobra do cliente os vencimentos dos proximos 7 dias do escritorio")
  public ResultadoNotificacaoResponse proximas(@PathVariable UUID produtorId) {
    return notificacaoService.proximas(produtorId);
  }

  /** Atrasados do escritório. */
  @PostMapping(CONTADOR + "/todos-atrasados")
  @Operation(summary = "Cobra do cliente as guias vencidas e os pedidos com prazo passado")
  public ResultadoNotificacaoResponse atrasados(@PathVariable UUID produtorId) {
    return notificacaoService.atrasados(produtorId);
  }

  /** Mensagem livre do escritório. */
  @PostMapping(CONTADOR + "/enviar")
  @Operation(summary = "Registra uma mensagem livre ao cliente (1 a 500 caracteres)")
  public ResultadoNotificacaoResponse mensagem(@PathVariable UUID produtorId,
      @Valid @RequestBody MensagemNotificacaoRequest request) {
    return notificacaoService.mensagem(produtorId, request.mensagem());
  }

  /** Histórico do escritório com o cliente. */
  @GetMapping(CONTADOR)
  @Operation(summary = "Notificacoes que o escritorio disparou ao cliente; total em X-Total-Count")
  public ResponseEntity<List<NotificacaoResponse>> listar(@PathVariable UUID produtorId,
      @RequestParam(defaultValue = "0") @Min(0) @Max(NotificacaoService.PAGINA_MAXIMA) int page,
      @RequestParam(defaultValue = "" + NotificacaoService.TAMANHO_PAGINA_PADRAO)
      @Min(1) @Max(NotificacaoService.TAMANHO_PAGINA_MAXIMO) int size) {
    return comTotal(notificacaoService.listar(produtorId, page, size));
  }

  private static ResponseEntity<List<NotificacaoResponse>> comTotal(
      Page<NotificacaoResponse> pagina) {
    return ResponseEntity.ok()
        .header(GuiaFiscalController.HEADER_TOTAL, String.valueOf(pagina.getTotalElements()))
        .body(pagina.getContent());
  }
}
