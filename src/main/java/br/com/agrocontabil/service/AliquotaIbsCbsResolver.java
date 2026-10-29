package br.com.agrocontabil.service;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.model.AliquotaCbs;
import br.com.agrocontabil.model.AliquotaIbsUf;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.AliquotaCbsRepository;
import br.com.agrocontabil.repository.AliquotaIbsUfRepository;
import br.com.agrocontabil.service.AliquotasVigentes.OrigemAliquota;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolve as alíquotas de IBS (por UF) e CBS aplicáveis a uma nota fiscal, vigentes na data de
 * emissão (issue Agrocontabil#86 — simetria débito × crédito, RN002; issue #85 — vigência
 * temporal).
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
 * <p><strong>Vigência (issue #85):</strong> a data de referência é a {@code dataEmissao} da nota
 * convertida para {@code America/Sao_Paulo} (uma nota {@code 2027-01-01T01:00Z} é 2026-12-31 em
 * BRT). Sem {@code dataEmissao} (só possível em fixture — a API exige o campo), usa-se a data de
 * hoje em BRT com {@code WARN}. IBS vem de {@code aliquota_ibs_uf} (UF + vigência) e CBS de
 * {@code aliquota_cbs} (vigência). Em sobreposição acidental de vigências, vence a de início mais
 * recente (query ordenada por {@code data_inicio DESC}).
 *
 * <p><strong>Fallback conservador:</strong> quando a UF não é determinável, não consta na tabela
 * ou a data está fora de qualquer vigência (ex.: nota anterior a 2026-01-01 ou posterior à última
 * vigência cadastrada), a alíquota de IBS de {@link TributosProperties} é dividida igualmente entre
 * estado e município (comportamento anterior à issue #31 preservado) e um {@code WARN} é
 * registrado; o mesmo vale para a CBS. Nunca lança — decisão do spec da Onda 2.
 *
 * <p><strong>Vigências semeadas:</strong> IBS de 2026 só estadual (0,1%) e de 2027–2028 dividido
 * 0,05% + 0,05% (changeset {@code 0047}, issue #277); CBS só de 2026. As demais alíquotas da
 * transição (LC 214/2025, arts. 87–92) entram por novo changeset quando forem publicadas.
 *
 * <p>Consulta a tabela de referência (leitura); deve ser chamado dentro de uma transação
 * read-only pelo serviço que o utiliza. Os logs não contêm CPF/CNPJ.
 */
@Component
public class AliquotaIbsCbsResolver {

  private static final Logger log = LoggerFactory.getLogger(AliquotaIbsCbsResolver.class);
  private static final BigDecimal DOIS = BigDecimal.valueOf(2);

  /**
   * Primeiro dia com parcela municipal de IBS (LC 214/2025, art. 344). Em 2026 o IBS é só
   * estadual (EC 132/2023; NT 2026.010 v1.00, item 2; IT 2025.002 v1.70, seção 05) — issue #277.
   * Usado só no fallback: a tabela {@code aliquota_ibs_uf} já traz a divisão por vigência.
   */
  static final LocalDate INICIO_IBS_MUNICIPAL = LocalDate.of(2027, 1, 1);
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  private final AliquotaIbsUfRepository aliquotaIbsUfRepository;
  private final AliquotaCbsRepository aliquotaCbsRepository;
  private final TributosProperties tributosProperties;

  /**
   * Constrói o resolver.
   *
   * @param aliquotaIbsUfRepository repositório read-only da tabela {@code aliquota_ibs_uf}
   * @param aliquotaCbsRepository repositório read-only da tabela {@code aliquota_cbs}
   * @param tributosProperties alíquotas parametrizadas do {@code application.yml} (fallback)
   */
  public AliquotaIbsCbsResolver(
      AliquotaIbsUfRepository aliquotaIbsUfRepository,
      AliquotaCbsRepository aliquotaCbsRepository,
      TributosProperties tributosProperties) {
    this.aliquotaIbsUfRepository = aliquotaIbsUfRepository;
    this.aliquotaCbsRepository = aliquotaCbsRepository;
    this.tributosProperties = tributosProperties;
  }

  /**
   * Resolve as alíquotas de IBS e CBS aplicáveis à nota.
   *
   * @param nota nota fiscal (produtor, origem e endereço do destinatário são consultados)
   * @return alíquotas aplicáveis, nunca nulas
   */
  public AliquotasVigentes resolver(NotaFiscal nota) {
    LocalDate data = resolveDataReferencia(nota);
    String uf = resolveUfAplicavel(nota);

    Optional<AliquotaIbsUf> ibs = uf != null
        ? aliquotaIbsUfRepository.findVigente(uf, data).stream().findFirst()
        : Optional.empty();
    Optional<AliquotaCbs> cbs = aliquotaCbsRepository.findVigente(data).stream().findFirst();

    BigDecimal ibsEstadual;
    BigDecimal ibsMunicipal;
    OrigemAliquota origemIbs;
    if (ibs.isPresent()) {
      ibsEstadual = ibs.get().getAliquotaEstadual();
      ibsMunicipal = ibs.get().getAliquotaMunicipal();
      origemIbs = OrigemAliquota.TABELA_UF;
    } else {
      // notaId e nulo no create (calculo roda antes do save); produtorId da contexto sem PII.
      log.warn(
          "Aliquota de IBS por UF indisponivel (uf={}, data={}, notaId={}, produtorId={});"
              + " usando fallback do application.yml",
          uf, data, nota.getId(), produtorId(nota));
      BigDecimal configurada = tributosProperties.getIbs().getAliquota();
      if (data.isBefore(INICIO_IBS_MUNICIPAL)) {
        // 2026 (fase de teste): IBS so estadual (EC 132/2023; NT 2026.010, item 2) — #277.
        ibsEstadual = configurada;
        ibsMunicipal = BigDecimal.ZERO.setScale(configurada.scale());
      } else {
        // A partir de 2027 o IBS tem as duas parcelas (LC 214/2025, art. 344).
        ibsEstadual = configurada.divide(DOIS, 4, RoundingMode.HALF_UP);
        ibsMunicipal = configurada.subtract(ibsEstadual);
      }
      origemIbs = OrigemAliquota.FALLBACK_YML;
    }

    BigDecimal aliquotaCbs;
    OrigemAliquota origemCbs;
    if (cbs.isPresent()) {
      aliquotaCbs = cbs.get().getAliquota();
      origemCbs = OrigemAliquota.TABELA_VIGENCIA;
    } else {
      log.warn(
          "Aliquota de CBS sem vigencia para data={} (notaId={}, produtorId={});"
              + " usando fallback do application.yml",
          data, nota.getId(), produtorId(nota));
      aliquotaCbs = tributosProperties.getCbs().getAliquota();
      origemCbs = OrigemAliquota.FALLBACK_YML;
    }

    return new AliquotasVigentes(ibsEstadual, ibsMunicipal, aliquotaCbs, origemIbs, origemCbs);
  }

  /**
   * Data de referência da vigência: {@code dataEmissao} em {@code America/Sao_Paulo}; sem data
   * (fixture), hoje em BRT com {@code WARN}.
   */
  private LocalDate resolveDataReferencia(NotaFiscal nota) {
    OffsetDateTime emissao = nota.getDataEmissao();
    if (emissao == null) {
      log.warn(
          "Nota sem dataEmissao (notaId={}, produtorId={}); vigencia resolvida pela data de hoje",
          nota.getId(), produtorId(nota));
      return LocalDate.now(ZONE);
    }
    return emissao.atZoneSameInstant(ZONE).toLocalDate();
  }

  private static Object produtorId(NotaFiscal nota) {
    return nota.getProdutor() != null ? nota.getProdutor().getId() : null;
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
