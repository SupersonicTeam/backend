package br.com.agrocontabil.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Limite de requisições por chave (IP, no uso atual) com balde de fichas — bucket4j, em memória
 * (issue #208; o rate limit geral da #150 reusa esta classe).
 *
 * <p><strong>Teto de memória.</strong> Um mapa sem limite cresceria um balde por IP visto — um
 * atacante com muitos IPs (ou IPv6) esgotaria a memória. O mapa guarda no máximo
 * {@code maxChaves} baldes e descarta o usado há mais tempo (LRU). Descartar um balde é a favor do
 * cliente (ele recomeça cheio), então o teto nunca bloqueia ninguém por engano; só deixa de punir
 * quem saiu da janela de memória.
 *
 * <p><strong>Uma instância só.</strong> Com várias réplicas, cada uma conta à parte: o limite
 * efetivo vira {@code limite × réplicas}. Aceitável no escopo do TCC; um armazenamento comum
 * (bucket4j + Redis/JDBC) resolve quando houver mais de uma réplica.
 */
public class LimitadorTaxa {

  private final long capacidade;
  private final Duration janela;
  private final Map<String, Bucket> baldes;

  /**
   * Cria o limitador.
   *
   * @param capacidade requisições permitidas por janela
   * @param janela     janela de reposição (as fichas voltam aos poucos ao longo dela)
   * @param maxChaves  quantas chaves acompanhar ao mesmo tempo
   */
  public LimitadorTaxa(long capacidade, Duration janela, int maxChaves) {
    this.capacidade = capacidade;
    this.janela = janela;
    this.baldes = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<String, Bucket> maisAntiga) {
        return size() > maxChaves;
      }
    });
  }

  /**
   * Consome uma ficha da chave.
   *
   * @param chave identificador do cliente (IP)
   * @return {@code 0} se a requisição pode seguir; senão, os segundos até haver ficha (≥ 1)
   */
  public long tentar(String chave) {
    Bucket balde = baldes.computeIfAbsent(chave, k -> novoBalde());
    ConsumptionProbe sonda = balde.tryConsumeAndReturnRemaining(1);
    if (sonda.isConsumed()) {
      return 0;
    }
    return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(sonda.getNanosToWaitForRefill()) + 1);
  }

  private Bucket novoBalde() {
    return Bucket.builder()
        .addLimit(Bandwidth.builder().capacity(capacidade).refillGreedy(capacidade, janela)
            .build())
        .build();
  }
}
