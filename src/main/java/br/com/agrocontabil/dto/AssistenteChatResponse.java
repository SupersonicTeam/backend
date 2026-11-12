package br.com.agrocontabil.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * Resposta da Ceres (issues #142 e #157). O contrato já nasce no formato final da #157, para o chat
 * do Angular não mudar quando as rotas de legislação e mista chegarem.
 *
 * @param resposta     texto da resposta
 * @param rota         camada acionada: {@code DADOS}, {@code LEGISLACAO}, {@code MISTO} ou
 *                     {@code FORA_DE_ESCOPO} (fora de escopo e, desde a #493, a conversa leve:
 *                     saudação, agradecimento, despedida, respondida sem modelo)
 * @param citacoes     trechos de lei que fundamentam a resposta (rota de legislação, #156)
 * @param fontesDados  funções consultadas — "calculado a partir das suas notas"
 * @param modoFallback {@code true} quando o modelo estava indisponível e a resposta foi montada sem
 *                     ele: trechos de lei verbatim e/ou atalhos para as telas (#146)
 * @param atalhos      telas sugeridas no modo fallback ({@code NOTAS}, {@code APURACAO},
 *                     {@code CREDITOS}, {@code CALENDARIO}); vazia fora dele (#146)
 * @param perguntaInterpretada a pergunta considerada, quando a continuidade a mudou (#499); fora do
 *                     JSON quando nula
 * @param turno        a troca assinada para devolver no {@code historico} da próxima pergunta
 *                     (#499); fora do JSON quando nula (continuidade desligada, conversa leve,
 *                     recusa, fallback)
 * @param explicacao   explicação curta do modelo depois da resposta de dados, conferida em código
 *                     (#268), ou nula: descartada, fora do prazo, outra rota ou a função desligada
 *                     ({@code app.assistente.explicacao.enabled}, padrão). Fora do JSON quando nula:
 *                     desligada, o JSON é o de antes. No streaming ela vem no evento
 *                     {@code explicacao}, depois da {@code resposta}, nunca dentro dela
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
    List<String> atalhos,
    @Schema(description = "Pergunta considerada, quando a continuidade a mudou")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String perguntaInterpretada,
    @Schema(description = "Troca assinada para devolver no historico da proxima pergunta")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    TurnoConversa turno,
    @Schema(description = "Explicacao curta do modelo depois da resposta de dados, conferida em "
        + "codigo (#268); ausente quando descartada, fora do prazo ou desligada",
        nullable = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String explicacao) {

  /**
   * Resposta sem continuidade (#499) nem explicação (#268), como antes das duas.
   *
   * @param resposta     texto da resposta
   * @param rota         camada acionada
   * @param citacoes     trechos de lei citados
   * @param fontesDados  funções consultadas
   * @param modoFallback resposta montada sem o modelo
   * @param atalhos      telas sugeridas no modo fallback
   */
  public AssistenteChatResponse(String resposta, String rota, List<Citacao> citacoes,
      List<FonteDado> fontesDados, boolean modoFallback, List<String> atalhos) {
    this(resposta, rota, citacoes, fontesDados, modoFallback, atalhos, null, null, null);
  }

  /**
   * Resposta com a continuidade (#499), sem explicação.
   *
   * @param resposta             texto da resposta
   * @param rota                 camada acionada
   * @param citacoes             trechos de lei citados
   * @param fontesDados          funções consultadas
   * @param modoFallback         resposta montada sem o modelo
   * @param atalhos              telas sugeridas no modo fallback
   * @param perguntaInterpretada pergunta considerada, ou nula
   * @param turno                troca assinada, ou nula
   */
  public AssistenteChatResponse(String resposta, String rota, List<Citacao> citacoes,
      List<FonteDado> fontesDados, boolean modoFallback, List<String> atalhos,
      String perguntaInterpretada, TurnoConversa turno) {
    this(resposta, rota, citacoes, fontesDados, modoFallback, atalhos, perguntaInterpretada,
        turno, null);
  }

  /**
   * A mesma resposta com a explicação aceita (#268). A {@code resposta} não muda: continua
   * completa e correta sozinha.
   *
   * @param texto explicação conferida
   * @return a resposta com a explicação
   */
  public AssistenteChatResponse comExplicacao(String texto) {
    return new AssistenteChatResponse(resposta, rota, citacoes, fontesDados, modoFallback, atalhos,
        perguntaInterpretada, turno, texto);
  }

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
