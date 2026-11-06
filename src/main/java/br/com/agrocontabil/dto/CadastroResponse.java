package br.com.agrocontabil.dto;

import br.com.agrocontabil.security.PapelUsuario;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Conta criada (issue #205). Sem senha, hash ou documento.
 *
 * @param usuarioId id do produtor ou do contador criado — o mesmo usado nos demais endpoints
 * @param papel     papel da conta
 * @param email     e-mail de login, já normalizado
 */
@Schema(description = "Conta criada no cadastro, sem senha nem documento")
public record CadastroResponse(
    @Schema(description = "Id do produtor ou do contador criado", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID usuarioId,
    @Schema(description = "Papel da conta", example = "PRODUTOR")
    PapelUsuario papel,
    @Schema(description = "E-mail de login, normalizado", example = "produtor@exemplo.com.br")
    String email) {
}
