package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ferramentas.TemplateTratamentoProduto;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.Produto;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.TratamentoConsultado;
import br.com.agrocontabil.model.TipoProdutor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Texto explicado do atalho "O que muda para mim com o IBS e o CBS?" (issue #347): o que são os
 * tributos, o calendário da transição, o que muda na venda, nas compras e no pagamento, quem é
 * contribuinte e o próximo passo — em linguagem do produtor, com o dispositivo de cada afirmação.
 *
 * <p>Correto por construção, como o texto curado da #343: cada número está escrito no dispositivo
 * citado ({@code TextoReformaTributariaTest} confere contra o texto da base), e o texto é do
 * backend, não do modelo. A personalização usa só o que o sistema sabe do produtor — o tipo, se
 * um escritório o atende e o tratamento dos produtos que ele mais vende, pela tabela oficial — e
 * nunca afirma a situação dele no IBS e na CBS: optante ou não contribuinte é com o contador
 * (premissas da #280; a modelagem é da #281).
 *
 * <p>Formato da tela: texto puro com quebra de linha ({@code whitespace-pre-line}), títulos em
 * maiúsculas e itens com "- ". Sem acento, como os demais textos da Ceres. Texto a revisar pela
 * equipe a cada mudança.
 */
public final class TextoReformaTributaria {

  /** Título da resposta. */
  static final String TITULO = "O que muda para voce com o IBS e a CBS";

  static final String O_QUE_SAO = String.join("\n",
      "O QUE SAO",
      "- A CBS e a contribuicao da Uniao (federal) e o IBS e o imposto de competencia"
          + " compartilhada entre estados, municipios e Distrito Federal (LC 214/2025, art. 1º,"
          + " incisos I e II).",
      "- Eles substituem tributos de hoje: a partir de 2027 a CBS e cobrada e o PIS e a Cofins"
          + " sao extintos (EC 132/2023, art. 126 do ADCT); o ICMS e o ISS sao extintos a partir"
          + " de 2033 (art. 129 do ADCT).");

  static final String CALENDARIO = String.join("\n",
      "O CALENDARIO",
      "- 2026 e um ano de teste: a CBS e cobrada a 0,9% e o IBS a 0,1%, so na parcela estadual"
          + " (LC 214/2025, arts. 346 e 343; EC 132/2023, art. 125 do ADCT). Quem cumpre as"
          + " obrigacoes acessorias previstas na legislacao fica dispensado de recolher esses"
          + " valores em 2026 (LC 214/2025, art. 348, § 1º).",
      "- 2027 e 2028: a CBS passa a ser cobrada com a aliquota de referencia reduzida em 0,1"
          + " ponto percentual (LC 214/2025, art. 347), e o IBS segue em teste, a 0,05% estadual"
          + " e 0,05% municipal (art. 344).",
      "- 2029 a 2032: o ICMS e o ISS diminuem aos poucos, para 9/10 das aliquotas em 2029, 8/10"
          + " em 2030, 7/10 em 2031 e 6/10 em 2032 (EC 132/2023, art. 128 do ADCT), e o IBS"
          + " cresce para repor essa receita (art. 130 do ADCT).",
      "- 2033: o ICMS e o ISS deixam de existir (art. 129 do ADCT).");

  static final String VENDA_REGRAS = String.join("\n",
      "NA SUA VENDA",
      "- Produto agropecuario in natura tem reducao de 60% nas aliquotas do IBS e da CBS"
          + " (LC 214/2025, art. 137).",
      "- Alguns alimentos tem aliquota zero: os da Cesta Basica Nacional, no Anexo I (art. 125),"
          + " e os horticolas, frutas e ovos do Anexo XV (art. 148).");

  static final String VENDA_PRODUTOS =
      "- Pela tabela oficial, os produtos que voce mais vendeu este ano, nas suas notas"
          + " autorizadas:";

  static final String VENDA_ENQUADRAMENTO =
      "- O enquadramento final de cada venda e com o seu contador.";

  static final String VENDA_SEM_PRODUTOS =
      "- Para saber o tratamento de um produto seu, pergunte pelo nome dele ou pelo NCM da nota.";

  static final String VENDA_NOTA =
      "- Quem e contribuinte do IBS e da CBS emite documento fiscal eletronico nas suas"
          + " operacoes, e o IBS e a CBS informados nele valem como confissao do valor devido"
          + " (LC 214/2025, art. 60, § 1º).";

  static final String COMPRAS = String.join("\n",
      "NAS SUAS COMPRAS",
      "- Os insumos agropecuarios e aquicolas do Anexo IX tem reducao de 60% nas aliquotas do"
          + " IBS e da CBS (LC 214/2025, art. 138), se estiverem registrados como insumos no"
          + " Ministerio da Agricultura e Pecuaria, quando a lei exige (art. 138, § 1º).",
      "- Na compra desses insumos de fornecedor do regime regular por quem tambem esta no regime"
          + " regular, o recolhimento do IBS e da CBS fica diferido (art. 138, § 2º, inciso I,"
          + " alinea a). Quando o diferimento se encerra e quem recolhe (art. 138, §§ 5º a 7º), e"
          + " o efeito disso no seu credito, sao para conferir com o contador.",
      "- No regime regular, o IBS e a CBS pagos nas demais compras, fora desse diferimento, podem"
          + " virar credito, exceto o que for de uso ou consumo pessoal (art. 47). O que conta como"
          + " credito na sua atividade e com o contador.");

  static final String PAGAMENTO = String.join("\n",
      "NO PAGAMENTO",
      "- A lei preve o split payment: nos pagamentos eletronicos, a instituicao de pagamento"
          + " separa e recolhe o IBS e a CBS no momento da liquidacao (LC 214/2025, arts. 31 e"
          + " 32).");

  static final String PAGAMENTO_PESSOA_FISICA = String.join("\n",
      "- O Funrural segue a regra propria: na venda a empresa ou cooperativa, o comprador retem a"
          + " contribuicao do produtor pessoa fisica (Lei 8.212/1991, art. 30, IV).",
      "- O ICMS fica com o contador; o AgroConIA trata do IBS e da CBS.");

  static final String PAGAMENTO_OUTROS =
      "- O Funrural e o ICMS ficam com o contador; o AgroConIA trata do IBS e da CBS.";

  static final String QUEM_PAGA = String.join("\n",
      "QUEM PAGA",
      "- Pela lei, o produtor rural com receita inferior a R$ 3.600.000,00 no ano-calendario nao"
          + " e contribuinte do IBS e da CBS (LC 214/2025, art. 164), mas pode optar pelo regime"
          + " regular a qualquer tempo (art. 165). Quem esta no regime regular e compra de produtor"
          + " nao contribuinte pode apropriar credito presumido (art. 168).",
      "- O AgroConIA calcula o IBS e a CBS das suas notas pelo regime regular. Confirme com o"
          + " contador se esse e o seu caso.");

  static final String PROXIMO_COM_ESCRITORIO = String.join("\n",
      "PROXIMO PASSO",
      "- Confira com o seu escritorio de contabilidade o enquadramento dos seus produtos e a sua"
          + " situacao no IBS e na CBS.");

  static final String PROXIMO_SEM_ESCRITORIO = String.join("\n",
      "PROXIMO PASSO",
      "- Procure um escritorio de contabilidade para conferir o enquadramento dos seus produtos"
          + " e a sua situacao no IBS e na CBS. Pelo AgroConIA, o escritorio manda um convite para"
          + " o seu e-mail e, quando voce aceita, passa a acompanhar as suas notas.");

  static final String PROXIMO_GENERICO = String.join("\n",
      "PROXIMO PASSO",
      "- Confira com o seu contador o enquadramento dos seus produtos e a sua situacao no IBS e"
          + " na CBS.");

  /** Dispositivos do texto antes da lista dos produtos ({@code norma|dispositivo}, como na base). */
  static final List<String> ANTES_DOS_PRODUTOS = List.of(
      "LC 214/2025|art. 1º, inciso I",
      "LC 214/2025|art. 1º, inciso II",
      "EC 132/2023|art. 2º (redação do art. 126, inciso I, alínea a)",
      "EC 132/2023|art. 2º (redação do art. 126, inciso II)",
      "EC 132/2023|art. 2º (redação do art. 129)",
      "LC 214/2025|art. 346",
      "LC 214/2025|art. 343",
      "EC 132/2023|art. 2º (redação do art. 125)",
      "LC 214/2025|art. 348, § 1º",
      "LC 214/2025|art. 347",
      "LC 214/2025|art. 344",
      "EC 132/2023|art. 2º (redação do art. 128, inciso I)",
      "EC 132/2023|art. 2º (redação do art. 128, inciso II)",
      "EC 132/2023|art. 2º (redação do art. 128, inciso III)",
      "EC 132/2023|art. 2º (redação do art. 128, inciso IV)",
      "EC 132/2023|art. 2º (redação do art. 130, inciso II)",
      "EC 132/2023|art. 2º (redação do art. 130, inciso III)",
      "LC 214/2025|art. 137",
      "LC 214/2025|art. 125",
      "LC 214/2025|art. 148");

  /** Da nota fiscal ao split payment, depois dos produtos e antes do Funrural. */
  static final List<String> DA_NOTA_AO_PAGAMENTO = List.of(
      "LC 214/2025|art. 60, § 1º",
      "LC 214/2025|art. 138",
      "LC 214/2025|art. 138, § 1º",
      "LC 214/2025|art. 138, § 2º, inciso I, alínea a",
      "LC 214/2025|art. 138, § 5º",
      "LC 214/2025|art. 138, § 6º",
      "LC 214/2025|art. 138, § 7º",
      "LC 214/2025|art. 47",
      "LC 214/2025|art. 31",
      "LC 214/2025|art. 32");

  /** Quem paga, no fim do texto. */
  static final List<String> QUEM_PAGA_DISPOSITIVOS = List.of(
      "LC 214/2025|art. 164",
      "LC 214/2025|art. 165",
      "LC 214/2025|art. 168");

  /** Todos os dispositivos das seções fixas, na ordem do texto. */
  static final List<String> DISPOSITIVOS_FIXOS = Stream.of(
      ANTES_DOS_PRODUTOS, DA_NOTA_AO_PAGAMENTO, QUEM_PAGA_DISPOSITIVOS)
      .flatMap(List::stream).toList();

  /** Funrural retido, só no texto do produtor pessoa física. */
  static final String DISPOSITIVO_FUNRURAL = "Lei 8.212/1991|art. 30, inciso IV";

  private TextoReformaTributaria() {}

  /**
   * O que o texto sabe do produtor. Tudo opcional: a personalização que falhou sai do texto, e o
   * resto segue igual.
   *
   * @param tipo                  tipo cadastrado; nulo se desconhecido (sem o Funrural retido)
   * @param atendidoPorEscritorio vínculo ativo com escritório; nulo se desconhecido
   * @param produtos              tratamento dos produtos mais vendidos; vazio sem vendas ou sem
   *                              a consulta
   */
  public record Perfil(TipoProdutor tipo, Boolean atendidoPorEscritorio, List<Produto> produtos) {

    /** Perfil sem nada do produtor: o texto genérico. */
    public static final Perfil DESCONHECIDO = new Perfil(null, null, List.of());

    /** Cópia defensiva. */
    public Perfil {
      produtos = List.copyOf(produtos);
    }
  }

  /**
   * Texto pronto e os dispositivos que ele cita, sem repetir.
   *
   * @param texto        texto para o produtor
   * @param dispositivos {@code norma|dispositivo}, na ordem do texto
   */
  public record Texto(String texto, List<String> dispositivos) {

    /** Cópia defensiva. */
    public Texto {
      dispositivos = List.copyOf(dispositivos);
    }
  }

  /**
   * Monta o texto para o perfil.
   *
   * @param perfil o que se sabe do produtor
   * @return texto e dispositivos
   */
  public static Texto montar(Perfil perfil) {
    // Na ordem do texto: as citacoes saem na mesma sequencia das secoes.
    Set<String> dispositivos = new LinkedHashSet<>(ANTES_DOS_PRODUTOS);
    dispositivos.addAll(TemplateTratamentoProduto.dispositivos(
        new TratamentoConsultado("", perfil.produtos(), null)));
    dispositivos.addAll(DA_NOTA_AO_PAGAMENTO);
    List<String> secoes = new ArrayList<>();
    secoes.add(TITULO);
    secoes.add(O_QUE_SAO);
    secoes.add(CALENDARIO);
    secoes.add(venda(perfil.produtos()));
    secoes.add(COMPRAS);
    boolean pessoaFisica = perfil.tipo() == TipoProdutor.PESSOA_FISICA;
    secoes.add(PAGAMENTO + "\n" + (pessoaFisica ? PAGAMENTO_PESSOA_FISICA : PAGAMENTO_OUTROS));
    if (pessoaFisica) {
      dispositivos.add(DISPOSITIVO_FUNRURAL);
    }
    dispositivos.addAll(QUEM_PAGA_DISPOSITIVOS);
    secoes.add(QUEM_PAGA);
    secoes.add(proximoPasso(perfil.atendidoPorEscritorio()));
    return new Texto(String.join("\n\n", secoes), List.copyOf(dispositivos));
  }

  private static String venda(List<Produto> produtos) {
    List<String> linhas = new ArrayList<>();
    linhas.add(VENDA_REGRAS);
    if (produtos.isEmpty()) {
      linhas.add(VENDA_SEM_PRODUTOS);
    } else {
      linhas.add(VENDA_PRODUTOS);
      // "- " e nao recuo: a tela usa whitespace-pre-line, que descarta espaco no comeco da linha.
      for (Produto p : produtos) {
        linhas.add("- " + TemplateTratamentoProduto.frase(p));
      }
      linhas.add(VENDA_ENQUADRAMENTO);
    }
    linhas.add(VENDA_NOTA);
    return String.join("\n", linhas);
  }

  private static String proximoPasso(Boolean atendido) {
    if (atendido == null) {
      return PROXIMO_GENERICO;
    }
    return atendido ? PROXIMO_COM_ESCRITORIO : PROXIMO_SEM_ESCRITORIO;
  }
}
