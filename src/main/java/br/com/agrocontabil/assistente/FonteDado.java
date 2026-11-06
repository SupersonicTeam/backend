package br.com.agrocontabil.assistente;

import br.com.agrocontabil.dto.AssistenteChatResponse.OrigemFonte;
import java.util.Map;

/**
 * Uma função consultada para responder a pergunta (issue #142): de onde vieram os números.
 *
 * @param funcao     nome da função (ex.: {@code totalComprasPorInsumo})
 * @param parametros parâmetros efetivamente usados, já validados e normalizados
 * @param origem     dado do produtor ou tabela pública (#342); definido onde a consulta é
 *                   registrada no {@link ContextoAssistente}
 */
public record FonteDado(String funcao, Map<String, String> parametros, OrigemFonte origem) {

  /** Consulta aos dados do produtor (a origem de quase toda função). */
  public FonteDado(String funcao, Map<String, String> parametros) {
    this(funcao, parametros, OrigemFonte.PRODUTOR);
  }
}
