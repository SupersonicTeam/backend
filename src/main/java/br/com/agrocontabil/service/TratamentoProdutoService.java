package br.com.agrocontabil.service;

import br.com.agrocontabil.common.NormalizacaoBusca;
import br.com.agrocontabil.model.ClassificacaoTributariaAnexoItem;
import br.com.agrocontabil.model.NcmReferencia;
import br.com.agrocontabil.repository.ClassificacaoTributariaAnexoItemRepository;
import br.com.agrocontabil.repository.ClassificacaoTributariaAnexoNcmRepository;
import br.com.agrocontabil.repository.NcmReferenciaRepository;
import br.com.agrocontabil.service.TratamentoTributarioNcm.Origem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tratamento do IBS/CBS de um produto, pelo nome ou pelo NCM (issue #283, parte 1): a mesma tabela
 * oficial do cálculo da nota, lida pelo {@link ClassificacaoTributariaService} — a Ceres explica
 * exatamente o que o {@link ReducaoNcmResolver} aplica, e a contradição entre a busca de lei e o
 * cálculo some por construção.
 *
 * <p><strong>Como o produto é achado:</strong>
 * <ol>
 *   <li>código NCM de 8 dígitos: o próprio código, com o nome da referência de NCM (#282) quando
 *       ele está lá;</li>
 *   <li>nome: as palavras do termo no começo de palavras da descrição oficial dos NCMs da
 *       referência ("soja", "ovo" casa "Ovos frescos..."), sem acento nem caixa;</li>
 *   <li>sem NCM com esse nome: os itens dos anexos da LC 214/2025 pela mesma regra ("adubo" casa
 *       "Fertilizantes (adubos)..." do Anexo IX), com o tratamento de cada NCM do item agrupado.</li>
 * </ol>
 *
 * <p><strong>Condição que o dado não decide</strong> ("destinados à alimentação humana?", "in
 * natura?", "não cozidos?") vai em {@link TratamentoProduto#condicao()}, para a resposta dizê-la
 * em vez de afirmar. Vem do dado: a condição do item do anexo (só as que são condição de destino,
 * {@code DESTINAD...}; as demais células da coluna são o título do grupo do anexo), a cláusula
 * "desde que ..." da descrição oficial do {@code cClassTrib} e, no in natura, o próprio art. 137.
 *
 * <p>Leite cru, arroz em casca e cooperativa (#284, perguntas 2, 3 e 6) seguem o dado atual da
 * tabela, sem regra própria.
 */
@Service
public class TratamentoProdutoService {

  /** Máximo de produtos devolvidos numa consulta (minimização, como as demais funções). */
  public static final int LIMITE_PRODUTOS = 3;

  private static final Pattern NCM = Pattern.compile("\\d{8}");
  private static final Pattern ARTIGO = Pattern.compile("observado o art\\. (\\d+)");
  private static final Pattern DESDE_QUE = Pattern.compile("desde que ([^,.;]+)");
  private static final Set<String> PALAVRAS_VAZIAS =
      Set.of("de", "do", "da", "dos", "das", "em", "e", "o", "a", "os", "as", "para", "com");
  private static final int PALAVRA_MINIMA = 3;
  static final String CONDICAO_IN_NATURA =
      "vendido in natura, sem industrializacao (LC 214/2025, art. 137)";

  private final ClassificacaoTributariaService classificacaoService;
  private final NcmReferenciaRepository ncmReferenciaRepository;
  private final ClassificacaoTributariaAnexoItemRepository itemRepository;
  private final ClassificacaoTributariaAnexoNcmRepository anexoNcmRepository;

  /** Constrói o serviço sobre a tabela oficial e a referência de NCM. */
  public TratamentoProdutoService(ClassificacaoTributariaService classificacaoService,
      NcmReferenciaRepository ncmReferenciaRepository,
      ClassificacaoTributariaAnexoItemRepository itemRepository,
      ClassificacaoTributariaAnexoNcmRepository anexoNcmRepository) {
    this.classificacaoService = classificacaoService;
    this.ncmReferenciaRepository = ncmReferenciaRepository;
    this.itemRepository = itemRepository;
    this.anexoNcmRepository = anexoNcmRepository;
  }

  /**
   * Tratamento do produto na data.
   *
   * @param termo nome do produto ou NCM de 8 dígitos (pontos e espaços ignorados)
   * @param data  data de referência ({@code America/Sao_Paulo})
   * @return o que foi achado; lista vazia se nada casou
   */
  @Transactional(readOnly = true)
  public ConsultaTratamentoProduto consultar(String termo, LocalDate data) {
    String limpo = termo == null ? "" : termo.replaceAll("[.\\s-]", "");
    if (NCM.matcher(limpo).matches()) {
      String descricao = ncmReferenciaRepository.findById(limpo)
          .map(NcmReferencia::getDescricao).orElse(null);
      TratamentoTributarioNcm t = classificacaoService.tratamentoPorNcm(limpo, data);
      return new ConsultaTratamentoProduto(limpo, TipoBusca.NCM,
          List.of(produto(limpo, descricao, 1, t)), t.versaoTabela());
    }
    List<String> palavras = palavras(termo);
    if (palavras.isEmpty()) {
      return vazia(termo);
    }
    List<NcmReferencia> ncms = melhores(ncmReferenciaRepository.findAll(), palavras,
        NcmReferencia::getDescricao).stream()
        .sorted(Comparator.comparing(NcmReferencia::getCodigo))
        .limit(LIMITE_PRODUTOS)
        .toList();
    if (!ncms.isEmpty()) {
      Map<String, TratamentoTributarioNcm> tratamentos = classificacaoService.tratamentosPorNcm(
          ncms.stream().map(NcmReferencia::getCodigo).toList(), data);
      List<TratamentoProduto> produtos = ncms.stream()
          .map(n -> produto(n.getCodigo(), n.getDescricao(), 1, tratamentos.get(n.getCodigo())))
          .toList();
      return new ConsultaTratamentoProduto(termo.strip(), TipoBusca.NOME, produtos,
          versao(tratamentos.values()));
    }
    List<ClassificacaoTributariaAnexoItem> itens = melhores(itemRepository.findAll(), palavras,
        ClassificacaoTributariaAnexoItem::getDescricao).stream()
        .sorted(Comparator.comparing(ClassificacaoTributariaAnexoItem::getId))
        .toList();
    List<TratamentoProduto> produtos = new ArrayList<>();
    List<TratamentoTributarioNcm> todos = new ArrayList<>();
    for (ClassificacaoTributariaAnexoItem item : itens) {
      List<String> ncmsDoItem = anexoNcmRepository.findNcmsPermitidosDoItem(item.getId(), data);
      if (ncmsDoItem.isEmpty()) {
        continue;
      }
      Map<String, TratamentoTributarioNcm> tratamentos =
          classificacaoService.tratamentosPorNcm(ncmsDoItem, data);
      todos.addAll(tratamentos.values());
      // NCMs do item com o mesmo tratamento viram um so produto, com a contagem.
      Map<String, List<TratamentoTributarioNcm>> porHipotese = new LinkedHashMap<>();
      tratamentos.values().forEach(t ->
          porHipotese.computeIfAbsent(t.cClassTrib(), c -> new ArrayList<>()).add(t));
      for (List<TratamentoTributarioNcm> grupo : porHipotese.values()) {
        TratamentoTributarioNcm exemplo = grupo.get(0);
        produtos.add(produto(grupo.size() == 1 ? exemplo.ncm() : null, item.getDescricao(),
            grupo.size(), exemplo));
      }
      if (produtos.size() >= LIMITE_PRODUTOS) {
        break;
      }
    }
    if (produtos.isEmpty()) {
      return vazia(termo);
    }
    return new ConsultaTratamentoProduto(termo.strip(), TipoBusca.ITEM_ANEXO,
        List.copyOf(produtos.subList(0, Math.min(LIMITE_PRODUTOS, produtos.size()))),
        versao(todos));
  }

  private static ConsultaTratamentoProduto vazia(String termo) {
    return new ConsultaTratamentoProduto(termo == null ? "" : termo.strip(), TipoBusca.NOME,
        List.of(), null);
  }

  private static String versao(java.util.Collection<TratamentoTributarioNcm> tratamentos) {
    return tratamentos.stream().map(TratamentoTributarioNcm::versaoTabela)
        .filter(Objects::nonNull).findFirst().orElse(null);
  }

  private static TratamentoProduto produto(
      String ncm, String descricao, int quantidadeNcms, TratamentoTributarioNcm t) {
    Optional<EnquadramentoAnexo> escolhido = t.enquadramentos().stream()
        .filter(e -> e.cClassTrib().equals(t.cClassTrib()))
        .findFirst();
    return new TratamentoProduto(ncm, descricao, quantidadeNcms, t.cClassTrib(),
        t.percentualReducao(), t.origem(), escolhido.map(EnquadramentoAnexo::anexo).orElse(null),
        artigo(t.fundamento()), condicao(t, escolhido));
  }

  /** Artigo da LC 214/2025 na descrição oficial ("observado o art. 137 da ..."). */
  static String artigo(String fundamento) {
    if (fundamento == null) {
      return null;
    }
    Matcher m = ARTIGO.matcher(fundamento);
    return m.find() ? m.group(1) : null;
  }

  /** Condição que o dado não decide; nula quando não há. */
  static String condicao(TratamentoTributarioNcm t, Optional<EnquadramentoAnexo> escolhido) {
    List<String> partes = new ArrayList<>();
    if (t.origem() == Origem.IN_NATURA) {
      partes.add(CONDICAO_IN_NATURA);
    }
    escolhido.map(EnquadramentoAnexo::condicao)
        .filter(c -> c.toUpperCase(Locale.ROOT).contains("DESTINAD"))
        .map(c -> c.toLowerCase(new Locale("pt", "BR")))
        .ifPresent(partes::add);
    if (t.fundamento() != null) {
      Matcher m = DESDE_QUE.matcher(t.fundamento());
      if (m.find()) {
        partes.add("desde que " + m.group(1).strip());
      }
    }
    return partes.isEmpty() ? null : String.join("; ", partes);
  }

  /** Palavras de busca: sem acento, sem as vazias, singular aproximado (tira o "s" final). */
  static List<String> palavras(String termo) {
    List<String> palavras = new ArrayList<>();
    for (String p : NormalizacaoBusca.normalizar(termo).split("[^a-z0-9]+")) {
      if (p.length() < PALAVRA_MINIMA || PALAVRAS_VAZIAS.contains(p)) {
        continue;
      }
      palavras.add(p.length() > PALAVRA_MINIMA && p.endsWith("s")
          ? p.substring(0, p.length() - 1) : p);
    }
    return palavras;
  }

  /**
   * Os que casam todas as palavras; sem nenhum, os que casam a primeira (o produto: "soja em
   * graos" acha a soja, mesmo sem "grao" na descrição oficial). A primeira palavra é obrigatória:
   * "milho" nunca devolve a soja.
   */
  static <T> List<T> melhores(List<T> candidatos, List<String> palavras,
      Function<T, String> descricao) {
    List<T> comAPrimeira = candidatos.stream()
        .filter(c -> casa(palavras.subList(0, 1), descricao.apply(c)))
        .toList();
    List<T> comTodas = comAPrimeira.stream()
        .filter(c -> casa(palavras, descricao.apply(c)))
        .toList();
    return comTodas.isEmpty() ? comAPrimeira : comTodas;
  }

  /** Toda palavra do termo começa alguma palavra da descrição. */
  static boolean casa(List<String> palavras, String descricao) {
    List<String> daDescricao =
        List.of(NormalizacaoBusca.normalizar(descricao).split("[^a-z0-9]+"));
    return palavras.stream().allMatch(p -> daDescricao.stream().anyMatch(d -> d.startsWith(p)));
  }

  /** Como o produto foi achado. */
  public enum TipoBusca {
    /** Pelo código NCM. */
    NCM,
    /** Pelo nome, na referência de NCM. */
    NOME,
    /** Pelo nome, num item de anexo da LC 214/2025. */
    ITEM_ANEXO
  }

  /**
   * Resultado da consulta.
   *
   * @param termo        termo consultado (NCM sem pontos, ou o nome)
   * @param tipo         como foi achado
   * @param produtos     até {@link #LIMITE_PRODUTOS}; vazia se nada casou
   * @param versaoTabela versão da tabela oficial (ex.: {@code IT 2025.002 v1.70})
   */
  public record ConsultaTratamentoProduto(
      String termo, TipoBusca tipo, List<TratamentoProduto> produtos, String versaoTabela) {

    /** Cópia defensiva. */
    public ConsultaTratamentoProduto {
      produtos = List.copyOf(produtos);
    }
  }

  /**
   * Tratamento de um produto (um NCM, ou os NCMs de um item de anexo com o mesmo tratamento).
   *
   * @param ncm               NCM; nulo quando o grupo tem mais de um
   * @param descricao         descrição oficial do NCM ou do item do anexo; pode ser nula
   * @param quantidadeNcms    NCMs do grupo
   * @param cClassTrib        classificação aplicada (a mesma do cálculo da nota)
   * @param percentualReducao redução da alíquota, de 0 a 100
   * @param origem            anexo, in natura ou sem redução
   * @param anexo             número do anexo da LC 214/2025; nulo fora de anexo
   * @param artigo            artigo da LC 214/2025 do {@code cClassTrib}; nulo na tributação
   *                          integral
   * @param condicao          condição que o dado não decide; nula quando não há
   */
  public record TratamentoProduto(String ncm, String descricao, int quantidadeNcms,
      String cClassTrib, BigDecimal percentualReducao, Origem origem, Integer anexo,
      String artigo, String condicao) {}
}
