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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 *   <li>código NCM de 8 dígitos (com ou sem pontos e o prefixo "NCM"): o próprio código, com o
 *       nome da referência de NCM (#282) quando ele está lá. Código fora da referência e de todo
 *       anexo sai {@linkplain TratamentoProduto#ncmConhecido() marcado}, para a resposta pedir a
 *       conferência do código em vez de afirmar a alíquota cheia;</li>
 *   <li>nome: as palavras do termo, sem acento, caixa nem plural ("feijões" = "feijão", "ovos" =
 *       "ovo"), no começo de palavras da descrição oficial dos NCMs da referência e dos itens dos
 *       anexos da LC 214/2025. Vence quem casa as palavras <strong>mais raras</strong> no conjunto
 *       das descrições ("grãos de soja" acha a soja, não o café em grão; "ovos fertilizados" acha
 *       o item do Anexo IX, não o ovo fresco). No empate, o NCM da referência vence o item de
 *       anexo; o item de anexo traz o tratamento de cada NCM dele, agrupado.</li>
 * </ol>
 *
 * <p><strong>Negação (#369):</strong> o que a descrição oficial exclui não conta para o
 * casamento. "Soja, mesmo triturada, exceto para semeadura" não é achada por "semeadura": o
 * trecho de {@code exceto}, {@code excetuados}, {@code excluídos}, {@code ressalvados} e
 * {@code salvo} (até a vírgula, o ponto e vírgula, o ponto final ou o parêntese que fecha) e a
 * palavra depois de {@code não}/{@code nem} saem da descrição, e o mesmo vale para a pergunta
 * ({@link #semTrechoNegado}).
 *
 * <p><strong>Condição que o dado não decide</strong> vai em {@link TratamentoProduto#condicao()},
 * para a resposta dizê-la em vez de afirmar. Vem do dado: no in natura, o próprio art. 137; a
 * condição de destino do item ("destinados à alimentação humana"), da coluna de condição ou da
 * descrição do item (no Anexo IX ela está no texto: "destinados diretamente à fabricação de
 * ração"); a exceção do item; e a cláusula "desde que ..." da descrição oficial do
 * {@code cClassTrib}. As demais células da coluna de condição são o título do grupo do anexo, não
 * condição.
 *
 * <p><strong>Leite</strong> (#284, pergunta 2): os NCMs do Anexo I, item 2, que não são UHT saem
 * in natura (60%, art. 137), como no cálculo da nota, e o produto leva a {@link Alternativa} da
 * alíquota zero (Anexo I, art. 125, cClassTrib 200003) para o leite de consumo direto: a resposta
 * diz as duas situações e que o enquadramento é com o contador. O UHT (#380) tem zero pela regra e
 * a condição lembra que o leite cru é outro NCM, com 60%. A cooperativa (pergunta 6) segue o dado atual
 * da tabela, sem regra própria; o arroz em casca não parboilizado (pergunta 3) é in natura pela
 * marca da referência (changeSet 0051).
 */
@Service
public class TratamentoProdutoService {

  /** Máximo de produtos devolvidos numa consulta (minimização, como as demais funções). */
  public static final int LIMITE_PRODUTOS = 3;

  static final String CONDICAO_IN_NATURA =
      "vendido in natura, sem industrializacao (LC 214/2025, art. 137)";

  static final String CONDICAO_LEITE_IN_NATURA = "leite cru ou apenas resfriado para o"
      + " transporte ou o armazenamento, vendido in natura (LC 214/2025, art. 137, par. 1, II)";

  /**
   * UHT (#380): zero sem condição a decidir, mas a frase lembra que o leite cru não é este caso,
   * para o produtor de leite que pergunta só "leite" não ler zero para a venda ao laticínio.
   */
  static final String CONDICAO_LEITE_UHT = "leite UHT, sempre de consumo direto (Anexo I, item"
      + " 2); o leite cru ou apenas resfriado vendido ao laticinio e outro NCM (0401.20.90 e"
      + " outros) e sai com 60%, in natura (LC 214/2025, art. 137)";

  static final String CONDICAO_LEITE_CONSUMO_DIRETO = "leite em conformidade com os requisitos"
      + " da legislacao especifica relativos ao consumo direto pela populacao (Anexo I, item 2)";

  private static final Pattern NCM = Pattern.compile("\\d{8}");
  private static final Pattern PREFIXO_NCM = Pattern.compile("(?i)^\\s*ncm\\b[:\\s]*");
  /** "observado o art. 137 da Lei Complementar nº 214": a hipótese; outros "art." são contexto. */
  private static final Pattern ARTIGO =
      Pattern.compile("(?i)observad[oa]s? (?:o |os )?arts?\\. (\\d+)");
  private static final Pattern DESDE_QUE = Pattern.compile("(?i)desde que ([^,.;]+)");
  private static final Pattern DESTINO_NO_ITEM =
      Pattern.compile("(?i)\\b((?:todos |todas )?destinad[oa]s? [^;.]+)");
  /** Palavras que negam o que vem depois (#369), já sem acento (a busca normaliza antes). */
  private static final Pattern NEGACAO = Pattern.compile(
      "\\b(?:exceto|excetuad[oa]s?|excluid[oa]s?|ressalvad[oa]s?|salvo|nao|nem)\\b");
  private static final Pattern PROXIMA_PALAVRA = Pattern.compile("[a-z0-9]+");
  private static final Set<String> PALAVRAS_VAZIAS = Set.of("de", "do", "da", "dos", "das",
      "em", "e", "o", "a", "os", "as", "para", "com", "ncm", "sh", "nbs", "tem", "paga", "que");
  private static final int PALAVRA_MINIMA = 3;
  private static final int EXCECAO_MAXIMA = 160;
  private static final double EMPATE = 1e-9;

  private final ClassificacaoTributariaService classificacaoService;
  private final NcmReferenciaRepository ncmReferenciaRepository;
  private final ClassificacaoTributariaAnexoItemRepository itemRepository;
  private final ClassificacaoTributariaAnexoNcmRepository anexoNcmRepository;

  /**
   * Descrições já normalizadas: tabelas estáticas, que só mudam com changeSet novo e nova subida.
   */
  private volatile Catalogo catalogo;

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
   * @param termo nome do produto ou NCM de 8 dígitos (pontos, espaços e o prefixo "NCM"
   *              ignorados)
   * @param data  data de referência ({@code America/Sao_Paulo})
   * @return o que foi achado; lista vazia se nada casou
   */
  @Transactional(readOnly = true)
  public ConsultaTratamentoProduto consultar(String termo, LocalDate data) {
    String codigo = codigoNcm(termo);
    if (codigo != null) {
      return porNcm(codigo, data);
    }
    List<String> palavras = palavras(termo);
    if (palavras.isEmpty()) {
      return vazia(termo);
    }
    Catalogo c = catalogo();
    List<Descrito> escolhidos = melhores(c, palavras);
    if (escolhidos.isEmpty()) {
      return vazia(termo);
    }
    List<String> ncms = new ArrayList<>();
    Map<Integer, List<String>> ncmsPorItem = new LinkedHashMap<>();
    for (Descrito d : escolhidos) {
      if (d.ncm() != null) {
        ncms.add(d.ncm());
      } else {
        List<String> doItem = anexoNcmRepository.findNcmsPermitidosDoItem(d.itemId(), data);
        ncmsPorItem.put(d.itemId(), doItem);
        ncms.addAll(doItem);
      }
    }
    Map<String, TratamentoTributarioNcm> tratamentos =
        classificacaoService.tratamentosPorNcm(ncms, data);
    List<TratamentoProduto> produtos = new ArrayList<>();
    for (Descrito d : escolhidos) {
      if (d.ncm() != null) {
        produtos.add(produto(d.ncm(), d.descricao(), 1, tratamentos.get(d.ncm()), true, data));
        continue;
      }
      // NCMs do item com o mesmo tratamento viram um so produto, com a contagem.
      Map<String, List<TratamentoTributarioNcm>> porHipotese = new LinkedHashMap<>();
      for (String ncm : ncmsPorItem.getOrDefault(d.itemId(), List.of())) {
        TratamentoTributarioNcm t = tratamentos.get(ncm);
        porHipotese.computeIfAbsent(t.cClassTrib(), k -> new ArrayList<>()).add(t);
      }
      for (List<TratamentoTributarioNcm> grupo : porHipotese.values()) {
        TratamentoTributarioNcm exemplo = grupo.get(0);
        produtos.add(produto(grupo.size() == 1 ? exemplo.ncm() : null, d.descricao(),
            grupo.size(), exemplo, true, data));
      }
    }
    if (produtos.isEmpty()) {
      return vazia(termo);
    }
    TipoBusca tipo = escolhidos.get(0).ncm() != null ? TipoBusca.NOME : TipoBusca.ITEM_ANEXO;
    return new ConsultaTratamentoProduto(termo.strip(), tipo,
        produtos.subList(0, Math.min(LIMITE_PRODUTOS, produtos.size())),
        versao(tratamentos.values()));
  }

  private ConsultaTratamentoProduto porNcm(String codigo, LocalDate data) {
    Optional<NcmReferencia> referencia = ncmReferenciaRepository.findById(codigo);
    TratamentoTributarioNcm t = classificacaoService.tratamentoPorNcm(codigo, data);
    // Fora da referencia e de todo anexo: o codigo pode nem existir (o modelo pode ter chutado).
    boolean conhecido = referencia.isPresent() || !t.enquadramentos().isEmpty();
    return new ConsultaTratamentoProduto(codigo, TipoBusca.NCM,
        List.of(produto(codigo, referencia.map(NcmReferencia::getDescricao).orElse(null), 1, t,
            conhecido, data)),
        t.versaoTabela());
  }

  /** NCM de 8 dígitos no termo ("1201.90.00", "NCM 1201.90.00"); nulo se não for um código. */
  static String codigoNcm(String termo) {
    if (termo == null) {
      return null;
    }
    String limpo = PREFIXO_NCM.matcher(termo).replaceFirst("").replaceAll("[.\\s-]", "");
    return NCM.matcher(limpo).matches() ? limpo : null;
  }

  private static ConsultaTratamentoProduto vazia(String termo) {
    return new ConsultaTratamentoProduto(termo == null ? "" : termo.strip(), TipoBusca.NOME,
        List.of(), null);
  }

  private static String versao(Collection<TratamentoTributarioNcm> tratamentos) {
    return tratamentos.stream().map(TratamentoTributarioNcm::versaoTabela)
        .filter(Objects::nonNull).findFirst().orElse(null);
  }

  private TratamentoProduto produto(String ncm, String descricao, int quantidadeNcms,
      TratamentoTributarioNcm t, boolean ncmConhecido, LocalDate data) {
    Optional<EnquadramentoAnexo> escolhido = t.enquadramentos().stream()
        .filter(e -> e.cClassTrib().equals(t.cClassTrib()))
        .findFirst();
    Alternativa alternativa = alternativaDoLeite(t, data);
    String condicao = alternativa != null ? CONDICAO_LEITE_IN_NATURA
        : ClassificacaoTributariaService.LEITE_UHT.contains(t.ncm()) ? CONDICAO_LEITE_UHT
        : condicao(t, escolhido);
    return new TratamentoProduto(ncm, descricao, quantidadeNcms, t.cClassTrib(),
        t.percentualReducao(), t.origem(), escolhido.map(EnquadramentoAnexo::anexo).orElse(null),
        artigo(t.fundamento()), condicao, ncmConhecido, alternativa);
  }

  /**
   * Alíquota zero do leite de consumo direto (Anexo I, item 2), quando a regra por NCM o deixou
   * in natura ({@link ClassificacaoTributariaService#leiteSemDestinoDecidido}); nula nos demais.
   */
  private Alternativa alternativaDoLeite(TratamentoTributarioNcm t, LocalDate data) {
    if (t.origem() != Origem.IN_NATURA) {
      return null;
    }
    Set<String> enquadrados = new LinkedHashSet<>();
    t.enquadramentos().forEach(e -> enquadrados.add(e.cClassTrib()));
    if (!ClassificacaoTributariaService.leiteSemDestinoDecidido(t.ncm(), enquadrados)) {
      return null;
    }
    Optional<EnquadramentoAnexo> anexoI = t.enquadramentos().stream()
        .filter(e -> ClassificacaoTributariaService.CESTA_BASICA.equals(e.cClassTrib()))
        .findFirst();
    return classificacaoService.classificacao(ClassificacaoTributariaService.CESTA_BASICA, data)
        .map(c -> new Alternativa(c.getCodigo(), c.getPercentualReducaoIbs(),
            anexoI.map(EnquadramentoAnexo::anexo).orElse(null), artigo(c.getDescricao()),
            CONDICAO_LEITE_CONSUMO_DIRETO))
        .orElse(null);
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
    Set<String> partes = new LinkedHashSet<>();
    if (t.origem() == Origem.IN_NATURA) {
      partes.add(CONDICAO_IN_NATURA);
    }
    escolhido.ifPresent(e -> {
      if (e.condicao() != null && e.condicao().toUpperCase(Locale.ROOT).contains("DESTINAD")) {
        partes.add(e.condicao().strip().toLowerCase(Locale.forLanguageTag("pt-BR")));
      } else if (e.descricaoItem() != null) {
        Matcher m = DESTINO_NO_ITEM.matcher(e.descricaoItem());
        if (m.find()) {
          partes.add(m.group(1).strip());
        }
      }
      if (e.excecao() != null) {
        partes.add("exceto " + curta(e.excecao().strip(), EXCECAO_MAXIMA));
      }
    });
    if (t.fundamento() != null) {
      Matcher m = DESDE_QUE.matcher(t.fundamento());
      if (m.find()) {
        partes.add("desde que " + m.group(1).strip());
      }
    }
    return partes.isEmpty() ? null : String.join("; ", partes);
  }

  private static String curta(String texto, int maximo) {
    if (texto.length() <= maximo) {
      return texto;
    }
    int corte = texto.lastIndexOf(' ', maximo);
    return texto.substring(0, corte > 0 ? corte : maximo).replaceAll("[,;:]$", "") + "...";
  }

  /** Palavras de busca: sem acento, sem as vazias, no radical de singular. */
  static List<String> palavras(String termo) {
    Set<String> palavras = new LinkedHashSet<>();
    for (String p : tokens(termo)) {
      if (p.length() >= PALAVRA_MINIMA && !PALAVRAS_VAZIAS.contains(p)) {
        palavras.add(singular(p));
      }
    }
    return List.copyOf(palavras);
  }

  /**
   * Singular aproximado do português, o bastante para casar o começo das palavras: "feijoes",
   * "paes" e "graos" → "feijao", "pao", "grao"; "flores" → "flor"; "ovos" → "ovo".
   */
  static String singular(String palavra) {
    if (palavra.length() <= PALAVRA_MINIMA) {
      return palavra;
    }
    if (palavra.endsWith("oes") || palavra.endsWith("aes") || palavra.endsWith("aos")) {
      return palavra.substring(0, palavra.length() - 3) + "ao";
    }
    if (palavra.endsWith("ns")) {
      return palavra.substring(0, palavra.length() - 2) + "m";
    }
    if (palavra.endsWith("res") || palavra.endsWith("zes")) {
      return palavra.substring(0, palavra.length() - 2);
    }
    return palavra.endsWith("s") ? palavra.substring(0, palavra.length() - 1) : palavra;
  }

  private static List<String> tokens(String texto) {
    return List.of(semTrechoNegado(NormalizacaoBusca.normalizar(texto)).split("[^a-z0-9]+"));
  }

  /**
   * Tira do texto (já normalizado) o que ele nega (#369): "Soja, mesmo triturada, exceto para
   * semeadura" não descreve a semeadura, e "semente de soja" não pode casar com ela.
   *
   * <ul>
   *   <li>{@code exceto}, {@code excetuado(s)}, {@code excluído(s)}, {@code ressalvado(s)} e
   *       {@code salvo}: o trecho vai da palavra até a vírgula, o ponto e vírgula ou o ponto final
   *       (o ponto de "07.10" não conta), ou, se a palavra está entre parênteses, até o
   *       {@code )} que os fecha;</li>
   *   <li>{@code não} e {@code nem}: só a palavra seguinte ("café não torrado", "algodão não
   *       cardado nem penteado"), porque a negação costuma fazer parte do nome do produto ("queijo
   *       fresco não maturado e queijo do reino", "semente não certificada"). Se a seguinte é
   *       "destinados", a negação vai até o fim da oração ("não destinados à alimentação
   *       humana").</li>
   * </ul>
   */
  static String semTrechoNegado(String normalizado) {
    StringBuilder resultado = new StringBuilder(normalizado.length());
    int cursor = 0;
    Matcher m = NEGACAO.matcher(normalizado);
    while (m.find()) {
      if (m.start() < cursor) {
        continue;
      }
      int fim = fimDoTrechoNegado(normalizado, m);
      resultado.append(normalizado, cursor, m.start()).append(' ');
      cursor = fim;
    }
    return resultado.append(normalizado, cursor, normalizado.length()).toString();
  }

  private static int fimDoTrechoNegado(String texto, Matcher negacao) {
    String palavra = negacao.group();
    if (palavra.equals("nao") || palavra.equals("nem")) {
      Matcher seguinte = PROXIMA_PALAVRA.matcher(texto);
      if (!seguinte.find(negacao.end())) {
        return texto.length();
      }
      return seguinte.group().startsWith("destinad")
          ? fimDaOracao(texto, negacao.end()) : seguinte.end();
    }
    return fimDaOracao(texto, negacao.end());
  }

  /** Depois do último {@code )} que fecha o parêntese aberto antes do início, ou da pontuação. */
  private static int fimDaOracao(String texto, int inicio) {
    boolean dentroDeParenteses = abertos(texto, inicio) > 0;
    int aninhados = 0;
    for (int i = inicio; i < texto.length(); i++) {
      char c = texto.charAt(i);
      if (c == '(') {
        aninhados++;
      } else if (c == ')') {
        if (aninhados == 0) {
          return i;
        }
        aninhados--;
      } else if (aninhados == 0 && !dentroDeParenteses && fimDeOracao(texto, i)) {
        return i;
      }
    }
    return texto.length();
  }

  private static boolean fimDeOracao(String texto, int i) {
    char c = texto.charAt(i);
    return c == ',' || c == ';'
        || (c == '.' && (i + 1 == texto.length() || texto.charAt(i + 1) == ' '));
  }

  private static int abertos(String texto, int ate) {
    int abertos = 0;
    for (int i = 0; i < ate; i++) {
      if (texto.charAt(i) == '(') {
        abertos++;
      } else if (texto.charAt(i) == ')' && abertos > 0) {
        abertos--;
      }
    }
    return abertos;
  }

  /**
   * Os candidatos que casam as palavras mais raras: a pontuação de cada um é a soma, para cada
   * palavra do termo que começa uma palavra da descrição, do inverso da quantidade de descrições
   * em que ela aparece. Ficam só os de pontuação máxima; no empate entre a referência de NCM e um
   * item de anexo, só a referência.
   */
  static List<Descrito> melhores(Catalogo catalogo, List<String> palavras) {
    Map<String, Integer> frequencia = new HashMap<>();
    for (String p : palavras) {
      int vezes = 0;
      for (Descrito d : catalogo.todos()) {
        if (d.casa(p)) {
          vezes++;
        }
      }
      frequencia.put(p, vezes);
    }
    double maximo = 0;
    Map<Descrito, Double> pontos = new LinkedHashMap<>();
    for (Descrito d : catalogo.todos()) {
      double soma = 0;
      for (String p : palavras) {
        if (d.casa(p)) {
          soma += 1.0 / frequencia.get(p);
        }
      }
      if (soma > 0) {
        pontos.put(d, soma);
        maximo = Math.max(maximo, soma);
      }
    }
    List<Descrito> escolhidos = new ArrayList<>();
    for (Map.Entry<Descrito, Double> e : pontos.entrySet()) {
      if (e.getValue() >= maximo - EMPATE) {
        escolhidos.add(e.getKey());
      }
    }
    // Empate entre a referencia (NCM certo) e um item de anexo: fica a referencia.
    if (escolhidos.stream().anyMatch(d -> d.ncm() != null)) {
      escolhidos.removeIf(d -> d.ncm() == null);
    }
    // O catalogo ja esta na ordem: referencia (por codigo) e depois itens (por id).
    return escolhidos.subList(0, Math.min(LIMITE_PRODUTOS, escolhidos.size()));
  }

  private Catalogo catalogo() {
    Catalogo atual = catalogo;
    if (atual == null) {
      List<Descrito> todos = new ArrayList<>();
      ncmReferenciaRepository.findAll().stream()
          .sorted((a, b) -> a.getCodigo().compareTo(b.getCodigo()))
          .forEach(n -> todos.add(Descrito.de(n.getCodigo(), null, n.getDescricao())));
      itemRepository.findAll().stream()
          .sorted((a, b) -> a.getId().compareTo(b.getId()))
          .forEach(i -> todos.add(Descrito.de(null, i.getId(), i.getDescricao())));
      atual = new Catalogo(List.copyOf(todos));
      catalogo = atual;
    }
    return atual;
  }

  /** Descrições pesquisáveis: a referência de NCM e depois os itens dos anexos. */
  record Catalogo(List<Descrito> todos) {}

  /**
   * Uma descrição pesquisável.
   *
   * @param ncm       código, se é da referência de NCM
   * @param itemId    item do anexo, se é de um item
   * @param descricao texto oficial
   * @param palavras  palavras da descrição no radical de singular
   */
  record Descrito(String ncm, Integer itemId, String descricao, List<String> palavras) {

    static Descrito de(String ncm, Integer itemId, String descricao) {
      return new Descrito(ncm, itemId, descricao,
          tokens(descricao).stream().map(TratamentoProdutoService::singular).toList());
    }

    boolean casa(String palavra) {
      return palavras.stream().anyMatch(d -> d.startsWith(palavra));
    }
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
   *                          integral ou se a descrição oficial não o nomeia
   * @param condicao          condição que o dado não decide; nula quando não há
   * @param ncmConhecido      {@code false} para um NCM pedido que não está na referência nem em
   *                          anexo nenhum: a tabela não diz nada dele, e a resposta pede para
   *                          conferir o código
   * @param alternativa       tratamento que vale só com o cClassTrib informado; nulo quando não há
   */
  public record TratamentoProduto(String ncm, String descricao, int quantidadeNcms,
      String cClassTrib, BigDecimal percentualReducao, Origem origem, Integer anexo,
      String artigo, String condicao, boolean ncmConhecido, Alternativa alternativa) {

    /** Sem alternativa (todo produto, exceto o leite do Anexo I, item 2). */
    public TratamentoProduto(String ncm, String descricao, int quantidadeNcms,
        String cClassTrib, BigDecimal percentualReducao, Origem origem, Integer anexo,
        String artigo, String condicao, boolean ncmConhecido) {
      this(ncm, descricao, quantidadeNcms, cClassTrib, percentualReducao, origem, anexo, artigo,
          condicao, ncmConhecido, null);
    }
  }

  /**
   * Outro tratamento do mesmo produto, que vale só com o {@code cClassTrib} informado no item
   * (hoje, a alíquota zero do leite de consumo direto, #284).
   *
   * @param cClassTrib        classificação que o item teria de informar
   * @param percentualReducao redução oficial desse código
   * @param anexo             número do anexo da LC 214/2025
   * @param artigo            artigo da LC 214/2025 do código
   * @param condicao          quando vale
   */
  public record Alternativa(String cClassTrib, BigDecimal percentualReducao, Integer anexo,
      String artigo, String condicao) {}
}
