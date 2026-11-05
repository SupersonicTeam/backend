package br.com.agrocontabil.service;

import br.com.agrocontabil.model.ClassificacaoTributaria;
import br.com.agrocontabil.model.ClassificacaoTributariaAnexoItem;
import br.com.agrocontabil.model.ClassificacaoTributariaAnexoNcm;
import br.com.agrocontabil.model.ClassificacaoTributariaAnexoNcm.Permissao;
import br.com.agrocontabil.repository.ClassificacaoTributariaAnexoNcmRepository;
import br.com.agrocontabil.repository.ClassificacaoTributariaRepository;
import br.com.agrocontabil.repository.NcmReferenciaRepository;
import br.com.agrocontabil.service.TratamentoTributarioNcm.Origem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consulta da tabela oficial de classificação tributária do IBS e da CBS por NCM (issue #272).
 *
 * <p>Fonte: Informe Técnico 2025.002 (cClassTrib) e os itens dos anexos da LC 214/2025 por NCM,
 * carregados pelo changeSet {@code 0048}. Serviço de domínio <strong>independente do cálculo da
 * nota</strong>: o {@link ReducaoNcmResolver} o consome para a redução, e a Ceres (#283) o usa para
 * explicar a regra, sem passar pelo resolvedor.
 *
 * <p><strong>Precedência</strong> (o mais benéfico vence; assunção da #284, pergunta 5):
 * <ol>
 *   <li>Anexo I (200003, cesta básica, art. 125) ou Anexo XV (200014, hortícolas, frutas e ovos,
 *       art. 148): redução de 100% (alíquota zero);</li>
 *   <li>produto in natura (200036, art. 137, 60%), pela marca da referência — o art. 137 vale
 *       pela condição e não tem lista de NCM;</li>
 *   <li>Anexo IX (200038, insumos, art. 138) ou Anexo VII (200034, alimentos, art. 135): 60%;</li>
 *   <li>senão, tributação integral (000001).</li>
 * </ol>
 * Um NCM só se enquadra num {@code cClassTrib} se tem linha {@code PERMITIDO} e nenhuma
 * {@code VEDADO} vigente para ele (a tabela veda, por exemplo, no Anexo VII o que está no Anexo I).
 *
 * <p><strong>Pendências de validação (#284):</strong> leite cru vendido ao laticínio (pergunta 2:
 * o Anexo I traz o leite "para consumo direto") e venda a cooperativa optante (pergunta 6:
 * cClassTrib 200020 depende da opção da cooperativa, não do NCM). Aqui vale o dado oficial por
 * NCM, sem regra própria para essas situações. A marca in natura não é dado oficial: é da
 * referência do projeto (o art. 137 não tem lista); o arroz em casca não parboilizado
 * (pergunta 3) a recebeu pela resposta do escritório (changeSet 0051).
 */
@Service
public class ClassificacaoTributariaService {

  private static final Logger log = LoggerFactory.getLogger(ClassificacaoTributariaService.class);

  static final String INTEGRAL = "000001";
  static final String IN_NATURA = "200036";

  /** Anexos de alíquota zero (I e XV), que vencem o in natura. */
  static final List<String> ANEXOS_ZERO = List.of("200003", "200014");

  /** Anexos de 60% (IX e VII), depois do in natura. */
  static final List<String> ANEXOS_SESSENTA = List.of("200038", "200034");

  /** Todos os cClassTrib com anexo, na ordem de precedência. */
  static final List<String> PRECEDENCIA_ANEXOS =
      List.of(ANEXOS_ZERO.get(0), ANEXOS_ZERO.get(1), ANEXOS_SESSENTA.get(0),
          ANEXOS_SESSENTA.get(1));

  private final ClassificacaoTributariaRepository classificacaoRepository;
  private final ClassificacaoTributariaAnexoNcmRepository anexoNcmRepository;
  private final NcmReferenciaRepository ncmReferenciaRepository;

  /**
   * Constrói o serviço.
   *
   * @param classificacaoRepository tabela de cClassTrib
   * @param anexoNcmRepository NCMs dos itens de anexo
   * @param ncmReferenciaRepository referência de NCM (marca de in natura)
   */
  public ClassificacaoTributariaService(
      ClassificacaoTributariaRepository classificacaoRepository,
      ClassificacaoTributariaAnexoNcmRepository anexoNcmRepository,
      NcmReferenciaRepository ncmReferenciaRepository) {
    this.classificacaoRepository = classificacaoRepository;
    this.anexoNcmRepository = anexoNcmRepository;
    this.ncmReferenciaRepository = ncmReferenciaRepository;
  }

  /**
   * Tratamento do IBS/CBS do NCM na data, pela precedência descrita na classe.
   *
   * @param ncm código NCM de 8 dígitos
   * @param data data de referência (emissão em {@code America/Sao_Paulo})
   * @return tratamento aplicável, nunca nulo (sem hipótese → tributação integral)
   */
  @Transactional(readOnly = true)
  public TratamentoTributarioNcm tratamentoPorNcm(String ncm, LocalDate data) {
    return escolher(ncm, enquadramentos(ncm, data), () -> inNatura(ncm), data,
        codigo -> classificacao(codigo, data));
  }

  /**
   * Tratamento de vários NCMs na mesma data, com a mesma precedência de
   * {@link #tratamentoPorNcm}, em três consultas em vez de três por NCM (issue #283: a Ceres
   * explica um item de anexo inteiro, com dezenas de NCMs).
   *
   * @param ncms códigos NCM de 8 dígitos (vazios e repetidos são ignorados)
   * @param data data de referência
   * @return NCM → tratamento, na ordem recebida
   */
  @Transactional(readOnly = true)
  public Map<String, TratamentoTributarioNcm> tratamentosPorNcm(
      Collection<String> ncms, LocalDate data) {
    List<String> codigos = ncms.stream()
        .filter(n -> n != null && !n.isBlank())
        .distinct()
        .toList();
    if (codigos.isEmpty()) {
      return Map.of();
    }
    Map<String, List<ClassificacaoTributariaAnexoNcm>> linhasPorNcm = anexoNcmRepository
        .findVigentesEm(codigos, data).stream()
        .collect(Collectors.groupingBy(ClassificacaoTributariaAnexoNcm::getNcm));
    Set<String> inNatura = Set.copyOf(ncmReferenciaRepository.findCodigosInNatura(codigos));
    Map<String, Optional<ClassificacaoTributaria>> classificacoes = new HashMap<>();
    Function<String, Optional<ClassificacaoTributaria>> classificacaoDoLote =
        codigo -> classificacoes.computeIfAbsent(codigo, c -> classificacao(c, data));
    Map<String, TratamentoTributarioNcm> tratamentos = new LinkedHashMap<>();
    for (String ncm : codigos) {
      List<EnquadramentoAnexo> enquadramentos =
          enquadramentosDe(linhasPorNcm.getOrDefault(ncm, List.of()));
      tratamentos.put(ncm, escolher(ncm, enquadramentos, () -> inNatura.contains(ncm), data,
          classificacaoDoLote));
    }
    return tratamentos;
  }

  /**
   * A precedência da classe, sobre os enquadramentos já lidos. O in natura só é consultado se
   * nenhum anexo de alíquota zero venceu (no cálculo da nota, uma consulta a menos por item).
   */
  private TratamentoTributarioNcm escolher(String ncm, List<EnquadramentoAnexo> enquadramentos,
      BooleanSupplier inNatura, LocalDate data,
      Function<String, Optional<ClassificacaoTributaria>> classificacoes) {
    Set<String> enquadrados = enquadramentos.stream()
        .map(EnquadramentoAnexo::cClassTrib)
        .collect(Collectors.toSet());

    Optional<String> zero = ANEXOS_ZERO.stream().filter(enquadrados::contains).findFirst();
    if (zero.isPresent()) {
      return tratamento(ncm, zero.get(), Origem.ANEXO, data, enquadramentos, classificacoes);
    }
    if (inNatura.getAsBoolean()) {
      return tratamento(ncm, IN_NATURA, Origem.IN_NATURA, data, enquadramentos, classificacoes);
    }
    Optional<String> sessenta =
        ANEXOS_SESSENTA.stream().filter(enquadrados::contains).findFirst();
    if (sessenta.isPresent()) {
      return tratamento(ncm, sessenta.get(), Origem.ANEXO, data, enquadramentos, classificacoes);
    }
    return tratamento(ncm, INTEGRAL, Origem.SEM_REDUCAO, data, enquadramentos, classificacoes);
  }

  /**
   * Itens de anexo em que o NCM se enquadra na data: com linha {@code PERMITIDO} e sem
   * {@code VEDADO} no mesmo {@code cClassTrib}. Na ordem de precedência.
   *
   * @param ncm código NCM de 8 dígitos
   * @param data data de referência
   * @return enquadramentos; vazia se nenhum
   */
  @Transactional(readOnly = true)
  public List<EnquadramentoAnexo> enquadramentos(String ncm, LocalDate data) {
    if (ncm == null || ncm.isBlank()) {
      return List.of();
    }
    return enquadramentosDe(anexoNcmRepository.findVigentes(ncm, data));
  }

  /** Itens permitidos e não vedados, na ordem de precedência, a partir das linhas de um NCM. */
  private static List<EnquadramentoAnexo> enquadramentosDe(
      List<ClassificacaoTributariaAnexoNcm> linhas) {
    Set<String> vedados = linhas.stream()
        .filter(l -> l.getPermissao() == Permissao.VEDADO)
        .map(l -> l.getItem().getCClassTrib())
        .collect(Collectors.toSet());
    Map<Integer, EnquadramentoAnexo> porItem = new LinkedHashMap<>();
    linhas.stream()
        .filter(l -> l.getPermissao() == Permissao.PERMITIDO)
        .map(ClassificacaoTributariaAnexoNcm::getItem)
        .filter(item -> !vedados.contains(item.getCClassTrib()))
        .sorted(Comparator.comparingInt(ClassificacaoTributariaService::ordem)
            .thenComparing(ClassificacaoTributariaAnexoItem::getId))
        .forEach(item -> porItem.putIfAbsent(item.getId(), enquadramento(item)));
    return List.copyOf(porItem.values());
  }

  /**
   * Classificação oficial pelo código, se vigente na data. Serve para conferir um
   * {@code cClassTrib} informado pelo cliente.
   *
   * @param cClassTrib código de 6 dígitos
   * @param data data de referência
   * @return a classificação vigente, ou vazio se o código não existe ou não vale na data
   */
  @Transactional(readOnly = true)
  public Optional<ClassificacaoTributaria> classificacao(String cClassTrib, LocalDate data) {
    if (cClassTrib == null) {
      return Optional.empty();
    }
    return classificacaoRepository.findVigente(cClassTrib, data).stream().findFirst();
  }

  private TratamentoTributarioNcm tratamento(String ncm, String codigo, Origem origem,
      LocalDate data, List<EnquadramentoAnexo> enquadramentos,
      Function<String, Optional<ClassificacaoTributaria>> classificacoes) {
    Optional<ClassificacaoTributaria> classificacao = classificacoes.apply(codigo);
    BigDecimal reducao = classificacao
        .map(ClassificacaoTributaria::getPercentualReducaoIbs)
        .orElse(BigDecimal.ZERO);
    if (classificacao.isEmpty() && origem != Origem.SEM_REDUCAO) {
      // Classificacao ausente ou fora de vigencia (tabela nao carregada, versao fechada sem
      // sucessora): sem reducao, como o integral, mas nunca em silencio.
      log.warn("cClassTrib {} sem vigencia em {} (ncm={}); IBS/CBS sem reducao", codigo, data,
          ncm);
      return new TratamentoTributarioNcm(ncm, INTEGRAL, INTEGRAL.substring(0, 3), BigDecimal.ZERO,
          Origem.SEM_REDUCAO, null, enquadramentos, null);
    }
    return new TratamentoTributarioNcm(ncm, codigo, codigo.substring(0, 3), reducao, origem,
        classificacao.map(ClassificacaoTributaria::getDescricao).orElse(null), enquadramentos,
        classificacao.map(ClassificacaoTributaria::getVersaoTabela).orElse(null));
  }

  private boolean inNatura(String ncm) {
    return ncm != null && ncmReferenciaRepository.existsByCodigoAndInNaturaTrue(ncm);
  }

  private static int ordem(ClassificacaoTributariaAnexoItem item) {
    int indice = PRECEDENCIA_ANEXOS.indexOf(item.getCClassTrib());
    return indice < 0 ? Integer.MAX_VALUE : indice;
  }

  private static EnquadramentoAnexo enquadramento(ClassificacaoTributariaAnexoItem item) {
    return new EnquadramentoAnexo(item.getCClassTrib(), item.getAnexo(), item.getDescricao(),
        vazioComoNulo(item.getCondicao()), vazioComoNulo(item.getExcecao()));
  }

  /**
   * O {@code loadData} do Liquibase grava a célula vazia do CSV como {@code ''} no PostgreSQL, e o
   * {@code CSVREAD} do H2 a lê como {@code NULL}: aqui as duas viram nulo.
   */
  private static String vazioComoNulo(String texto) {
    return texto == null || texto.isBlank() ? null : texto;
  }
}
