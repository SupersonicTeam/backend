package br.com.agrocontabil.service;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço responsável pela identificação e cálculo do crédito tributário de IBS/CBS
 * aproveitável nas notas de <strong>entrada</strong> (Sprint 4 — issue Agrocontabil#30, RN003).
 *
 * <p><strong>Regra de cálculo por item elegível:</strong>
 * <ol>
 *   <li>{@code baseCalculo = valorTotalItem × (1 − reducao / 100)}, onde {@code reducao} é
 *       resolvida pelo {@link ReducaoNcmResolver} (precedência item → NCM → zero) — o
 *       <strong>mesmo</strong> resolver usado pelo débito em {@link TributacaoService}, garantindo
 *       simetria débito × crédito de fato (issue Agrocontabil#52)</li>
 *   <li>{@code aliquotaIbs}/{@code aliquotaCbs} resolvidas pelo {@link AliquotaIbsCbsResolver}
 *       — o <strong>mesmo</strong> componente usado pelo débito (issue Agrocontabil#86),
 *       garantindo que crédito e débito apliquem a alíquota de IBS da mesma UF (RN002)</li>
 *   <li>{@code creditoIbs = baseCalculo × aliquotaIbs × percentualAproveitamento / 100} —
 *       arredondamento HALF_UP, 2 casas</li>
 *   <li>{@code creditoCbs = baseCalculo × aliquotaCbs × percentualAproveitamento / 100} —
 *       arredondamento HALF_UP, 2 casas</li>
 * </ol>
 *
 * <p><strong>Elegibilidade:</strong> somente notas {@link OrigemNotaFiscal#IMPORTADA} (a nota
 * importada é, por decisão de produto, uma nota de entrada/compra em que o produtor autenticado
 * é o destinatário). Por item, o 1.º dígito do CFOP decide:
 * <ul>
 *   <li><strong>1/2/3</strong> — operação de entrada na perspectiva do destinatário: elegível;</li>
 *   <li><strong>5/6</strong> — o XML de um fornecedor traz o CFOP na perspectiva do
 *       <em>emitente</em> (ex.: 5102/6101 = venda dele), que espelha uma entrada
 *       interna/interestadual para o produtor: elegível;</li>
 *   <li><strong>7</strong> — exportação: o destinatário está no exterior e a operação é
 *       desonerada (imunidade de exportação — LC 214/2025, art. 8.º): sem crédito;</li>
 *   <li>nulo ou fora do padrão CFOP: sem crédito (postura conservadora).</li>
 * </ul>
 *
 * <p><strong>Contexto legal e premissas (LC 214/2025):</strong>
 * <ul>
 *   <li><strong>Não-cumulatividade ampla</strong> (EC 132/2023, art. 156-A, § 1.º, VIII;
 *       LC 214/2025, art. 47): o adquirente se credita do IBS/CBS incidente na aquisição.</li>
 *   <li><strong>Premissa de cálculo:</strong> o layout 4.00 dos XMLs importados ainda não traz
 *       o grupo IBS/CBS destacado (NT em elaboração pela SEFAZ); o crédito é portanto
 *       <em>estimado</em> pelas mesmas alíquotas de referência do débito (por UF, via
 *       {@link AliquotaIbsCbsResolver}) aplicadas sobre a base do item. Quando o parser passar a
 *       ler o IBS/CBS destacado no XML, o valor destacado deverá prevalecer (débito técnico
 *       registrado para a sprint de SEFAZ real).</li>
 *   <li><strong>Incerteza regulatória documentada:</strong> aquisições de produtor rural não
 *       contribuinte geram <em>crédito presumido</em> (LC 214/2025, arts. 164 e seguintes) com
 *       percentual dependente de ato do Comitê Gestor/RFB ainda não editado — por isso o
 *       percentual de aproveitamento é parametrizado em
 *       {@code agrocontabil.tributos.credito.percentual-aproveitamento}, nunca cravado em
 *       código.</li>
 * </ul>
 *
 * <p>Recebe a entidade {@link NotaFiscal} (já com itens carregados), calcula e escreve os campos de
 * crédito diretamente na entidade. Para resolver a redução de base por NCM (issue Agrocontabil#52)
 * consulta a tabela de referência {@code ncm_agropecuario} através do {@link ReducaoNcmResolver},
 * portanto opera em transação (read-only) — não é mais um serviço puro.
 */
@Service
public class CreditoTributarioService {

  private static final BigDecimal CEM = new BigDecimal("100");

  private final TributosProperties tributosProperties;
  private final ReducaoNcmResolver reducaoNcmResolver;
  private final AliquotaIbsCbsResolver aliquotaResolver;

  /**
   * Constrói o serviço injetando o percentual de aproveitamento parametrizado e os resolvers
   * compartilhados com o débito.
   *
   * @param tributosProperties propriedades de tributos do {@code application.yml} — usadas aqui
   *     <strong>apenas</strong> para {@code credito.percentualAproveitamento}; alíquotas nunca são
   *     lidas diretamente (issue #86)
   * @param reducaoNcmResolver resolver compartilhado da redução de base por NCM (issue #52)
   * @param aliquotaResolver resolver compartilhado das alíquotas de IBS por UF e CBS (issue #86)
   */
  public CreditoTributarioService(
      TributosProperties tributosProperties,
      ReducaoNcmResolver reducaoNcmResolver,
      AliquotaIbsCbsResolver aliquotaResolver) {
    this.tributosProperties = tributosProperties;
    this.reducaoNcmResolver = reducaoNcmResolver;
    this.aliquotaResolver = aliquotaResolver;
  }

  /**
   * Calcula o crédito de IBS/CBS aproveitável por item e agrega os totais na nota.
   *
   * <p>Somente notas {@link OrigemNotaFiscal#IMPORTADA} são processadas — nas demais a nota é
   * devolvida intacta. Itens não elegíveis têm o crédito zerado (nulo); os totais da nota
   * importada são sempre preenchidos (inclusive {@code 0.00} sem itens elegíveis), permitindo
   * agregação por período sem tratar nulos.
   *
   * <p>O método é <strong>idempotente</strong>: recalcular sobre uma nota já processada
   * recompõe crédito e totais do zero — um item que deixou de ser elegível tem o crédito
   * limpo, evitando divergência entre créditos por item e totais da nota.
   *
   * @param nota nota fiscal com itens já carregados; modificada in-place
   * @return a mesma {@code nota} com os campos de crédito preenchidos quando aplicável
   */
  @Transactional(readOnly = true)
  public NotaFiscal calcular(NotaFiscal nota) {
    if (nota.getOrigem() != OrigemNotaFiscal.IMPORTADA) {
      return nota;
    }

    // Mesma resolucao de aliquota do debito (RN002 / issue #86): UF do destinatario = produtor.
    AliquotasVigentes vigentes = aliquotaResolver.resolver(nota);
    BigDecimal aliquotaIbs = vigentes.ibsTotal();
    BigDecimal aliquotaCbs = vigentes.cbs();
    BigDecimal fatorAproveitamento = tributosProperties.getCredito()
        .getPercentualAproveitamento()
        .divide(CEM, 10, RoundingMode.HALF_UP);

    BigDecimal totalCreditoIbs = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    BigDecimal totalCreditoCbs = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    for (NotaFiscalItem item : nota.getItens()) {
      if (!itemElegivel(item)) {
        // Idempotência: zera credito de item nao elegivel para nao manter valor
        // antigo em recalculo (ex.: reprocessamento/edicao), honrando o contrato.
        item.setValorCreditoIbs(null);
        item.setValorCreditoCbs(null);
        continue;
      }
      ReducaoNcmResolver.ReducaoAplicada aplicada = reducaoNcmResolver.tratamento(item);
      // O tratamento aplicado tambem na nota importada: a tela le estes campos (#272).
      item.setPercentualReducaoAplicado(aplicada.percentualReducao());
      item.setCClassTribAplicado(aplicada.cClassTrib());
      // Base cheia x aliquota efetiva, com um arredondamento so: mesma conta do debito (#278).
      BigDecimal base = item.getValorTotal().multiply(fatorReducao(aplicada.percentualReducao()));
      BigDecimal creditoIbs = base.multiply(aliquotaIbs)
          .multiply(fatorAproveitamento)
          .setScale(2, RoundingMode.HALF_UP);
      BigDecimal creditoCbs = base.multiply(aliquotaCbs)
          .multiply(fatorAproveitamento)
          .setScale(2, RoundingMode.HALF_UP);

      item.setValorCreditoIbs(creditoIbs);
      item.setValorCreditoCbs(creditoCbs);

      totalCreditoIbs = totalCreditoIbs.add(creditoIbs);
      totalCreditoCbs = totalCreditoCbs.add(creditoCbs);
    }

    nota.setValorTotalCreditoIbs(totalCreditoIbs);
    nota.setValorTotalCreditoCbs(totalCreditoCbs);

    return nota;
  }

  /**
   * Decide a elegibilidade do item pelo 1.º dígito do CFOP (ver Javadoc da classe):
   * 1/2/3 (entrada) e 5/6 (saída do emitente terceiro = entrada para o produtor) geram
   * crédito; 7 (exportação), nulo ou fora do padrão, não.
   */
  private boolean itemElegivel(NotaFiscalItem item) {
    String cfop = item.getCfop();
    if (cfop == null || cfop.isBlank()) {
      return false;
    }
    char digito = cfop.trim().charAt(0);
    return digito == '1' || digito == '2' || digito == '3' || digito == '5' || digito == '6';
  }

  /**
   * Fator da alíquota efetiva do crédito, {@code 1 − reducao / 100}: mesma regra do débito em
   * {@link TributacaoService}, com a {@code reducao} resolvida pelo {@link ReducaoNcmResolver}
   * (issues #52 e #272), garantindo simetria débito × crédito. A LC 214/2025 reduz a
   * <em>alíquota</em>, não a base (grupo {@code gRed} do XML, issue #278).
   */
  private static BigDecimal fatorReducao(BigDecimal reducao) {
    return BigDecimal.ONE.subtract(reducao.divide(CEM, 10, RoundingMode.HALF_UP));
  }
}
