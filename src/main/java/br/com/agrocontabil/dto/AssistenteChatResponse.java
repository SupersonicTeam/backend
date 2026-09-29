package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * Resposta da Ceres (issues #142 e #157). O contrato já nasce no formato final da #157, para o chat
 * do Angular não mudar quando as rotas de legislação e mista chegarem.
 *
 * @param resposta     texto da resposta
 * @param rota         camada acionada: {@code DADOS}, {@code LEGISLACAO}, {@code MISTO} ou
 *                     {@code FORA_DE_ESCOPO}
 * @param citacoes     trechos de lei que fundamentam a resposta (rota de legislação, #156)
 * @param fontesDados  funções consultadas — "calculado a partir das suas notas"
 * @param modoFallback {@code true} quando o modelo estava indisponível e a resposta é só texto de
 *                     lei recuperado (#146)
 */
@Schema(description = "Resposta da Ceres, a assistente fiscal")
public record AssistenteChatResponse(
    @Schema(description = "Texto da resposta") String resposta,
    @Schema(description = "Camada acionada", example = "DADOS") String rota,
    @Schema(description = "Trechos de lei citados") List<Citacao> citacoes,
    @Schema(description = "Funcoes de dados consultadas") List<FonteDado> fontesDados,
    @Schema(description = "Resposta sem geracao, so trechos de lei") boolean modoFallback) {

  /**
   * Trecho de lei citado (issue #156). A referência é montada pelo backend a partir dos metadados
   * do trecho — o modelo nunca a escreve.
   *
   * @param norma       ex.: {@code LC 214/2025}
   * @param dispositivo ex.: {@code art. 138, § 1º}
   * @param url         link oficial (Planalto)
   * @param trecho      texto do dispositivo
   */
  public record Citacao(String norma, String dispositivo, String url, String trecho) {}

  /**
   * Função de dados consultada.
   *
   * @param funcao     nome da função (ex.: {@code totalComprasPorInsumo})
   * @param parametros parâmetros usados (ex.: {@code {"dataInicio": "2026-01-01"}})
   */
  public record FonteDado(String funcao, Map<String, String> parametros) {}
}
