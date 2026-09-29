package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.AssistenteChatRequest;
import br.com.agrocontabil.dto.AssistenteChatResponse;
import br.com.agrocontabil.service.AssistenteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chat com a Ceres, a assistente fiscal (issue #142).
 *
 * <p>Nesta entrega a resposta é síncrona e só pela rota de dados; o streaming (SSE) e o roteamento
 * completo vêm na issue #157, com o mesmo contrato de resposta.
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
          + "apuracao de IBS/CBS, creditos, notas e vencimentos). Todo numero da resposta vem de "
          + "uma consulta aos dados; as funcoes usadas vem em fontesDados.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Resposta da Ceres"),
      @ApiResponse(responseCode = "400", description = "Pergunta vazia ou longa demais",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "401", description = "Sem identidade valida",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "403", description = "Papel sem acesso (contador)",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "429", description = "Muitas perguntas simultaneas",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "503", description = "Modelo de linguagem indisponivel",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<AssistenteChatResponse> chat(
      @Valid @RequestBody AssistenteChatRequest request) {
    return ResponseEntity.ok(assistenteService.conversar(request));
  }
}
