package br.com.agrocontabil.assistente.auditoria;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.DataSanitizer;
import br.com.agrocontabil.assistente.FonteDado;
import br.com.agrocontabil.assistente.porta.AuditoriaPorta;
import br.com.agrocontabil.assistente.porta.AuditoriaPorta.RegistroInteracao;
import br.com.agrocontabil.model.DesfechoInteracao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Monta o registro de auditoria de cada pergunta à Ceres (issue #144, RF020) e o entrega à
 * {@link AuditoriaPorta}.
 *
 * <p>O que entra: produtor, rota, desfecho, funções usadas com os parâmetros (mascarados pelo
 * {@link DataSanitizer} — o parâmetro de busca pode ecoar o que o usuário digitou), quantas funções
 * o modelo pediu, trechos de lei citados, modelo, latência, tokens e o <em>tamanho</em> da pergunta.
 * No MISTO, também o desfecho de cada parte e se a de lei recebeu a pergunta legal reescrita.
 *
 * <p>O que <strong>não</strong> entra, por desenho: o texto da pergunta e o da resposta. O registro
 * nem tem onde guardá-los.
 */
@Component
public class AuditoriaAssistente {

  private final AuditoriaPorta porta;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  /** Constrói a auditoria sobre a porta de gravação. */
  public AuditoriaAssistente(AuditoriaPorta porta, ObjectMapper objectMapper, Clock clock) {
    this.porta = porta;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /**
   * Registra uma interação.
   *
   * @param contexto        contexto da pergunta (produtor, funções, trechos, tokens)
   * @param rota            camada que respondeu
   * @param desfecho        como terminou
   * @param modelo          {@code provedor/modelo}
   * @param latenciaMs      duração total da pergunta
   * @param tamanhoPergunta tamanho da pergunta, em caracteres
   */
  public void registrar(ContextoAssistente contexto, String rota, DesfechoInteracao desfecho,
      String modelo, long latenciaMs, int tamanhoPergunta) {
    porta.registrar(new RegistroInteracao(
        contexto.produtorId(),
        rota,
        desfecho,
        contexto.desfechoDados(),
        contexto.desfechoLegislacao(),
        contexto.perguntaLeiReescrita(),
        funcoesJson(contexto.fontes()),
        contexto.chamadasDeFuncao(),
        contexto.trechosCitados().isEmpty() ? null : json(contexto.trechosCitados()),
        modelo,
        latenciaMs,
        contexto.tokensEntrada(),
        contexto.tokensSaida(),
        contexto.tokensEstimados(),
        tamanhoPergunta,
        OffsetDateTime.now(clock)));
  }

  private String funcoesJson(List<FonteDado> fontes) {
    List<Map<String, Object>> funcoes = fontes.stream()
        .map(fonte -> {
          Map<String, String> parametros = new LinkedHashMap<>();
          fonte.parametros().forEach((k, v) -> parametros.put(k, DataSanitizer.mascarar(v)));
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("funcao", fonte.funcao());
          item.put("parametros", parametros);
          return item;
        })
        .toList();
    return json(funcoes);
  }

  private String json(Object valor) {
    try {
      return objectMapper.writeValueAsString(valor);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Falha ao serializar a auditoria", e);
    }
  }
}
