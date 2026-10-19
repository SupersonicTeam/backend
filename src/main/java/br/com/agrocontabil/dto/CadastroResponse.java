package br.com.agrocontabil.dto;

import br.com.agrocontabil.security.PapelUsuario;
import java.util.UUID;

/**
 * Conta criada (issue #205). Sem senha, hash ou documento.
 *
 * @param usuarioId id do produtor ou do contador criado — o mesmo usado nos demais endpoints
 * @param papel     papel da conta
 * @param email     e-mail de login, já normalizado
 */
public record CadastroResponse(UUID usuarioId, PapelUsuario papel, String email) {
}
