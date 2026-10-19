package br.com.agrocontabil.dto;

import br.com.agrocontabil.security.PapelUsuario;
import java.util.UUID;

/**
 * Identidade autenticada na requisição ({@code GET /api/auth/me}, issue #206). Sem documento nem
 * dado de contato.
 *
 * @param usuarioId id do produtor ou do contador
 * @param papel     papel
 * @param nome      nome do produtor ou do escritório
 */
public record UsuarioLogadoResponse(UUID usuarioId, PapelUsuario papel, String nome) {
}
