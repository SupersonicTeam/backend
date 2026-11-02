package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Citações que o backend acrescenta por dispositivo exato, com o texto da base de legislação: as
 * do {@link FundamentoRetencao} (RN009) e as do {@link TextosDoBackend} (#283). Um lugar só para a
 * busca, o registro na auditoria e a junção sem repetir referência.
 */
final class CitacoesFixas {

  private static final Logger log = LoggerFactory.getLogger(CitacoesFixas.class);

  private CitacoesFixas() {}

  /**
   * Trechos vigentes dos dispositivos, como citações; sem a base, lista vazia (o texto já nomeia
   * o dispositivo). Os ids entram nos trechos citados da auditoria (#144).
   *
   * @param busca        busca por dispositivo exato
   * @param dispositivos {@code "norma|dispositivo"}
   * @param contexto     contexto da pergunta
   * @param assunto      para o log
   * @return citações na ordem pedida (o que falta na base é omitido)
   */
  static List<CitacaoLegal> buscar(BuscaTextualLegislacaoService busca, List<String> dispositivos,
      ContextoAssistente contexto, String assunto) {
    if (dispositivos.isEmpty()) {
      return List.of();
    }
    contexto.verificarAtiva();
    try {
      List<TrechoRecuperado> trechos = busca.buscarDispositivos(dispositivos, contexto.hoje());
      contexto.registrarTrechosCitados(trechos.stream().map(TrechoRecuperado::id).toList());
      if (trechos.size() < dispositivos.size()) {
        log.warn("{}: {} de {} dispositivos na base de legislacao", assunto, trechos.size(),
            dispositivos.size());
      }
      return trechos.stream().map(TrechoRecuperado::comoCitacao).toList();
    } catch (AssistenteIndisponivelException e) {
      log.warn("{} sem citacoes: base de legislacao indisponivel", assunto);
      return List.of();
    }
  }

  /** Citações da rota primeiro, sem repetir a mesma referência. */
  static List<CitacaoLegal> juntar(List<CitacaoLegal> daRota, List<CitacaoLegal> fixas) {
    Map<String, CitacaoLegal> porReferencia = new LinkedHashMap<>();
    daRota.forEach(c -> porReferencia.putIfAbsent(c.referencia(), c));
    fixas.forEach(c -> porReferencia.putIfAbsent(c.referencia(), c));
    return new ArrayList<>(porReferencia.values());
  }
}
