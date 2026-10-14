package br.com.agrocontabil.config.avaliacao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.assistente.orquestracao.ResultadoOrquestracao;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Roda as perguntas da avaliação uma por vez, com prazo (issue #158). Falha nunca interrompe a
 * avaliação: vira o desfecho que a API daria (tempo esgotado, modelo indisponível, erro).
 *
 * <p>Depois de um tempo esgotado, a thread da pergunta pode continuar presa na leitura do modelo.
 * O executor é trocado para a pergunta seguinte não esperar por ela: a espera entraria na latência
 * da próxima pergunta e poderia estourar o prazo dela também.
 */
final class ExecucaoComPrazo implements AutoCloseable {

  /** O que responde uma pergunta: o {@code OrquestradorService.responder} na avaliação. */
  @FunctionalInterface
  interface Respondedor {
    ResultadoOrquestracao responder(
        String pergunta, ContextoAssistente contexto, Consumer<Intencao> aoDecidirRota);
  }

  private final Respondedor respondedor;
  private final Duration prazo;
  private ExecutorService executor;
  private int executoresCriados;

  /**
   * @param respondedor quem responde
   * @param prazo       tempo máximo de uma pergunta
   */
  ExecucaoComPrazo(Respondedor respondedor, Duration prazo) {
    this.respondedor = respondedor;
    this.prazo = prazo;
    this.executor = novoExecutor();
  }

  /**
   * Responde uma pergunta dentro do prazo.
   *
   * @param pergunta pergunta
   * @param contexto contexto da pergunta (as fontes registradas entram no resultado)
   * @return o que aconteceu, com a latência medida do envio à resposta
   */
  ExecucaoPergunta executar(String pergunta, ContextoAssistente contexto) {
    AtomicReference<Intencao> rota = new AtomicReference<>();
    long inicio = System.nanoTime();
    Future<ResultadoOrquestracao> futuro =
        executor.submit(() -> respondedor.responder(pergunta, contexto, rota::set));
    try {
      ResultadoOrquestracao r = futuro.get(prazo.toMillis(), TimeUnit.MILLISECONDS);
      return new ExecucaoPergunta(r.rota(), r.texto(), r.desfecho(), r.citacoes(),
          contexto.fontes(), r.modoFallback(), decorrido(inicio), null);
    } catch (TimeoutException e) {
      contexto.interromper(DesfechoInteracao.TEMPO_ESGOTADO);
      futuro.cancel(true);
      executor.shutdownNow();
      executor = novoExecutor();
      return falha(rota, contexto, DesfechoInteracao.TEMPO_ESGOTADO, inicio, e);
    } catch (ExecutionException e) {
      DesfechoInteracao desfecho = e.getCause() instanceof AssistenteIndisponivelException
          ? DesfechoInteracao.MODELO_INDISPONIVEL
          : DesfechoInteracao.ERRO;
      return falha(rota, contexto, desfecho, inicio, e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Avaliacao interrompida", e);
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }

  private ExecutorService novoExecutor() {
    int numero = ++executoresCriados;
    return Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(r, "agro-avaliacao-" + numero);
      thread.setDaemon(true);
      return thread;
    });
  }

  private static ExecucaoPergunta falha(AtomicReference<Intencao> rota,
      ContextoAssistente contexto, DesfechoInteracao desfecho, long inicio, Throwable e) {
    return new ExecucaoPergunta(rota.get(), null, desfecho, List.of(), contexto.fontes(),
        false, decorrido(inicio), e.getClass().getSimpleName());
  }

  private static long decorrido(long inicioNs) {
    return Duration.ofNanos(System.nanoTime() - inicioNs).toMillis();
  }
}
