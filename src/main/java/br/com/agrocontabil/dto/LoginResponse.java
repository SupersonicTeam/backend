package br.com.agrocontabil.dto;

import br.com.agrocontabil.security.PapelUsuario;
import java.time.Instant;
import java.util.UUID;

/**
 * Token de acesso emitido no login (issue #206).
 *
 * @param accessToken JWT a enviar em {@code Authorization: Bearer}
 * @param tokenType   sempre {@code Bearer}
 * @param expiraEm    instante de expiração do token
 * @param papel       papel da conta
 * @param usuarioId   id do produtor ou do contador
 */
public record LoginResponse(
    String accessToken, String tokenType, Instant expiraEm, PapelUsuario papel, UUID usuarioId) {

  /** Tipo de token do RFC 6750. */
  public static final String TIPO_BEARER = "Bearer";

  /** Sem o token: ele é a credencial em si. */
  @Override
  public String toString() {
    return "LoginResponse[papel=" + papel + ", expiraEm=" + expiraEm + "]";
  }
}
