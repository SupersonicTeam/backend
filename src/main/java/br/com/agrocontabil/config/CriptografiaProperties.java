package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Criptografia de dados pessoais em repouso (issue #147, RNF004), em
 * {@code app.seguranca.cripto}.
 *
 * <p>A validação da chave (presença, tamanho, chave de desenvolvimento fora de dev) fica na
 * {@code CifraDeterministica}: sem chave válida a aplicação não sobe.
 *
 * @param chave                            chave mestra AES-256 em Base64 (32 bytes), de
 *                                         {@code CRIPTO_CHAVE}
 * @param permitirChaveDeDesenvolvimento   sem chave, usar a chave fixa e pública de
 *                                         desenvolvimento. Só {@code dev} e {@code test} ligam:
 *                                         uma chave efêmera deixaria o banco de dev ilegível a
 *                                         cada reinício
 * @param migrarLegado                     cifrar, na subida, as linhas gravadas antes da #147
 */
@ConfigurationProperties(prefix = "app.seguranca.cripto")
public record CriptografiaProperties(
    String chave,
    @DefaultValue("false") boolean permitirChaveDeDesenvolvimento,
    @DefaultValue("true") boolean migrarLegado) {

  /** Sem a chave: o {@code toString} padrão do record a poria em log de configuração. */
  @Override
  public String toString() {
    return "CriptografiaProperties[permitirChaveDeDesenvolvimento="
        + permitirChaveDeDesenvolvimento + ", migrarLegado=" + migrarLegado + "]";
  }
}
