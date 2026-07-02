package br.com.agrocontabil.service;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.model.AliquotaIbsUf;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NaturezaOperacaoTributaria;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.AliquotaIbsUfRepository;
import br.com.agrocontabil.repository.NcmReferenciaRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço de cálculo tributário de IBS e CBS por item de nota fiscal, com classificação da
 * operação (entrada/saída) e resolução da redução de base por NCM
 * (Sprint 4 — issue Agrocontabil#30, RF015).
 *
 * <p>Evolução do antigo {@code CalculoIbsCbsService}: além de calcular IBS/CBS por item e agregar
 * os totais na nota, agora <strong>classifica cada item por operação</strong> e resolve a
 * <strong>redução por NCM</strong>.
 *
 * <p><strong>Regra de cálculo (LC 214/2025):</strong>
 * <ol>
 *   <li>{@code reducao} = {@code percentualReducao} informado no item; se nulo, cai para o
 *       percentual de redução <em>padrão</em> do NCM (tabela {@code ncm_agropecuario}); se o NCM
 *       não for de referência, {@code 0} (sem redução — postura conservadora).</li>
 *   <li>{@code baseCalculo = valorTotalItem × (1 − reducao / 100)}</li>
 *   <li>{@code valorIbs = baseCalculo × aliquotaIbs} — arredondamento HALF_UP, 2 casas</li>
 *   <li>{@code valorCbs = baseCalculo × aliquotaCbs} — arredondamento HALF_UP, 2 casas</li>
 * </ol>
 *
 * <p><strong>Alíquota de IBS por UF (RN002, issue Agrocontabil#31):</strong> o IBS é de
 * competência compartilhada estado/município. A alíquota total (e suas parcelas estadual e
 * municipal) é resolvida pela UF aplicável à operação — UF de <em>destino</em> quando
 * interestadual (UF do emitente ≠ UF do destinatário), UF do <em>emitente</em> quando
 * intraestadual — consultando a tabela {@code aliquota_ibs_uf}. As parcelas reais são gravadas
 * no item ({@code aliquotaIbsUf}/{@code aliquotaIbsMun}, {@code valorIbsUf}/{@code valorIbsMun})
 * e consumidas pelo {@code NotaFiscalNfeMapper}, dispensando a antiga divisão 50/50. Quando a UF
 * não é determinável ou não consta na tabela, cai para a alíquota parametrizada em
 * {@link TributosProperties} (dividida igualmente), postura conservadora. A validação de
 * compatibilidade entre o âmbito do CFOP e a relação de UFs já é feita pelo
 * {@code FiscalRulesValidator} (issue #20) no pipeline de emissão — não é duplicada aqui.
 *
 * <p><strong>Classificação por operação (RF015):</strong> pelo 1.º dígito do CFOP do item —
 * {@code 5/6/7} = saída → {@link NaturezaOperacaoTributaria#DEBITO}; {@code 1/2/3} = entrada →
 * {@link NaturezaOperacaoTributaria#CREDITO}; CFOP nulo/fora do padrão → natureza nula
 * (conservador). A natureza é persistida por item e consumida pela apuração mensal (issue #41)
 * para compor débitos e créditos do período. Espelha a lógica de âmbito/tipo já modelada em
 * {@code CfopReferencia} (issue #20), mas sem exigir que o CFOP conste na tabela de referência —
 * a classificação débito/crédito depende apenas do 1.º dígito.
 *
 * <p><strong>Contexto legal:</strong>
 * <ul>
 *   <li><strong>EC 132/2023</strong> (art. 156-A e 195, III): institui IBS e CBS.</li>
 *   <li><strong>LC 214/2025</strong> (arts. 12–18 — base de cálculo; arts. 87–92 — alíquotas):
 *       regulamenta o cálculo, incluindo reduções para regimes diferenciados.</li>
 *   <li>Em 2026 (ano-teste) os valores calculados são informativos; as alíquotas são
 *       reduzidas e parametrizadas em {@code application.yml} via {@link TributosProperties}.
 *       Nunca fixadas em código.</li>
 *   <li><strong>Incerteza documentada:</strong> as tabelas oficiais de redução por NCM da
 *       LC 214/2025 ainda não foram finalizadas pela RFB/CONFAZ. Os percentuais de redução por
 *       NCM são <em>referência de ano-teste</em> (seed do changeset {@code 0012}), configuráveis
 *       sem recompilar, e servem apenas de default quando o item não informa a redução.</li>
 * </ul>
 *
 * <p>Diferentemente do serviço anterior, este consulta a tabela de referência de NCM (leitura),
 * portanto opera em transação (read-only) quando invocado dentro do fluxo de nota fiscal. Os
 * campos calculados são escritos diretamente na entidade {@link NotaFiscal} (mesma referência).
 */
@Service
public class TributacaoService {

  private static final BigDecimal CEM = new BigDecimal("100");
  private static final BigDecimal DOIS = BigDecimal.valueOf(2);

  private final TributosProperties tributosProperties;
  private final NcmReferenciaRepository ncmReferenciaRepository;
  private final AliquotaIbsUfRepository aliquotaIbsUfRepository;

  /**
   * Constrói o serviço injetando as alíquotas parametrizadas e os repositórios de referência.
   *
   * @param tributosProperties propriedades de alíquota do {@code application.yml}
   * @param ncmReferenciaRepository repositório read-only da tabela {@code ncm_agropecuario},
   *     usado para resolver a redução padrão por NCM
   * @param aliquotaIbsUfRepository repositório read-only da tabela {@code aliquota_ibs_uf}, usado
   *     para resolver as parcelas estadual/municipal da alíquota de IBS por UF (issue #31)
   */
  public TributacaoService(
      TributosProperties tributosProperties,
      NcmReferenciaRepository ncmReferenciaRepository,
      AliquotaIbsUfRepository aliquotaIbsUfRepository) {
    this.tributosProperties = tributosProperties;
    this.ncmReferenciaRepository = ncmReferenciaRepository;
    this.aliquotaIbsUfRepository = aliquotaIbsUfRepository;
  }

  /**
   * Calcula IBS/CBS de todos os itens da nota, classifica a natureza da operação e agrega os
   * totais na própria nota.
   *
   * <p>As alíquotas vêm de {@link TributosProperties}; a redução de base combina o percentual do
   * item (precedência) com o default por NCM. Os valores e a natureza são gravados diretamente nas
   * entidades. A nota é retornada modificada (mesma referência).
   *
   * @param nota nota fiscal com itens já carregados; modificada in-place
   * @return a mesma {@code nota} com IBS/CBS, natureza e totais preenchidos
   */
  @Transactional(readOnly = true)
  public NotaFiscal calcular(NotaFiscal nota) {
    // A alíquota de IBS (estadual + municipal) é resolvida pela UF aplicável à operação —
    // a mesma para todos os itens da nota. A CBS (federal) segue a alíquota parametrizada.
    IbsSplit ibsSplit = resolveAliquotaIbs(nota);
    BigDecimal aliquotaCbs = tributosProperties.getCbs().getAliquota();

    BigDecimal totalIbs = BigDecimal.ZERO;
    BigDecimal totalCbs = BigDecimal.ZERO;

    for (NotaFiscalItem item : nota.getItens()) {
      BigDecimal reducao = resolveReducao(item);
      BigDecimal fatorReducao = BigDecimal.ONE.subtract(reducao.divide(CEM, 10, RoundingMode.HALF_UP));
      BigDecimal baseCalculo = item.getValorTotal()
          .multiply(fatorReducao)
          .setScale(2, RoundingMode.HALF_UP);

      BigDecimal valorIbs = baseCalculo.multiply(ibsSplit.total()).setScale(2, RoundingMode.HALF_UP);
      BigDecimal valorCbs = baseCalculo.multiply(aliquotaCbs).setScale(2, RoundingMode.HALF_UP);

      // Parcela estadual pelo valor exato; a municipal fecha o total sem perda no arredondamento.
      BigDecimal valorIbsUf =
          baseCalculo.multiply(ibsSplit.estadual()).setScale(2, RoundingMode.HALF_UP);
      BigDecimal valorIbsMun = valorIbs.subtract(valorIbsUf);

      item.setBaseCalculoIbsCbs(baseCalculo);
      item.setAliquotaIbs(ibsSplit.total());
      item.setAliquotaIbsUf(ibsSplit.estadual());
      item.setAliquotaIbsMun(ibsSplit.municipal());
      item.setValorIbs(valorIbs);
      item.setValorIbsUf(valorIbsUf);
      item.setValorIbsMun(valorIbsMun);
      item.setAliquotaCbs(aliquotaCbs);
      item.setValorCbs(valorCbs);
      item.setNaturezaOperacao(classificarNatureza(item.getCfop()));

      totalIbs = totalIbs.add(valorIbs);
      totalCbs = totalCbs.add(valorCbs);
    }

    nota.setValorTotalIbs(totalIbs);
    nota.setValorTotalCbs(totalCbs);

    return nota;
  }

  /**
   * Resolve as parcelas estadual/municipal da alíquota de IBS aplicável à operação (RN002).
   *
   * <p><strong>Regra:</strong> em operação <em>interestadual</em> (UF do emitente diferente da UF
   * do destinatário) usa-se a alíquota da UF de <strong>destino</strong>; em operação
   * <em>intraestadual</em> (mesma UF) usa-se a alíquota da UF do <strong>emitente</strong>. A UF
   * escolhida é consultada em {@code aliquota_ibs_uf}.
   *
   * <p><strong>Fallback conservador:</strong> se a UF não puder ser determinada (endereço ausente)
   * ou não constar na tabela de referência, cai para a alíquota de IBS parametrizada em
   * {@link TributosProperties}, dividida igualmente entre estado e município — preservando o
   * comportamento anterior à issue #31 sem quebrar o cálculo por indisponibilidade de referência.
   *
   * @param nota nota fiscal (emitente e destinatário)
   * @return parcelas estadual/municipal da alíquota de IBS a aplicar
   */
  private IbsSplit resolveAliquotaIbs(NotaFiscal nota) {
    String ufAplicavel = resolveUfAplicavel(nota);
    if (ufAplicavel != null) {
      Optional<AliquotaIbsUf> referencia = aliquotaIbsUfRepository.findByUf(ufAplicavel);
      if (referencia.isPresent()) {
        AliquotaIbsUf a = referencia.get();
        return new IbsSplit(a.getAliquotaEstadual(), a.getAliquotaMunicipal());
      }
    }
    // Fallback: alíquota parametrizada dividida igualmente entre estado e município.
    BigDecimal aliquotaConfigurada = tributosProperties.getIbs().getAliquota();
    BigDecimal estadual = aliquotaConfigurada.divide(DOIS, 4, RoundingMode.HALF_UP);
    BigDecimal municipal = aliquotaConfigurada.subtract(estadual);
    return new IbsSplit(estadual, municipal);
  }

  /**
   * Determina a UF cuja alíquota de IBS se aplica: destino em operação interestadual, emitente em
   * operação intraestadual (RN002).
   *
   * @param nota nota fiscal
   * @return sigla da UF aplicável (maiúsculas), ou {@code null} quando não há como determiná-la
   */
  private String resolveUfAplicavel(NotaFiscal nota) {
    Produtor produtor = nota.getProdutor();
    String ufEmitente = produtor != null ? normalizarUf(produtor.getEstado()) : null;

    EnderecoDestinatario endereco = nota.getDestinatarioEndereco();
    String ufDestino = endereco != null ? normalizarUf(endereco.getUf()) : null;

    // Operação interestadual (UFs distintas e ambas conhecidas) → alíquota da UF de destino.
    if (ufEmitente != null && ufDestino != null && !ufEmitente.equals(ufDestino)) {
      return ufDestino;
    }
    // Operação intraestadual (ou destino desconhecido) → alíquota da UF do emitente.
    if (ufEmitente != null) {
      return ufEmitente;
    }
    return ufDestino;
  }

  private String normalizarUf(String uf) {
    if (uf == null || uf.isBlank()) {
      return null;
    }
    return uf.trim().toUpperCase();
  }

  /**
   * Parcelas estadual e municipal de uma alíquota de IBS. O total é a soma das duas parcelas.
   *
   * @param estadual parcela estadual (proporção decimal)
   * @param municipal parcela municipal (proporção decimal)
   */
  private record IbsSplit(BigDecimal estadual, BigDecimal municipal) {
    BigDecimal total() {
      return estadual.add(municipal);
    }
  }

  /**
   * Resolve o percentual de redução aplicável ao item:
   * <ol>
   *   <li>se o item informa {@code percentualReducao} (não nulo), esse valor prevalece;</li>
   *   <li>senão, usa a redução padrão do NCM (tabela {@code ncm_agropecuario});</li>
   *   <li>se o NCM não for de referência ou não tiver redução, retorna {@link BigDecimal#ZERO}.</li>
   * </ol>
   *
   * @param item item da nota fiscal
   * @return percentual de redução efetivo (0–100), nunca nulo
   */
  private BigDecimal resolveReducao(NotaFiscalItem item) {
    if (item.getPercentualReducao() != null) {
      return item.getPercentualReducao();
    }
    String ncm = item.getNcm();
    if (ncm == null || ncm.isBlank()) {
      return BigDecimal.ZERO;
    }
    return ncmReferenciaRepository
        .findPercentualReducaoPadraoByCodigo(ncm)
        .orElse(BigDecimal.ZERO);
  }

  /**
   * Classifica a natureza tributária pelo 1.º dígito do CFOP: {@code 5/6/7} → DEBITO (saída);
   * {@code 1/2/3} → CREDITO (entrada); nulo/fora do padrão → {@code null} (conservador).
   *
   * @param cfop CFOP do item (pode ser nulo)
   * @return natureza tributária, ou {@code null} quando o CFOP não permite classificar
   */
  private NaturezaOperacaoTributaria classificarNatureza(String cfop) {
    if (cfop == null || cfop.isBlank()) {
      return null;
    }
    char digito = cfop.trim().charAt(0);
    return switch (digito) {
      case '1', '2', '3' -> NaturezaOperacaoTributaria.CREDITO;
      case '5', '6', '7' -> NaturezaOperacaoTributaria.DEBITO;
      default -> null;
    };
  }
}
