package br.com.agrocontabil.service;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/**
 * Serviço responsável pela identificação e cálculo do crédito tributário de IBS/CBS
 * aproveitável nas notas de <strong>entrada</strong> (Sprint 4 — issue Agrocontabil#30, RN003).
 *
 * <p><strong>Regra de cálculo por item elegível:</strong>
 * <ol>
 *   <li>{@code baseCalculo = valorTotalItem × (1 − percentualReducao / 100)} — mesma base do
 *       débito calculado em {@link TributacaoService}, para simetria débito × crédito</li>
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
 *       <em>estimado</em> pelas mesmas alíquotas de referência parametrizadas do ano-teste 2026
 *       ({@link TributosProperties}) aplicadas sobre a base do item. Quando o parser passar a
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
 * <p>Este serviço é <strong>puro</strong>: não acessa banco de dados. Recebe a entidade
 * {@link NotaFiscal} (já com itens carregados), calcula e escreve os campos de crédito
 * diretamente na entidade. Pode ser testado sem contexto Spring.
 */
@Service
public class CreditoTributarioService {

  private static final BigDecimal CEM = new BigDecimal("100");

  private final TributosProperties tributosProperties;

  /**
   * Constrói o serviço injetando alíquotas e percentual de aproveitamento parametrizados.
   *
   * @param tributosProperties propriedades de tributos do {@code application.yml}
   */
  public CreditoTributarioService(TributosProperties tributosProperties) {
    this.tributosProperties = tributosProperties;
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
  public NotaFiscal calcular(NotaFiscal nota) {
    if (nota.getOrigem() != OrigemNotaFiscal.IMPORTADA) {
      return nota;
    }

    BigDecimal aliquotaIbs = tributosProperties.getIbs().getAliquota();
    BigDecimal aliquotaCbs = tributosProperties.getCbs().getAliquota();
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
      BigDecimal baseCalculo = baseCalculo(item);
      BigDecimal creditoIbs = baseCalculo.multiply(aliquotaIbs)
          .multiply(fatorAproveitamento)
          .setScale(2, RoundingMode.HALF_UP);
      BigDecimal creditoCbs = baseCalculo.multiply(aliquotaCbs)
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
   * Base de cálculo do crédito: mesma regra da base do débito em {@link TributacaoService}
   * ({@code valorTotal × (1 − percentualReducao / 100)}), garantindo simetria débito × crédito.
   */
  private BigDecimal baseCalculo(NotaFiscalItem item) {
    BigDecimal reducao = item.getPercentualReducao() != null
        ? item.getPercentualReducao() : BigDecimal.ZERO;
    BigDecimal fatorReducao = BigDecimal.ONE.subtract(reducao.divide(CEM, 10, RoundingMode.HALF_UP));
    return item.getValorTotal().multiply(fatorReducao).setScale(2, RoundingMode.HALF_UP);
  }
}
