package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.ExclusaoDadosRequest;
import br.com.agrocontabil.dto.PoliticaDadosResponse;
import br.com.agrocontabil.security.CookieRefresh;
import br.com.agrocontabil.service.ExclusaoDadosService;
import br.com.agrocontabil.service.PoliticaDadosService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Privacidade e LGPD (issue #148). O controller só delega. */
@RestController
@ConditionalOnWebApplication
@Tag(name = "Privacidade", description = "Politica de dados e direitos do titular (LGPD)")
public class PrivacidadeController {

  private final PoliticaDadosService politicaService;
  private final ExclusaoDadosService exclusaoService;
  private final CookieRefresh cookieRefresh;

  public PrivacidadeController(PoliticaDadosService politicaService,
      ExclusaoDadosService exclusaoService, CookieRefresh cookieRefresh) {
    this.politicaService = politicaService;
    this.exclusaoService = exclusaoService;
    this.cookieRefresh = cookieRefresh;
  }

  /**
   * Política de tratamento de dados pessoais.
   *
   * @return a política vigente
   */
  @GetMapping("/api/politica-dados")
  @SecurityRequirements
  @Operation(summary = "Politica de tratamento de dados pessoais (LGPD art. 9o)",
      description = "Publico. Dados tratados, finalidades, bases legais, compartilhamento, "
          + "retencao e direitos do titular.")
  public ResponseEntity<PoliticaDadosResponse> politica() {
    return ResponseEntity.ok(politicaService.politica());
  }

  /**
   * Elimina os dados pessoais do titular autenticado (direito ao esquecimento).
   *
   * @param id      id do próprio produtor ou contador
   * @param request senha atual
   * @return 204, com o cookie de refresh apagado
   */
  @DeleteMapping("/api/usuarios/{id}/dados")
  @Operation(summary = "Elimina os dados pessoais do titular (LGPD art. 18, VI)",
      description = "So o proprio titular, com a senha atual. Anonimiza os dados pessoais, "
          + "encerra a conta e todas as sessoes; documentos fiscais seguem guardados pelo prazo "
          + "legal de 5 anos. Irreversivel.")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Dados pessoais eliminados"),
      @ApiResponse(responseCode = "400", description = "Senha atual incorreta",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "404", description = "Id que nao e o do titular autenticado",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<Void> excluirDados(
      @PathVariable UUID id, @Valid @RequestBody ExclusaoDadosRequest request) {
    exclusaoService.excluir(id, request.senha());
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookieRefresh.apagar())
        .build();
  }
}
