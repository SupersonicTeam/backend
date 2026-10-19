package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.dto.LoginRequest;
import br.com.agrocontabil.dto.LoginResponse;
import br.com.agrocontabil.dto.UsuarioLogadoResponse;
import br.com.agrocontabil.security.CookieRefresh;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.service.LoginService;
import br.com.agrocontabil.service.SessaoService;
import br.com.agrocontabil.service.SessaoService.SessaoEmitida;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cadastro e autenticação (Sprint 8). O controller só delega; as regras ficam nos services.
 *
 * <p>Só com servidor web, como o {@code LoginService} e o {@code JwtService}: os comandos sem web
 * (ingestão da Ceres, avaliação) não atendem requisição nem emitem token.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/auth")
@Tag(name = "Autenticacao", description = "Cadastro de conta, login e identidade autenticada")
public class AutenticacaoController {

  private final CadastroService cadastroService;
  private final LoginService loginService;
  private final SessaoService sessaoService;
  private final CookieRefresh cookieRefresh;

  public AutenticacaoController(CadastroService cadastroService, LoginService loginService,
      SessaoService sessaoService, CookieRefresh cookieRefresh) {
    this.cadastroService = cadastroService;
    this.loginService = loginService;
    this.sessaoService = sessaoService;
    this.cookieRefresh = cookieRefresh;
  }

  /**
   * Cria a conta de um produtor ou contador com e-mail e senha (issue #205).
   *
   * @param request dados do cadastro
   * @return 201 com o id da identidade criada
   */
  @PostMapping("/cadastro")
  @SecurityRequirements
  @Operation(summary = "Cria uma conta de produtor ou contador",
      description = "Publico. CPF/CNPJ obrigatorio (so digitos, com digito verificador). Senha de "
          + "10 caracteres a 72 bytes, fora da lista de senhas comuns e sem conter o e-mail ou o "
          + "documento. E-mail ou documento ja cadastrados respondem 409 sem dizer qual.")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Conta criada"),
      @ApiResponse(responseCode = "400", description = "Dados invalidos ou senha fraca",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409", description = "E-mail ou CPF/CNPJ ja cadastrado",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<CadastroResponse> cadastrar(@Valid @RequestBody CadastroRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(cadastroService.cadastrar(request));
  }

  /**
   * Troca e-mail e senha pelo token de acesso (issue #206).
   *
   * <p>{@code Cache-Control: no-store} (RFC 6749 §5.1): resposta com credencial não pode ficar em
   * cache de navegador nem de proxy.
   *
   * @param request e-mail e senha
   * @return token de acesso no corpo; refresh token no cookie {@code HttpOnly}
   */
  @PostMapping("/login")
  @SecurityRequirements
  @Operation(summary = "Login por e-mail e senha",
      description = "Publico. Devolve o JWT de acesso (validade de 15 minutos) a enviar em "
          + "Authorization: Bearer, e grava o refresh token num cookie HttpOnly (Path=/api/auth). "
          + "E-mail sem conta, senha errada e conta desativada respondem o mesmo 401 "
          + "CREDENCIAIS_INVALIDAS.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Token emitido e cookie de refresh gravado"),
      @ApiResponse(responseCode = "401", description = "E-mail ou senha incorretos",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<LoginResponse> entrar(@Valid @RequestBody LoginRequest request) {
    return comSessao(loginService.entrar(request));
  }

  /**
   * Troca o refresh token do cookie por um par novo (issue #207). O usado deixa de valer; um usado
   * que volta encerra a sessão inteira (detecção de roubo).
   *
   * @param request requisição, de onde vem o cookie
   * @return token de acesso novo no corpo; refresh novo no cookie
   */
  @PostMapping("/refresh")
  @SecurityRequirements
  @Operation(summary = "Renova o token de acesso pelo cookie de refresh",
      description = "Publico (o access token pode ja ter expirado). Le o cookie HttpOnly gravado "
          + "no login, devolve um access token novo e troca o cookie. Refresh ausente, vencido, "
          + "revogado ou ja usado responde 401 SESSAO_INVALIDA; o reuso de um refresh ja trocado "
          + "encerra todas as renovacoes daquele login.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Par renovado"),
      @ApiResponse(responseCode = "401", description = "Sessao expirada ou encerrada",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<LoginResponse> renovar(HttpServletRequest request) {
    return comSessao(sessaoService.renovar(cookieRefresh.ler(request).orElse(null)));
  }

  /**
   * Encerra a sessão deste dispositivo e apaga o cookie (issue #207). Idempotente.
   *
   * @param request requisição, de onde vem o cookie
   * @return 204 com o cookie apagado
   */
  @PostMapping("/logout")
  @SecurityRequirements
  @Operation(summary = "Sai deste dispositivo",
      description = "Publico (funciona com o access token expirado). Revoga o refresh do cookie e "
          + "o apaga. O access token em maos segue valido ate expirar (no maximo 15 minutos): o "
          + "frontend deve descarta-lo.")
  @ApiResponse(responseCode = "204", description = "Sessao encerrada")
  public ResponseEntity<Void> sair(HttpServletRequest request) {
    sessaoService.encerrar(cookieRefresh.ler(request).orElse(null));
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookieRefresh.apagar())
        .build();
  }

  /**
   * Encerra todas as sessões de quem está autenticado (issue #207).
   *
   * @return 204 com o cookie apagado
   */
  @PostMapping("/logout-todos")
  @Operation(summary = "Sai de todos os dispositivos",
      description = "Revoga todos os refresh tokens da conta e invalida na hora todos os access "
          + "tokens ja emitidos, inclusive o desta requisicao.")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Todas as sessoes encerradas"),
      @ApiResponse(responseCode = "401", description = "Sem identidade valida",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<Void> sairDeTodos() {
    loginService.sairDeTodos();
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookieRefresh.apagar())
        .build();
  }

  /**
   * Quem está autenticado nesta requisição (issue #206).
   *
   * @return id, papel e nome
   */
  @GetMapping("/me")
  @Operation(summary = "Identidade autenticada",
      description = "Id, papel e nome de quem fez a requisicao. Util para o frontend conferir o "
          + "token na abertura da aplicacao.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Identidade da requisicao"),
      @ApiResponse(responseCode = "401", description = "Sem identidade valida",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<UsuarioLogadoResponse> usuarioLogado() {
    return ResponseEntity.ok(loginService.usuarioLogado());
  }

  /**
   * Resposta com o par de tokens: access no corpo, refresh no cookie.
   *
   * <p>{@code Cache-Control: no-store} (RFC 6749 §5.1): resposta com credencial não pode ficar em
   * cache de navegador nem de proxy.
   */
  private ResponseEntity<LoginResponse> comSessao(SessaoEmitida sessao) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .header(HttpHeaders.SET_COOKIE,
            cookieRefresh.criar(sessao.refreshToken(), sessao.refreshExpiraEm()))
        .body(sessao.acesso());
  }
}
