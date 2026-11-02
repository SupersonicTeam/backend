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
   * Vendas de um produto (issue #248): saídas próprias autorizadas, período inclusivo, por NCM ou
   * pela descrição (curingas digitados são texto). Mesmo formato das compras.
   */
  ComprasInsumo vendasDeProduto(
      UUID produtorId, String termo, boolean porNcm, LocalDate inicio, LocalDate fim);

  /**
   * NCMs (8 dígitos) das compras de um insumo, pelo mesmo critério de {@link #comprasPorNcm} ou
   * {@link #comprasPorDescricao}, maior valor primeiro, no máximo {@code limite} (issue #170). Só
   * códigos de classificação: nenhum dado pessoal nem valor.
   */
  List<String> ncmsComprados(
      UUID produtorId, String descricaoOuNcm, LocalDate inicio, LocalDate fim, int limite);

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

  /**
   * Notas do produtor com o número pedido (issue #256), no máximo 3 (séries diferentes e notas de
   * fornecedor repetem número). Número de outro produtor = lista vazia, como número inexistente.
   */
  Pagina<NotaDetalhe> notasPorNumero(UUID produtorId, long numero);

  /** Guias não pagas com vencimento em {@code [de, ate]}, da mais próxima para a mais distante. */
  Pagina<Vencimento> vencimentosNaoPagos(UUID produtorId, LocalDate de, LocalDate ate, int limite);

  /**
   * Quantidade e soma do valor de todas as guias não pagas da janela {@code [de, ate]}, sem o
   * limite da lista (issue #169).
   */
  Totais totalVencimentosNaoPagos(UUID produtorId, LocalDate de, LocalDate ate);

  /**
   * Alertas tributários ativos do produtor (issue #140, RN010), do mais urgente ao mais distante,
   * no máximo {@link #LIMITE_MAXIMO}. O motivo de cada um já foi conferido na consulta.
   */
  Pagina<Alerta> alertas(UUID produtorId);

  /**
   * Retenções sobre as vendas autorizadas do período (RN009, issue #145): Funrural retido pelo
   * comprador e IBS/CBS separado no pagamento (split payment). Valores calculados pelo backend.
   */
  Retencoes retencoes(UUID produtorId, LocalDate inicio, LocalDate fim);

  /**
   * Funrural de um conjunto de vendas, por parcela (1,2% + 0,1% + 0,2% SENAR).
   *
   * @param notas       vendas no conjunto
   * @param base        receita bruta (valor dos produtos)
   * @param previdencia parcela de 1,2%
   * @param rat         parcela de 0,1%
   * @param senar       parcela de 0,2%
   * @param total       soma das parcelas
   */
  record Funrural(long notas, BigDecimal base, BigDecimal previdencia, BigDecimal rat,
      BigDecimal senar, BigDecimal total) {}

  /**
   * Retenções do período, sem dado do comprador (só a categoria entra no cálculo).
   *
   * @param produtorPessoaFisica {@code false}: produtor PJ ou sem tipo cadastrado, fora da
   *                             retenção do Funrural
   * @param retidoPeloComprador  vendas a empresa ou cooperativa
   * @param semRetencao          vendas a pessoa física ou no varejo (o produtor recolhe)
   * @param exportacao           vendas ao exterior: imunes, sem Funrural
   * @param ibsSplitPayment      IBS destacado nas vendas
   * @param cbsSplitPayment      CBS destacada nas vendas
   * @param notasVendidas        vendas autorizadas do período
   */
  record Retencoes(boolean produtorPessoaFisica, Funrural retidoPeloComprador,
      Funrural semRetencao, Funrural exportacao, BigDecimal ibsSplitPayment, BigDecimal cbsSplitPayment,
      long notasVendidas) {}

  /**
   * Um alerta, com a descrição montada pelo backend (sem observação livre nem dado pessoal).
   *
   * @param tipo          {@code VENCIMENTO_GUIA}, {@code FECHAMENTO_APURACAO} ou
   *                      {@code PRAZO_CANCELAMENTO_NFE}
   * @param descricao     texto curto
   * @param dataLimite    último dia para agir
   * @param diasRestantes dias até a data-limite (0 = hoje)
   */
  record Alerta(String tipo, String descricao, LocalDate dataLimite, long diasRestantes) {}

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

  /**
   * Uma nota consultada pelo número, sem dado pessoal (issue #256).
   *
   * @param motivoDevolucao motivo da última devolução, só com a nota ainda devolvida
   * @param inconsistencias as do último lote, só com a nota em {@code COM_INCONSISTENCIAS}
   * @param sinais          sinais de nota a conferir (#276), só nas notas próprias
   */
  record NotaDetalhe(long numero, int serie, OffsetDateTime dataEmissao, OrigemNotaFiscal origem,
      StatusNotaFiscal status, BigDecimal valorTotal, BigDecimal valorIbs, BigDecimal valorCbs,
      String motivoDevolucao, List<Inconsistencia> inconsistencias, List<Sinal> sinais) {

    /** Cópias defensivas. */
    public NotaDetalhe {
      inconsistencias = List.copyOf(inconsistencias);
      sinais = List.copyOf(sinais);
    }
  }

  /**
   * Sinal de nota a conferir (#276): suspeita, nunca certeza. A descrição do item é texto livre do
   * produtor: vai só ao texto fixo do backend, nunca ao modelo.
   *
   * @param codigo            código do sinal (ex.: {@code PRODUTO_PARECE_SERVICO})
   * @param numeroItem        item da nota; nulo num sinal da nota inteira
   * @param descricaoItem     descrição do item
   * @param cfop              CFOP do item
   * @param ncm               NCM do item
   * @param prazoCancelamento último dia para cancelar, só com a nota autorizada no prazo
   */
  record Sinal(String codigo, Integer numeroItem, String descricaoItem, String cfop, String ncm,
      LocalDate prazoCancelamento) {}

  /** Inconsistência de validação: código, campo, mensagem e sugestão do sistema. */
  record Inconsistencia(String codigo, String campo, String mensagem, String sugestao) {}

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
