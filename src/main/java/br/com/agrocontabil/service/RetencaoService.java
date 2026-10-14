package br.com.agrocontabil.service;

import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VendaPorAdquirenteAgregado;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retenções sobre as vendas do produtor (RN009, issue #145). Definição do Murillo de 2026-10-14:
 * a RN009 cobre <strong>duas</strong> retenções, e a Ceres cita as duas.
 *
 * <ol>
 *   <li><strong>Funrural retido pelo adquirente.</strong> O produtor rural pessoa física contribui
 *       sobre a receita bruta da comercialização: 1,2% (Lei 8.212/1991, art. 25, I) + 0,1% para
 *       acidente do trabalho (art. 25, II) + 0,2% para o SENAR (Lei 9.528/1997, art. 6º) = 1,5%.
 *       Quando ele vende a <em>empresa ou cooperativa</em>, o comprador fica sub-rogado e recolhe
 *       (Lei 8.212, art. 30, III e IV): o valor é <strong>retido</strong> no pagamento. Vendendo a
 *       pessoa física ou no varejo, não há retenção: o próprio produtor recolhe (art. 30, X).
 *       <strong>Venda ao exterior não paga Funrural:</strong> receita de exportação é imune às
 *       contribuições sociais (Constituição, art. 149, § 2º, I; STF, Tema 674). Produtor pessoa
 *       jurídica não tem essa retenção.</li>
 *   <li><strong>Split payment.</strong> O IBS e a CBS destacados na nota são separados e recolhidos
 *       na liquidação financeira do pagamento (LC 214/2025, arts. 31 e 32).</li>
 * </ol>
 *
 * <p>Fora do cálculo, de propósito: a opção pela folha de salários (art. 25, § 13), que o sistema
 * não guarda e que elimina a retenção; e a produção vendida para plantio ou reprodução (art. 25,
 * § 12), que a nota não identifica. A Ceres avisa das duas na resposta.
 */
@Service
public class RetencaoService {

  /** Lei 8.212/1991, art. 25, I (redação da Lei 13.606/2018). */
  public static final BigDecimal ALIQUOTA_PREVIDENCIA = new BigDecimal("0.012");
  /** Lei 8.212/1991, art. 25, II (redação da Lei 9.528/1997). */
  public static final BigDecimal ALIQUOTA_RAT = new BigDecimal("0.001");
  /** Lei 9.528/1997, art. 6º (redação da Lei 10.256/2001). */
  public static final BigDecimal ALIQUOTA_SENAR = new BigDecimal("0.002");

  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  private final NotaFiscalRepository notaFiscalRepository;
  private final ProdutorRepository produtorRepository;

  /** Service sobre as notas e o cadastro do produtor. */
  public RetencaoService(
      NotaFiscalRepository notaFiscalRepository, ProdutorRepository produtorRepository) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.produtorRepository = produtorRepository;
  }

  /** Quem é o comprador da venda: é ele que decide se o Funrural é retido. */
  public enum Adquirente { PESSOA_JURIDICA, PESSOA_FISICA, EXTERIOR }

  /** Como o Funrural de uma venda é pago. */
  public enum SituacaoFunrural {
    /** Venda de produtor PF a empresa ou cooperativa: o comprador retém e recolhe. */
    RETIDO_PELO_ADQUIRENTE,
    /** Venda de produtor PF a pessoa física ou no varejo: o produtor recolhe. */
    RECOLHIDO_PELO_PRODUTOR,
    /** Venda ao exterior: receita de exportação, imune (Constituição, art. 149, § 2º, I). */
    EXPORTACAO_IMUNE,
    /** Produtor pessoa jurídica (ou sem tipo cadastrado): fora desta regra. */
    NAO_SE_APLICA
  }

  /**
   * Funrural de uma venda (ou de um conjunto de vendas), por parcela.
   *
   * @param situacao    quem paga
   * @param base        receita bruta (valor dos produtos)
   * @param previdencia 1,2%
   * @param rat         0,1%
   * @param senar       0,2%
   */
  public record Funrural(SituacaoFunrural situacao, BigDecimal base, BigDecimal previdencia,
      BigDecimal rat, BigDecimal senar) {

    /** Soma das três parcelas (1,5% da base). */
    public BigDecimal total() {
      return previdencia.add(rat).add(senar);
    }
  }

  /**
   * Retenções de uma nota.
   *
   * @param adquirente   tipo do comprador
   * @param funrural     Funrural da venda
   * @param splitPayment IBS + CBS destacados, separados no pagamento
   */
  public record RetencaoInfo(Adquirente adquirente, Funrural funrural, BigDecimal splitPayment) {}

  /**
   * Retenções das vendas de um período.
   *
   * @param tipoProdutor          tipo do produtor; {@code null} se não cadastrado
   * @param retidoPeloAdquirente  vendas a empresa/cooperativa (Funrural retido)
   * @param notasRetidas          quantidade dessas vendas
   * @param recolhidoPeloProdutor vendas a pessoa física ou no varejo (sem retenção)
   * @param notasSemRetencao      quantidade dessas vendas
   * @param exportacao            vendas ao exterior (imunes, sem Funrural)
   * @param notasExportacao       quantidade dessas vendas
   * @param ibsSplitPayment       IBS destacado em todas as vendas do período
   * @param cbsSplitPayment       CBS destacada em todas as vendas do período
   * @param notasVendidas         total de vendas autorizadas do período
   */
  public record ResumoRetencoes(TipoProdutor tipoProdutor, Funrural retidoPeloAdquirente,
      long notasRetidas, Funrural recolhidoPeloProdutor, long notasSemRetencao,
      Funrural exportacao, long notasExportacao, BigDecimal ibsSplitPayment, BigDecimal cbsSplitPayment, long notasVendidas) {}

  /**
   * Retenções de uma nota. Vazio se a nota não é venda própria autorizada: compra, rascunho,
   * rejeitada e cancelada não têm pagamento a reter.
   *
   * @param nota nota fiscal (com o produtor carregado)
   * @return as retenções da venda
   */
  public Optional<RetencaoInfo> detectarRetencao(NotaFiscal nota) {
    if (nota.getOrigem() != OrigemNotaFiscal.PROPRIA
        || nota.getStatus() != StatusNotaFiscal.AUTORIZADA) {
      return Optional.empty();
    }
    Adquirente adquirente = adquirente(
        nota.getDestinatarioEndereco() == null ? null : nota.getDestinatarioEndereco().getUf(),
        nota.getDestinatarioCpfCnpj());
    BigDecimal split = valor(nota.getValorTotalIbs()).add(valor(nota.getValorTotalCbs()));
    return Optional.of(new RetencaoInfo(adquirente,
        funrural(nota.getProdutor().getTipoProdutor(), adquirente,
            valor(nota.getValorTotalProdutos())),
        split));
  }

  /**
   * Retenções das vendas autorizadas do produtor no período.
   *
   * <p><strong>Contrato de segurança:</strong> {@code produtorId} vem de identidade já resolvida no
   * servidor (o contexto da Ceres), nunca de entrada do cliente ou do modelo.
   *
   * @param produtorId produtor
   * @param inicio     primeiro dia (inclusive), em Brasília
   * @param fim        último dia (inclusive), em Brasília
   * @return Funrural retido e não retido, e o IBS/CBS separado no pagamento
   * @throws ResourceNotFoundException se o produtor não existe
   */
  @Transactional(readOnly = true)
  public ResumoRetencoes resumir(UUID produtorId, LocalDate inicio, LocalDate fim) {
    Produtor produtor = produtorRepository.findById(produtorId)
        .orElseThrow(() -> new ResourceNotFoundException("Produtor não encontrado"));
    List<VendaPorAdquirenteAgregado> vendas = notaFiscalRepository.agregarVendasPorAdquirente(
        produtorId, inicioDoDia(inicio), inicioDoDia(fim.plusDays(1)));

    Map<Adquirente, VendaPorAdquirenteAgregado> porAdquirente = new EnumMap<>(Adquirente.class);
    vendas.forEach(v -> porAdquirente.put(Adquirente.valueOf(v.adquirente()), v));

    TipoProdutor tipo = produtor.getTipoProdutor();
    VendaPorAdquirenteAgregado aPj = porAdquirente.get(Adquirente.PESSOA_JURIDICA);
    VendaPorAdquirenteAgregado aPf = porAdquirente.get(Adquirente.PESSOA_FISICA);
    VendaPorAdquirenteAgregado exterior = porAdquirente.get(Adquirente.EXTERIOR);
    return new ResumoRetencoes(
        tipo,
        funrural(tipo, Adquirente.PESSOA_JURIDICA, base(aPj)),
        quantidade(aPj),
        funrural(tipo, Adquirente.PESSOA_FISICA, base(aPf)),
        quantidade(aPf),
        funrural(tipo, Adquirente.EXTERIOR, base(exterior)),
        quantidade(exterior),
        vendas.stream().map(v -> valor(v.valorIbs())).reduce(BigDecimal.ZERO, BigDecimal::add),
        vendas.stream().map(v -> valor(v.valorCbs())).reduce(BigDecimal.ZERO, BigDecimal::add),
        vendas.stream().mapToLong(VendaPorAdquirenteAgregado::quantidade).sum());
  }

  /** Tipo do comprador: UF {@code EX} é exterior; documento de 14 dígitos é CNPJ. */
  static Adquirente adquirente(String uf, String documento) {
    if ("EX".equalsIgnoreCase(uf)) {
      return Adquirente.EXTERIOR;
    }
    return documento != null && documento.length() == 14
        ? Adquirente.PESSOA_JURIDICA
        : Adquirente.PESSOA_FISICA;
  }

  /**
   * Funrural sobre a base: só produtor PF; retido só na venda a empresa ou cooperativa; nada na
   * exportação.
   */
  static Funrural funrural(TipoProdutor tipo, Adquirente adquirente, BigDecimal base) {
    if (tipo != TipoProdutor.PESSOA_FISICA) {
      return semFunrural(SituacaoFunrural.NAO_SE_APLICA, base);
    }
    if (adquirente == Adquirente.EXTERIOR) {
      return semFunrural(SituacaoFunrural.EXPORTACAO_IMUNE, base);
    }
    SituacaoFunrural situacao = adquirente == Adquirente.PESSOA_JURIDICA
        ? SituacaoFunrural.RETIDO_PELO_ADQUIRENTE
        : SituacaoFunrural.RECOLHIDO_PELO_PRODUTOR;
    return new Funrural(situacao, base, parcela(base, ALIQUOTA_PREVIDENCIA),
        parcela(base, ALIQUOTA_RAT), parcela(base, ALIQUOTA_SENAR));
  }

  private static Funrural semFunrural(SituacaoFunrural situacao, BigDecimal base) {
    BigDecimal zero = BigDecimal.ZERO.setScale(2);
    return new Funrural(situacao, base, zero, zero, zero);
  }

  private static BigDecimal base(VendaPorAdquirenteAgregado vendas) {
    return vendas == null ? BigDecimal.ZERO : valor(vendas.valorProdutos());
  }

  private static long quantidade(VendaPorAdquirenteAgregado vendas) {
    return vendas == null ? 0 : vendas.quantidade();
  }

  private static BigDecimal parcela(BigDecimal base, BigDecimal aliquota) {
    return base.multiply(aliquota).setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal valor(BigDecimal valor) {
    return valor == null ? BigDecimal.ZERO : valor;
  }

  private static OffsetDateTime inicioDoDia(LocalDate dia) {
    return dia.atStartOfDay(ZONE).toOffsetDateTime();
  }
}
