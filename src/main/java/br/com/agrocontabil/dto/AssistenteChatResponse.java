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
 * @param modoFallback {@code true} quando o modelo estava indisponível e a resposta foi montada sem
 *                     ele: trechos de lei verbatim e/ou atalhos para as telas (#146)
 * @param atalhos      telas sugeridas no modo fallback ({@code NOTAS}, {@code APURACAO},
 *                     {@code CREDITOS}, {@code CALENDARIO}); vazia fora dele (#146)
 */
@Schema(description = "Resposta da Ceres, a assistente fiscal")
public record AssistenteChatResponse(
    @Schema(description = "Texto da resposta") String resposta,
    @Schema(description = "Camada acionada", example = "DADOS") String rota,
    @Schema(description = "Trechos de lei citados") List<Citacao> citacoes,
    @Schema(description = "Funcoes de dados consultadas") List<FonteDado> fontesDados,
    @Schema(description = "Resposta montada sem o modelo (trechos de lei e atalhos)")
    boolean modoFallback,
    @Schema(description = "Telas sugeridas no modo fallback", example = "[\"CALENDARIO\"]")
    List<String> atalhos) {

  /**
   * Trecho de lei citado (issue #156). A referência é montada pelo backend a partir dos metadados
   * do trecho — o modelo nunca a escreve.
   *
   * @param norma       ex.: {@code LC 214/2025}
   * @param dispositivo ex.: {@code art. 138, § 1º}
   * @param url         link oficial (Planalto)
   * @param trecho      texto do dispositivo
   */
  @Schema(description = "Trecho de lei citado pela resposta")
  public record Citacao(
      @Schema(description = "Norma citada", example = "LC 214/2025") String norma,
      @Schema(description = "Dispositivo da norma", example = "art. 138, § 2º") String dispositivo,
      @Schema(description = "Link para o texto da norma") String url,
      @Schema(description = "Texto do dispositivo, literal") String trecho) {}

  /**
   * Função de dados consultada.
   *
   * @param funcao     nome da função (ex.: {@code totalComprasPorInsumo})
   * @param parametros parâmetros usados (ex.: {@code {"dataInicio": "2026-01-01"}})
   * @param origem     de onde veio o dado (#342): quem escolhe o selo na tela é o frontend, com
   *                   base neste valor, não no nome da função
   */
  @Schema(description = "Função de dados consultada para montar a resposta")
  public record FonteDado(
      @Schema(description = "Nome da função", example = "totalComprasPorInsumo") String funcao,
      @Schema(description = "Parâmetros usados na consulta") Map<String, String> parametros,
      @Schema(description = "Origem do dado consultado", example = "PRODUTOR")
      OrigemFonte origem) {}

  /**
   * Origem do dado de uma {@link FonteDado} (#342). Sem rótulo em português: quem traduz é o
   * frontend. Valor novo só se acrescenta; o frontend trata valor desconhecido com um selo neutro,
   * sem afirmar que o dado é do produtor.
   */
  public enum OrigemFonte {
    /** Calculado a partir dos dados do produtor (notas, guias, apuração, créditos...). */
    PRODUTOR,
    /** Consultado em tabela pública oficial (ex.: classificação tributária do IBS/CBS). */
    TABELA_OFICIAL
  }
}
