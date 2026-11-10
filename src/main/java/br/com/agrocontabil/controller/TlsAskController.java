package br.com.agrocontabil.controller;

import br.com.agrocontabil.security.RotasEscritorio;
import br.com.agrocontabil.service.EnderecoEscritorioService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Ask" do On-Demand TLS do Caddy (issue #464): antes de pedir o certificado de
 * {@code {slug}.agroconia.com.br}, o Caddy pergunta aqui se o domínio é de um escritório. 200
 * emite; 404 recusa.
 *
 * <p>Fora de {@code /api}: o Caddy só repassa {@code /api/*} e o backend não publica porta, então a
 * rota só é alcançável pela rede interna do compose. Ainda assim exige a chave compartilhada
 * ({@code APP_ESCRITORIO_TLS_ASK_CHAVE}), conferida em tempo constante; sem ela, a mesma recusa.
 * Fora do Swagger, sem auditoria e sem corpo: o Caddy olha só o status.
 */
@RestController
@Hidden
public class TlsAskController {

  private final EnderecoEscritorioService enderecoService;

  public TlsAskController(EnderecoEscritorioService enderecoService) {
    this.enderecoService = enderecoService;
  }

  /**
   * O Caddy pode emitir o certificado?
   *
   * @param domain domínio pedido (o Caddy acrescenta {@code domain=} à URL)
   * @param chave  segredo compartilhado
   * @return 200 sem corpo para emitir; 404 sem corpo para recusar
   */
  @GetMapping(RotasEscritorio.TLS_ASK)
  @Operation(summary = "Ask do On-Demand TLS do Caddy (rede interna, fora do Swagger)",
      hidden = true)
  public ResponseEntity<Void> permitido(@RequestParam(required = false) String domain,
      @RequestParam(required = false) String chave) {
    return enderecoService.certificadoPermitido(chave, domain)
        ? ResponseEntity.ok().build()
        : ResponseEntity.notFound().build();
  }
}
