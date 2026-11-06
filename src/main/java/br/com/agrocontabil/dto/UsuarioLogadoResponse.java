package br.com.agrocontabil.dto;

import br.com.agrocontabil.security.PapelUsuario;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Identidade autenticada na requisição ({@code GET /api/auth/me}, issue #206). Sem documento nem
 * dado de contato.
 *
 * @param usuarioId id do produtor ou do contador
 * @param papel     papel
 * @param nome      nome do produtor ou do escritório
 */
@Schema(description = "Identidade autenticada na requisição")
public record UsuarioLogadoResponse(
    @Schema(description = "Id do produtor ou do contador", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID usuarioId,
    @Schema(description = "Papel da conta", example = "PRODUTOR")
    PapelUsuario papel,
    @Schema(description = "Nome do produtor ou do escritório", example = "Fazenda Boa Vista")
    String nome) {
}
