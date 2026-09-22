package br.com.agrocontabil.service;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.model.AliquotaIbsUf;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.AliquotaIbsUfRepository;
import br.com.agrocontabil.service.AliquotasVigentes.OrigemAliquota;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolve as alíquotas de IBS (por UF) e CBS aplicáveis a uma nota fiscal
 * (issue Agrocontabil#86 — simetria débito × crédito, RN002).
 *
 * <p>Componente <strong>único</strong> de resolução de alíquota, consumido tanto pelo cálculo de
 * <em>débito</em> ({@link TributacaoService}) quanto pelo de <em>crédito</em>
 * ({@link CreditoTributarioService}) — o mesmo padrão do {@link ReducaoNcmResolver} (issue #52).
 * Antes da issue #86 o débito resolvia a alíquota pela tabela {@code aliquota_ibs_uf} enquanto o
 * crédito usava a alíquota fixa de {@code application.yml}; coincidiam apenas porque o seed é
 * uniforme. Centralizar a regra elimina a fonte da divergência por construção.
 *
 * <p><strong>UF aplicável (RN002):</strong>
 * <ul>
 *   <li>Nota <em>própria</em>: em operação <em>interestadual</em> (UF do emitente ≠ UF do
 *       destinatário) usa-se a UF de <strong>destino</strong>; em operação
 *       <em>intraestadual</em> (ou destino desconhecido) a UF do <strong>emitente</strong>.</li>
 *   <li>Nota <em>importada</em> ({@link OrigemNotaFiscal#IMPORTADA}): o produtor é o
 *       destinatário e a UF do emitente terceiro não é persistida. Pelo princípio do destino
 *       (EC 132/2023, art. 156-A, § 1.º, VII) a UF aplicável é sempre a do
 *       <strong>destinatário</strong> — tanto na operação interestadual (destino) quanto na
 *       intraestadual (emitente = destino). Usa-se a UF do endereço do destinatário vinda do
 *       XML; na ausência, a UF do cadastro do produtor. Divergência entre as duas gera
 *       {@code WARN}.</li>
 * </ul>
 *
 * <p><strong>Fallback conservador:</strong> quando a UF não é determinável ou não consta na
 * tabela, a alíquota de IBS de {@link TributosProperties} é dividida igualmente entre estado e
 * município (comportamento anterior à issue #31 preservado) e um {@code WARN} é registrado. A CBS
 * vem de {@link TributosProperties} até a tabela de vigência da issue #85.
 *
 * <p>Consulta a tabela de referência (leitura); deve ser chamado dentro de uma transação
 * read-only pelo serviço que o utiliza. Os logs não contêm CPF/CNPJ.
 */
@Component
public class AliquotaIbsCbsResolver {

  private static final Logger log = LoggerFactory.getLogger(AliquotaIbsCbsResolver.class);
  private static final BigDecimal DOIS = BigDecimal.valueOf(2);

  private final AliquotaIbsUfRepository aliquotaIbsUfRepository;
  private final TributosProperties tributosProperties;

  /**
   * Constrói o resolver.
   *
   * @param aliquotaIbsUfRepository repositório read-only da tabela {@code aliquota_ibs_uf}
   * @param tributosProperties alíquotas parametrizadas do {@code application.yml} (fallback)
   */
  public AliquotaIbsCbsResolver(
      AliquotaIbsUfRepository aliquotaIbsUfRepository, TributosProperties tributosProperties) {
    this.aliquotaIbsUfRepository = aliquotaIbsUfRepository;
    this.tributosProperties = tributosProperties;
  }

  /**
   * Resolve as alíquotas de IBS e CBS aplicáveis à nota.
   *
   * @param nota nota fiscal (produtor, origem e endereço do destinatário são consultados)
   * @return alíquotas aplicáveis, nunca nulas
   */
  public AliquotasVigentes resolver(NotaFiscal nota) {
    String uf = resolveUfAplicavel(nota);
    Optional<AliquotaIbsUf> referencia =
        uf != null ? aliquotaIbsUfRepository.findByUf(uf) : Optional.empty();

    BigDecimal cbs = tributosProperties.getCbs().getAliquota();

    if (referencia.isPresent()) {
      AliquotaIbsUf a = referencia.get();
      return new AliquotasVigentes(
          a.getAliquotaEstadual(),
          a.getAliquotaMunicipal(),
          cbs,
          OrigemAliquota.TABELA_UF,
          OrigemAliquota.FALLBACK_YML);
    }

    log.warn(
        "Aliquota de IBS por UF indisponivel (uf={}, notaId={}); usando fallback do application.yml",
        uf, nota.getId());
    BigDecimal configurada = tributosProperties.getIbs().getAliquota();
    BigDecimal estadual = configurada.divide(DOIS, 4, RoundingMode.HALF_UP);
    BigDecimal municipal = configurada.subtract(estadual);
    return new AliquotasVigentes(
        estadual, municipal, cbs, OrigemAliquota.FALLBACK_YML, OrigemAliquota.FALLBACK_YML);
  }

  /**
   * Determina a UF cuja alíquota de IBS se aplica (ver Javadoc da classe).
   *
   * @return sigla da UF (maiúsculas), ou {@code null} quando não há como determiná-la
   */
  private String resolveUfAplicavel(NotaFiscal nota) {
    Produtor produtor = nota.getProdutor();
    String ufProdutor = produtor != null ? normalizarUf(produtor.getEstado()) : null;

    EnderecoDestinatario endereco = nota.getDestinatarioEndereco();
    String ufDestino = endereco != null ? normalizarUf(endereco.getUf()) : null;

    if (nota.getOrigem() == OrigemNotaFiscal.IMPORTADA) {
      // Produtor = destinatario; a UF do emitente terceiro nao e persistida. Principio do destino.
      if (ufDestino != null && ufProdutor != null && !ufDestino.equals(ufProdutor)) {
        log.warn(
            "Nota importada {} com UF do destinatario ({}) divergente do cadastro do produtor ({});"
                + " prevalece a UF do XML",
            nota.getId(), ufDestino, ufProdutor);
      }
      return ufDestino != null ? ufDestino : ufProdutor;
    }

    // Nota propria: interestadual (UFs distintas e ambas conhecidas) → UF de destino.
    if (ufProdutor != null && ufDestino != null && !ufProdutor.equals(ufDestino)) {
      return ufDestino;
    }
    // Intraestadual (ou destino desconhecido) → UF do emitente.
    if (ufProdutor != null) {
      return ufProdutor;
    }
    return ufDestino;
  }

  private static String normalizarUf(String uf) {
    if (uf == null || uf.isBlank()) {
      return null;
    }
    return uf.trim().toUpperCase();
  }
}
