package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.AssistenteChatRequest;
import br.com.agrocontabil.dto.AssistenteChatResponse;
import br.com.agrocontabil.assistente.AssistenteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Chat com a Ceres, a assistente fiscal (issues #142 e #157).
 *
 * <p>Duas formas, o mesmo contrato de resposta: síncrona ({@code POST /chat}) e em streaming
 * ({@code POST /chat/stream}, SSE). A orquestração decide a rota (dados, legislação, as duas ou
 * fora de escopo).
 */
@RestController
@RequestMapping("/api/assistente")
@Tag(name = "Assistente (Ceres)", description = "Perguntas sobre os dados fiscais do produtor")
public class AssistenteController {

  private final AssistenteService assistenteService;

  public AssistenteController(AssistenteService assistenteService) {
    this.assistenteService = assistenteService;
  }

  /**
   * Responde uma pergunta do produtor autenticado.
   *
   * @param request pergunta
   * @return resposta com as fontes de dados consultadas
   */
  @PostMapping("/chat")
  @Operation(
      summary = "Pergunta a Ceres",
      description = "Responde perguntas sobre os dados do produtor autenticado (compras, "
          + "apuracao de IBS/CBS, creditos, notas e vencimentos) e sobre a legislacao do IBS/CBS. "
          + "Todo numero da resposta vem de uma consulta aos dados (fontesDados) e toda afirmacao "
          + "legal cita o dispositivo (citacoes).")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Resposta da Ceres"),
      @ApiResponse(responseCode = "400", description = "Pergunta vazia ou longa demais",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "401", description = "Sem identidade valida",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "403", description = "Papel sem acesso (contador)",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "503", description = "ASSISTENTE_OCUPADO (sem vaga ou "
          + "pergunta anterior em andamento; header Retry-After), ASSISTENTE_TEMPO_ESGOTADO ou "
          + "ASSISTENTE_INDISPONIVEL (modelo fora do ar)",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<AssistenteChatResponse> chat(
      @Valid @RequestBody AssistenteChatRequest request) {
    return ResponseEntity.ok(assistenteService.conversar(request));
  }

  /**
   * Mesma pergunta, com a resposta em streaming (SSE, issue #157).
   *
   * <p>Eventos, nesta ordem: {@code rota} ({@code {"rota":"DADOS"}}), {@code resposta} (o mesmo
   * contrato do {@code POST /chat}, já validado) e {@code fim}; em caso de falha, {@code erro} com o
   * envelope {@code errors[]}. Não há tokens parciais do modelo: números e citações só saem depois
   * de conferidos. Path próprio: com os dois no mesmo path, um {@code Accept: *}{@code /*} (padrão
   * dos navegadores) cairia no streaming.
   *
   * @param request pergunta
   * @param http    requisição (caminho do envelope de erro)
   * @return emissor dos eventos
   */
  @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      summary = "Pergunta a Ceres com resposta em streaming (SSE)",
      description = "Eventos: rota, resposta (contrato do POST /chat, ja validado), fim; ou erro "
          + "com o envelope errors[]. 400/401/403 e 503 ASSISTENTE_OCUPADO sao respondidos antes "
          + "de o stream abrir.")
  public SseEmitter chatStream(
      @Valid @RequestBody AssistenteChatRequest request, HttpServletRequest http) {
    return assistenteService.conversarEmStream(request, http.getRequestURI());
  }
}
