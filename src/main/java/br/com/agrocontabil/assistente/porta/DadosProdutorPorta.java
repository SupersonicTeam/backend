package br.com.agrocontabil.assistente.porta;

import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Porta por onde as funções da Ceres leem os dados do produtor (issue #141, fronteira do módulo).
 *
 * <p>O módulo {@code assistente} não conhece services, repositories nem entidades: só esta
 * interface e os records dela. A implementação ({@code assistente.adaptador}) chama os services,
 * onde vivem o anti-IDOR e a regra fiscal. Extrair a Ceres como serviço seria trocar o adaptador
 * por um cliente HTTP — nada aqui muda.
 *
 * <p><strong>Contrato de segurança:</strong> todo {@code produtorId} vem do
 * {@code ContextoAssistente} (identidade resolvida no servidor), nunca do modelo nem do cliente.
 */
public interface DadosProdutorPorta {

  /** Maior lista que uma consulta devolve ao módulo (minimização, issue #144). */
  int LIMITE_MAXIMO = 10;

  /** Compras de um insumo por NCM (2 a 8 dígitos), entradas autorizadas, período inclusivo. */
  ComprasInsumo comprasPorNcm(UUID produtorId, String prefixoNcm, LocalDate inicio, LocalDate fim);

  /** Compras de um insumo pela descrição do item (curingas digitados são texto). */
  ComprasInsumo comprasPorDescricao(UUID produtorId, String termo, LocalDate inicio, LocalDate fim);

  /**
   * Apuração mensal de IBS/CBS.
   *
   * @param periodo {@code AAAA-MM}
   */
  Apuracao apuracao(UUID produtorId, String periodo);

  /** Créditos de IBS/CBS das entradas autorizadas no período inclusivo. */
  Creditos creditos(UUID produtorId, LocalDate inicio, LocalDate fim);

  /** Notas mais recentes no período, no máximo {@link #LIMITE_MAXIMO}. */
  Pagina<Nota> notas(UUID produtorId, Set<OrigemNotaFiscal> origens, Set<StatusNotaFiscal> status,
      LocalDate inicio, LocalDate fim, int limite);

  /**
   * Quantidade e soma do valor de todas as notas do mesmo filtro de {@link #notas}, sem o limite
   * da lista (issue #169: a soma é do backend, nunca do modelo).
   */
  Totais totalNotas(UUID produtorId, Set<OrigemNotaFiscal> origens, Set<StatusNotaFiscal> status,
      LocalDate inicio, LocalDate fim);

  /** Guias não pagas com vencimento em {@code [de, ate]}, da mais próxima para a mais distante. */
  Pagina<Vencimento> vencimentosNaoPagos(UUID produtorId, LocalDate de, LocalDate ate, int limite);

  /**
   * Quantidade e soma do valor de todas as guias não pagas da janela {@code [de, ate]}, sem o
   * limite da lista (issue #169).
   */
  Totais totalVencimentosNaoPagos(UUID produtorId, LocalDate de, LocalDate ate);

  /**
   * Quantidade e soma de um mesmo conjunto, calculadas juntas (issue #169).
   *
   * @param quantidade itens somados
   * @param valor      soma (2 casas; zero sem itens)
   */
  record Totais(long quantidade, BigDecimal valor) {}

  /**
   * Compras de um insumo.
   *
   * @param valorTotal      soma do valor dos itens (2 casas; zero sem compras)
   * @param quantidadeNotas notas de entrada distintas
   * @param porUnidade      quantidade e valor por unidade de medida
   */
  record ComprasInsumo(BigDecimal valorTotal, long quantidadeNotas, List<CompraUnidade> porUnidade) {

    /** Cópia defensiva. */
    public ComprasInsumo {
      porUnidade = List.copyOf(porUnidade);
    }
  }

  /** Compras numa unidade de medida. */
  record CompraUnidade(String unidade, BigDecimal quantidade, BigDecimal valorTotal) {}

  /** Apuração mensal (valores nunca nulos). */
  record Apuracao(String periodo, String status, BigDecimal debitoIbs, BigDecimal debitoCbs,
      BigDecimal creditoIbs, BigDecimal creditoCbs, BigDecimal saldoIbs, BigDecimal saldoCbs,
      long totalNotas) {}

  /** Créditos no período (valores nunca nulos, 2 casas). */
  record Creditos(BigDecimal creditoIbs, BigDecimal creditoCbs, long quantidadeNotas) {}

  /** Uma nota, sem dado pessoal (issue #144). */
  record Nota(long numero, int serie, OffsetDateTime dataEmissao, OrigemNotaFiscal origem,
      StatusNotaFiscal status, BigDecimal valorTotal, BigDecimal valorIbs, BigDecimal valorCbs) {}

  /** Uma guia não paga, sem as observações livres do contador. */
  record Vencimento(TipoGuiaFiscal tipo, LocalDate dataVencimento, BigDecimal valor,
      StatusGuiaFiscal status) {}

  /**
   * Página limitada de resultados.
   *
   * @param itens itens devolvidos
   * @param total quantos existem no filtro (pode ser mais que os itens)
   */
  record Pagina<T>(List<T> itens, long total) {

    /** Cópia defensiva. */
    public Pagina {
      itens = List.copyOf(itens);
    }
  }
}
