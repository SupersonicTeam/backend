package br.com.agrocontabil.support;

import br.com.agrocontabil.event.NotaCanceladaEvent;
import br.com.agrocontabil.event.NotaEmitidaEvent;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.event.EventListener;

/**
 * Conta os eventos de domínio de transmissão efetivamente publicados.
 *
 * <p>É a prova que mais importa para a apuração: o {@code ApuracaoService} escuta estes eventos
 * para recalcular débito e crédito do período, então um evento duplicado vira imposto apurado em
 * duplicidade, e um evento perdido vira imposto apurado a menor.
 *
 * <p>É um {@link TestComponent}: importe explicitamente com
 * {@code @Import(ContadorDeEventos.class)}.
 */
@TestComponent
public class ContadorDeEventos {

  private final AtomicInteger emitidas = new AtomicInteger();
  private final AtomicInteger canceladas = new AtomicInteger();

  @EventListener
  void aoEmitir(NotaEmitidaEvent evento) {
    emitidas.incrementAndGet();
  }

  @EventListener
  void aoCancelar(NotaCanceladaEvent evento) {
    canceladas.incrementAndGet();
  }

  /** Zera as contagens (chamar no {@code @BeforeEach}). */
  public void zerar() {
    emitidas.set(0);
    canceladas.set(0);
  }

  /** Quantidade de {@link NotaEmitidaEvent} publicados desde o último {@link #zerar()}. */
  public int emitidas() {
    return emitidas.get();
  }

  /** Quantidade de {@link NotaCanceladaEvent} publicados desde o último {@link #zerar()}. */
  public int canceladas() {
    return canceladas.get();
  }
}
