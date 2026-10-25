package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.FuncaoEscritorio;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

/**
 * Escritório do contador autenticado (issue #228).
 *
 * @param id          escritório
 * @param nome        nome
 * @param cnpj        CNPJ (opcional)
 * @param minhaFuncao função de quem pergunta
 * @param membros     quantos membros a equipe tem
 */
public record EscritorioResponse(UUID id, String nome, String cnpj, FuncaoEscritorio minhaFuncao,
    long membros) implements ResultadoAuditavel {

  @JsonIgnore
  @Override
  public Object status() {
    return "ATUALIZADO";
  }

  @JsonIgnore
  @Override
  public UUID recursoAuditado() {
    return id;
  }
}
