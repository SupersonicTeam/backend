package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.SenhaRequests;
import br.com.agrocontabil.security.CookieRefresh;
import br.com.agrocontabil.service.SenhaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Recuperação e troca de senha (issue #209). O controller só delega. */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/auth/senha")
@Tag(name = "Autenticacao")
public class SenhaController {

  private final SenhaService senhaService;
  private final CookieRefresh cookieRefresh;

  public SenhaController(SenhaService senhaService, CookieRefresh cookieRefresh) {
    this.senhaService = senhaService;
    this.cookieRefresh = cookieRefresh;
  }

  /**
   * Pede o link de redefinição de senha.
   *
   * @param request e-mail
   * @return 202 sempre
   */
  @PostMapping("/esqueci")
  @SecurityRequirements
  @Operation(summary = "Pede o link de redefinicao de senha",
      description = "Publico. Responde 202 sempre, tenha ou nao o e-mail uma conta (e no mesmo "
          + "tempo): a resposta nunca diz quem e cliente. Se houver conta ativa, um link de uso "
          + "unico, valido por 30 minutos, vai para o e-mail; pedir de novo invalida o anterior.")
  @ApiResponse(responseCode = "202", description = "Pedido recebido")
  public ResponseEntity<Void> esqueci(@Valid @RequestBody SenhaRequests.Esqueci request) {
    senhaService.solicitarRedefinicao(request.email());
    return ResponseEntity.accepted().build();
  }

  /**
   * Redefine a senha com o token do link. Encerra todas as sessões.
   *
   * @param request token e senha nova
   * @return 204, com o cookie de refresh apagado
   */
  @PostMapping("/redefinir")
  @SecurityRequirements
  @Operation(summary = "Redefine a senha com o token do e-mail",
      description = "Publico. O token vem do fragmento do link (#token=). Encerra todas as sessoes "
          + "da conta: e preciso entrar de novo com a senha nova.")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Senha redefinida"),
      @ApiResponse(responseCode = "400",
          description = "Link invalido/expirado/usado ou senha nova fraca",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<Void> redefinir(@Valid @RequestBody SenhaRequests.Redefinir request) {
    senhaService.redefinir(request.token(), request.novaSenha());
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookieRefresh.apagar())
        .build();
  }

  /**
   * Troca a senha de quem está autenticado. Encerra todas as sessões.
   *
   * @param request senha atual e senha nova
   * @return 204, com o cookie de refresh apagado
   */
  @PostMapping("/alterar")
  @Operation(summary = "Troca a senha (autenticado)",
      description = "Confere a senha atual (com o mesmo bloqueio por tentativas do login) e "
          + "encerra todas as sessoes, inclusive a desta requisicao.")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Senha alterada"),
      @ApiResponse(responseCode = "400", description = "Senha atual incorreta ou senha nova fraca",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "401", description = "Sem identidade valida",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<Void> alterar(@Valid @RequestBody SenhaRequests.Alterar request) {
    senhaService.alterar(request.senhaAtual(), request.novaSenha());
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookieRefresh.apagar())
        .build();
  }
}
