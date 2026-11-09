package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.EscritorioPublicoResponse;
import br.com.agrocontabil.security.RotasEscritorio;
import br.com.agrocontabil.service.EnderecoEscritorioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endereço próprio do escritório, lado público (issue #464): a tela de login de
 * {@code {slug}.agroconia.com.br} pergunta de quem é o endereço. Só o nome; a mesma resposta 404
 * para tudo que não é servido. Limite próprio de 30 por minuto por IP
 * ({@code LimiteTaxaPublicoFilter}).
 */
@RestController
@Tag(name = "Publico", description = "Dados publicos do endereco proprio do escritorio (#464)")
public class EscritorioPublicoController {

  /** O nome muda pouco: 5 minutos de cache no navegador e em proxies, só no 200. */
  static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

  private final EnderecoEscritorioService enderecoService;

  public EscritorioPublicoController(EnderecoEscritorioService enderecoService) {
    this.enderecoService = enderecoService;
  }

  /**
   * Escritório de um endereço próprio.
   *
   * @param slug rótulo do host
   * @return slug, nome e, em carência, o slug atual
   */
  @GetMapping(RotasEscritorio.PUBLICO)
  @SecurityRequirements
  @Operation(summary = "Escritorio de um endereco proprio, para a tela de login",
      description = "Publico. Devolve so o slug, o nome e, quando o slug pedido esta em carencia "
          + "de um escritorio que tem outro endereco, o slugAtual para redirecionar. Tudo que nao "
          + "e servido responde o mesmo 404 ENDERECO_NAO_ENCONTRADO. Limite de 30 por minuto por "
          + "IP.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Endereco servido"),
      @ApiResponse(responseCode = "404", description = "ENDERECO_NAO_ENCONTRADO",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "429", description = "MUITAS_TENTATIVAS",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<EscritorioPublicoResponse> escritorio(@PathVariable String slug) {
    return ResponseEntity.ok().cacheControl(CACHE).body(enderecoService.publico(slug));
  }
}
