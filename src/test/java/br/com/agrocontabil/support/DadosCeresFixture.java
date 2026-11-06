package br.com.agrocontabil.support;

import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Notas e guias para os testes da Ceres (issues #142/#143): entradas importadas com itens,
 * saídas próprias e guias fiscais, sempre do produtor informado.
 */
public final class DadosCeresFixture {

  private static final AtomicLong SEQUENCIA = new AtomicLong(System.nanoTime() % 1_000_000_000L);

  private DadosCeresFixture() {}

  /** Meio-dia em Brasília do dia informado. */
  public static OffsetDateTime em(int ano, int mes, int dia) {
    return OffsetDateTime.of(ano, mes, dia, 12, 0, 0, 0, ZoneOffset.ofHours(-3));
  }

  /**
   * Nota de entrada (compra) com um item.
   *
   * @param produtor   destinatário (dono da nota)
   * @param status     situação
   * @param emissao    emissão
   * @param descricao  descrição do item
   * @param ncm        NCM do item
   * @param unidade    unidade do item
   * @param quantidade quantidade
   * @param valor      valor do item (= valor da nota)
   */
  public static NotaFiscal entrada(Produtor produtor, StatusNotaFiscal status,
      OffsetDateTime emissao, String descricao, String ncm, String unidade, String quantidade,
      String valor) {
    NotaFiscal nota = base(produtor, emissao, valor);
    nota.setOrigem(OrigemNotaFiscal.IMPORTADA);
    nota.setStatus(status);
    nota.setEmitenteNome("Revenda de Insumos Ltda");
    nota.setEmitenteCpfCnpj("11222333000181");
    nota.setChaveAcesso(String.format("%044d", SEQUENCIA.incrementAndGet()));
    nota.setValorTotalCreditoIbs(new BigDecimal("1.00"));
    nota.setValorTotalCreditoCbs(new BigDecimal("9.00"));
    nota.addItem(item(descricao, ncm, unidade, quantidade, valor));
    return nota;
  }

  /** Nota de saída (venda) própria autorizada, com um item. */
  public static NotaFiscal saida(Produtor produtor, OffsetDateTime emissao, String descricao,
      String ncm, String valor) {
    NotaFiscal nota = base(produtor, emissao, valor);
    nota.setOrigem(OrigemNotaFiscal.PROPRIA);
    nota.setStatus(StatusNotaFiscal.AUTORIZADA);
    nota.setValorTotalIbs(new BigDecimal("2.00"));
    nota.setValorTotalCbs(new BigDecimal("18.00"));
    NotaFiscalItem item = item(descricao, ncm, "KG", "100", valor);
    // CST do ICMS informado: o sinal ICMS_A_DEFINIR tem teste proprio e nao entra nos da Ceres.
    item.setCstIcms("41");
    nota.addItem(item);
    return nota;
  }

  /** Guia fiscal do produtor. */
  public static GuiaFiscal guia(Produtor produtor, LocalDate vencimento, String valor,
      StatusGuiaFiscal status) {
    return GuiaFiscal.builder()
        .produtor(produtor)
        .tipoGuia(TipoGuiaFiscal.FUNRURAL)
        .dataVencimento(vencimento)
        .valor(new BigDecimal(valor))
        .status(status)
        .build();
  }

  private static NotaFiscal base(Produtor produtor, OffsetDateTime emissao, String valor) {
    return NotaFiscal.builder()
        .numero(SEQUENCIA.incrementAndGet() % 999_999_999L)
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Compra e venda de insumos")
        .dataEmissao(emissao)
        .produtor(produtor)
        .destinatarioNome("Destinatario Teste")
        .destinatarioCpfCnpj("12345678901")
        .destinatarioEndereco(EnderecoDestinatario.builder()
            .logradouro("Rua A").numero("1").municipio("Cuiaba")
            .codigoMunicipioIbge("5103403").uf("MT").cep("78000000").build())
        .valorTotalProdutos(new BigDecimal(valor))
        .valorTotalNota(new BigDecimal(valor))
        .build();
  }

  private static NotaFiscalItem item(String descricao, String ncm, String unidade,
      String quantidade, String valor) {
    BigDecimal qtd = new BigDecimal(quantidade);
    BigDecimal total = new BigDecimal(valor);
    return NotaFiscalItem.builder()
        .numeroItem(1)
        .descricao(descricao)
        .ncm(ncm)
        .cfop("1101")
        .unidade(unidade)
        .quantidade(qtd)
        .valorUnitario(total.divide(qtd, 4, java.math.RoundingMode.HALF_UP))
        .valorTotal(total)
        .build();
  }
}
