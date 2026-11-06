package br.com.agrocontabil.dto;

import br.com.agrocontabil.security.PapelUsuario;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Token de acesso emitido no login")
public record LoginResponse(
    @Schema(description = "JWT a enviar no cabeçalho Authorization com o esquema Bearer")
    String accessToken,
    @Schema(description = "Tipo do token; sempre Bearer", example = "Bearer")
    String tokenType,
    @Schema(description = "Instante em que o token expira", example = "2026-09-01T13:15:00Z")
    Instant expiraEm,
    @Schema(description = "Papel da conta", example = "PRODUTOR")
    PapelUsuario papel,
    @Schema(description = "Id do produtor ou do contador", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID usuarioId) {

  /** Tipo de token do RFC 6750. */
  public static final String TIPO_BEARER = "Bearer";

  /** Sem o token: ele é a credencial em si. */
  @Override
  public String toString() {
    return "LoginResponse[papel=" + papel + ", expiraEm=" + expiraEm + "]";
  }
}
