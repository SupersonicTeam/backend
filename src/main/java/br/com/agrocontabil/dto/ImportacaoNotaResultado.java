package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Resultado da importacao de um unico arquivo XML no lote (issue #19 — UC002).
 *
 * <p>O endpoint {@code POST /api/notas/importar} aceita varios arquivos e devolve uma lista
 * destes resultados — um por arquivo. O lote e resiliente: um arquivo invalido gera um
 * resultado com {@code sucesso=false} e um {@code motivo}, sem derrubar os demais nem
 * resultar em HTTP 500.
 *
 * @param arquivo    nome do arquivo enviado (para o cliente correlacionar o resultado)
 * @param sucesso    {@code true} se a nota foi importada e persistida
 * @param status     resultado detalhado da importacao ({@link Status})
 * @param notaId     id da nota criada quando {@code sucesso=true}; {@code null} caso contrario
 * @param chaveAcesso chave de acesso da NF-e importada, quando disponivel; {@code null} se
 *                    o XML nem chegou a ser parseado
 * @param motivo     descricao amigavel do resultado (motivo da falha, quando houver).
 *                   Nunca contem CPF/CNPJ completo (LGPD).
 */
@Schema(description = "Resultado da importacao de um arquivo XML no lote")
public record ImportacaoNotaResultado(

    @Schema(description = "Nome do arquivo enviado (para correlacionar o resultado)",
        example = "NFe35240612345678000190550010000000011000000010.xml")
    String arquivo,

    @Schema(description = "true se a nota foi importada e persistida", example = "true")
    boolean sucesso,

    @Schema(description = "Classificacao detalhada do resultado", example = "IMPORTADA")
    Status status,

    @Schema(description = "Id da nota criada quando sucesso=true; null caso contrario",
        example = "3f9a1c2e-5b7d-4e8a-9c1f-2a3b4c5d6e7f")
    UUID notaId,

    @Schema(description = "Chave de acesso da NF-e importada, quando disponivel",
        example = "35240612345678000190550010000000011000000010")
    String chaveAcesso,

    @Schema(description = "Descricao amigavel do resultado (motivo da falha, quando houver). "
        + "Nunca contem CPF/CNPJ completo (LGPD).",
        example = "Nota importada com sucesso.")
    String motivo) {

  /** Classificacao do resultado da importacao de um arquivo. */
  public enum Status {
    /** Nota importada e persistida com sucesso. */
    IMPORTADA,
    /** Ja existe uma nota com a mesma chave de acesso para o produtor (dedup). */
    DUPLICADA,
    /** XML invalido: malformado, sem NFe, reprovado no XSD ou dados estruturais ausentes. */
    INVALIDA
  }

  /** Fabrica um resultado de sucesso (nota importada). */
  public static ImportacaoNotaResultado importada(String arquivo, UUID notaId, String chave) {
    return new ImportacaoNotaResultado(
        arquivo, true, Status.IMPORTADA, notaId, chave, "Nota importada com sucesso.");
  }

  /** Fabrica um resultado de duplicidade (nota ja existente pela chave de acesso). */
  public static ImportacaoNotaResultado duplicada(String arquivo, String chave) {
    return new ImportacaoNotaResultado(
        arquivo, false, Status.DUPLICADA, null, chave,
        "Ja existe uma nota importada com esta chave de acesso.");
  }

  /** Fabrica um resultado de arquivo invalido. */
  public static ImportacaoNotaResultado invalida(String arquivo, String motivo) {
    return new ImportacaoNotaResultado(arquivo, false, Status.INVALIDA, null, null, motivo);
  }
}
