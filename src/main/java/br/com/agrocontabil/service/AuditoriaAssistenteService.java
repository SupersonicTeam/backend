package br.com.agrocontabil.service;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.DataSanitizer;
import br.com.agrocontabil.assistente.FonteDado;
import br.com.agrocontabil.model.AssistenteInteracao;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.repository.AssistenteInteracaoRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grava a auditoria de cada pergunta à Ceres (issue #144, RF020).
 *
 * <p>O que entra: produtor, rota, desfecho, funções usadas com os parâmetros (mascarados pelo
 * {@link DataSanitizer} — o parâmetro de busca pode ecoar o que o usuário digitou), quantas funções
 * o modelo pediu, modelo, latência, tokens e o <em>tamanho</em> da pergunta.
 *
 * <p>O que <strong>não</strong> entra, por desenho: o texto da pergunta e o da resposta. A entidade
 * nem tem onde guardá-los.
 *
 * <p>Transação curta e própria, chamada <em>depois</em> da geração: nenhuma conexão do pool fica
 * presa enquanto o modelo responde.
 */
@Service
public class AuditoriaAssistenteService {

  private final AssistenteInteracaoRepository repository;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  /** Constrói o serviço. */
  public AuditoriaAssistenteService(
      AssistenteInteracaoRepository repository, ObjectMapper objectMapper, Clock clock) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /**
   * Registra uma interação.
   *
   * @param contexto         contexto da pergunta (produtor, funções, tokens)
   * @param rota             camada que respondeu
   * @param desfecho         como terminou
   * @param modelo           {@code provedor/modelo}
   * @param latenciaMs       duração total da pergunta
   * @param tamanhoPergunta  tamanho da pergunta, em caracteres
   */
  @Transactional
  public void registrar(ContextoAssistente contexto, String rota, DesfechoInteracao desfecho,
      String modelo, long latenciaMs, int tamanhoPergunta) {
    repository.save(AssistenteInteracao.builder()
        .produtorId(contexto.produtorId())
        .rota(rota)
        .desfecho(desfecho)
        .funcoes(funcoesJson(contexto.fontes()))
        .chamadasFuncao(contexto.chamadasDeFuncao())
        .trechosCitados(contexto.trechosCitados().isEmpty()
            ? null
            : json(contexto.trechosCitados()))
        .modelo(modelo)
        .latenciaMs(latenciaMs)
        .tokensEntrada(contexto.tokensEntrada())
        .tokensSaida(contexto.tokensSaida())
        .tamanhoPergunta(tamanhoPergunta)
        .criadoEm(OffsetDateTime.now(clock))
        .build());
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
