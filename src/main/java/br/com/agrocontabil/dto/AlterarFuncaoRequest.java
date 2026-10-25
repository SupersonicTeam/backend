package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.FuncaoEscritorio;
import jakarta.validation.constraints.NotNull;

/**
 * Troca da função de um membro (issue #228).
 *
 * @param funcao função nova
 */
public record AlterarFuncaoRequest(@NotNull FuncaoEscritorio funcao) {
}
