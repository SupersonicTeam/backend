package br.com.agrocontabil.assistente.continuidade;

import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.Produto;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.TratamentoConsultado;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A {@link ResolucaoProduto} sobre a porta do tratamento de produto (#591), a mesma tabela oficial
 * que a Ceres usa para explicar a redução. Só lê a porta: nenhuma transação aberta aqui, e a
 * consulta da porta é curta e sem modelo.
 *
 * <p>Código de NCM com 4 dígitos ou mais resolve sem a porta, pela posição ({@code ncm:1201}); o
 * nome passa pela busca da porta, e só conta o que a tabela reconhece (NCM conhecido). Falha da
 * consulta não derruba a pergunta: o termo apenas não se resolve, e sem resolução não há prova de
 * erro.
 *
 * <p>O resultado de cada termo fica guardado por {@link #VALIDADE_MS} (o acerto e a falha): a
 * decisão de custo, tomada antes da reserva da cota, e o orquestrador perguntam o mesmo termo na
 * mesma pergunta e têm de ouvir a mesma resposta, senão uma falha passageira da consulta faria um
 * decidir "sem modelo" e o outro chamar o modelo sem reserva.
 */
@Component
public class ResolucaoProdutoPelaTabela implements ResolucaoProduto {

  /** Quanto tempo a resposta de um termo vale. */
  static final long VALIDADE_MS = 60_000L;

  private static final Logger log = LoggerFactory.getLogger(ResolucaoProdutoPelaTabela.class);
  private static final int POSICAO = 4;
  private static final int TETO_DE_TERMOS = 512;

  private final TratamentoProdutoPorta tratamento;
  private final LongSupplier relogio;
  private final Map<String, Guardado> guardados = new LinkedHashMap<>(16, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Guardado> eldest) {
      return size() > TETO_DE_TERMOS;
    }
  };

  /** Constrói a resolução sobre a porta. */
  @Autowired
  public ResolucaoProdutoPelaTabela(TratamentoProdutoPorta tratamento) {
    this(tratamento, System::currentTimeMillis);
  }

  ResolucaoProdutoPelaTabela(TratamentoProdutoPorta tratamento, LongSupplier relogio) {
    this.tratamento = tratamento;
    this.relogio = relogio;
  }

  @Override
  public Set<String> familias(String termo, LocalDate data) {
    if (termo == null || termo.isBlank()) {
      return Set.of();
    }
    String digitos = termo.replaceAll("[.\\s-]", "");
    if (digitos.matches("\\d{" + POSICAO + ",}")) {
      return Set.of("ncm:" + digitos.substring(0, POSICAO));
    }
    String chave = termo.strip() + "|" + data;
    long agora = relogio.getAsLong();
    synchronized (guardados) {
      Guardado guardado = guardados.get(chave);
      if (guardado != null && agora - guardado.em() < VALIDADE_MS) {
        return guardado.familias();
      }
    }
    Set<String> familias = consultar(termo.strip(), data);
    synchronized (guardados) {
      guardados.put(chave, new Guardado(familias, agora));
    }
    return familias;
  }

  private Set<String> consultar(String termo, LocalDate data) {
    TratamentoConsultado consultado;
    try {
      consultado = tratamento.consultar(termo, data);
    } catch (RuntimeException e) {
      log.warn("Ceres: a resolucao de produto da confirmacao falhou ({})",
          e.getClass().getSimpleName());
      return Set.of();
    }
    // Só o NCM entra: o item de anexo agrupa NCMs de posições diferentes e não se compara com a
    // posição de outro termo, então ele não prova nem refuta nada.
    Set<String> familias = new LinkedHashSet<>();
    for (Produto p : consultado.produtos()) {
      if (p.ncm() != null && p.ncm().length() >= POSICAO && p.ncmConhecido()) {
        familias.add("ncm:" + p.ncm().substring(0, POSICAO));
      }
    }
    return Set.copyOf(familias);
  }

  private record Guardado(Set<String> familias, long em) {}
}
