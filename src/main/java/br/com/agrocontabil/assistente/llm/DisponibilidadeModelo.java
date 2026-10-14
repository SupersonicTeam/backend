package br.com.agrocontabil.assistente.llm;

import br.com.agrocontabil.config.AssistenteProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Disjuntor do modelo de linguagem da Ceres (issue #146).
 *
 * <p>Depois de uma falha do modelo (Ollama fora, timeout, recusa do provedor), as perguntas vão
 * direto ao modo fallback durante {@code app.assistente.fallback.pausa-apos-falha}, sem chamar o
 * modelo. Sem a pausa, cada pergunta esperaria a mesma falha antes de receber os trechos de lei —
 * com read-timeout de geração, até 2 minutos cada. Vencida a pausa, a próxima pergunta volta a
 * tentar o modelo: se responder, o disjuntor fecha; se falhar, a pausa recomeça.
 *
 * <p><strong>Por que não o health do Ollama:</strong> o {@link OllamaHealthIndicator} mistura o
 * estado do chat com o do embedding, e existe só quando o embedding é do Ollama. Com o chat num
 * provedor por API e o embedding fora, o health ficaria {@code DEGRADED} e mandaria ao fallback
 * perguntas de dados que o modelo responderia. O disjuntor mede a coisa certa: a chamada de chat.
 *
 * <p>Sem estado por produtor e sem lock: um {@link AtomicLong} com o instante da última falha.
 */
@Component
public class DisponibilidadeModelo {

  private static final long FECHADO = Long.MIN_VALUE;

  private final long pausaNanos;
  private final LongSupplier relogioNanos;
  private final AtomicLong ultimaFalhaNanos = new AtomicLong(FECHADO);

  /** Constrói o disjuntor com o relógio monotônico do sistema. */
  @Autowired
  public DisponibilidadeModelo(AssistenteProperties propriedades) {
    this(propriedades.getFallback().getPausaAposFalha(), System::nanoTime);
  }

  /** Constrói com pausa e relógio explícitos (testes e uso sem Spring). */
  public DisponibilidadeModelo(Duration pausa, LongSupplier relogioNanos) {
    this.pausaNanos = pausa.toNanos();
    this.relogioNanos = relogioNanos;
  }

  /**
   * {@code true} se o modelo pode ser tentado: nenhuma falha recente, ou a pausa já venceu.
   */
  public boolean disponivel() {
    long falha = ultimaFalhaNanos.get();
    return falha == FECHADO || relogioNanos.getAsLong() - falha >= pausaNanos;
  }

  /** O modelo falhou: abre (ou reabre) a pausa a partir de agora. */
  public void registrarFalha() {
    ultimaFalhaNanos.set(relogioNanos.getAsLong());
  }

  /** O modelo respondeu: fecha o disjuntor. */
  public void registrarSucesso() {
    ultimaFalhaNanos.set(FECHADO);
  }
}
