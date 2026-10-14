package br.com.agrocontabil.assistente.ferramentas;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Alerta;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Alertas;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Apuracao;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.CompraPorUnidade;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.ComprasInsumo;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Creditos;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Guia;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Nota;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Notas;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Retencoes;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Vencimentos;
import br.com.agrocontabil.assistente.porta.DadosProdutorPorta;
import br.com.agrocontabil.assistente.porta.DadosProdutorPorta.Pagina;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Funções de consulta aos dados do produtor que a Ceres pode chamar (issue #142, UC003).
 *
 * <p><strong>Nenhum número é gerado pelo modelo:</strong> ele só escolhe a função, preenche os
 * parâmetros e redige a resposta com o resultado. Cada função é uma consulta <em>fixa e
 * parametrizada</em> feita pela {@link DadosProdutorPorta} — proibido text-to-SQL, e nenhuma regra
 * fiscal é reescrita aqui (o adaptador da porta usa os services de apuração, crédito e consulta).
 *
 * <p><strong>Isolamento por construção (issue #143, RN007):</strong>
 * <ul>
 *   <li>nenhuma função tem parâmetro de produtor — o modelo não tem como pedir dados de outro,
 *       nem sob prompt injection;</li>
 *   <li>o produtor vem do {@link ContextoAssistente} no {@link ToolContext}, que o modelo não vê,
 *       e a ausência dele aborta a pergunta (falha fechado);</li>
 *   <li>nada aqui lê o {@code SecurityContext}: com streaming a função roda em outra thread.</li>
 * </ul>
 *
 * <p>A {@code description} de cada {@code @Tool} faz parte do prompt: diz ao modelo quando usar a
 * função. Parâmetro inválido lança {@link ParametroFerramentaInvalidoException}, cuja mensagem
 * volta ao modelo para ele corrigir a chamada.
 */
@Component
public class FerramentasDadosProdutor {

  /** Janela máxima de uma consulta por período (as demais telas usam no máximo 36 meses). */
  static final int MAXIMO_DIAS_PERIODO = 731;

  /** Janela máxima de {@code proximosVencimentos}. */
  static final int MAXIMO_DIAS_VENCIMENTO = 90;

  /**
   * Zero por grafia nao e o mesmo que "nao comprou": a busca e por trecho exato da descricao. Sem
   * digitos de proposito, para nao virar numero "com fonte" no ValidadorNumeros.
   */
  static final String NADA_POR_DESCRICAO =
      "Nenhuma compra encontrada com esse termo no periodo. A busca procura o trecho exato na "
          + "descricao dos itens (acentos e grafia contam): diga isso ao usuario e sugira outro "
          + "termo ou o codigo NCM, em vez de afirmar que ele nao comprou.";

  /** Mesmo cuidado para a busca por NCM. */
  static final String NADA_POR_NCM =
      "Nenhuma compra encontrada com esse codigo NCM no periodo. Diga isso ao usuario e sugira "
          + "conferir o codigo ou buscar pelo nome do insumo.";

  /** Abrangencia do valorTotal de listarNotas sem filtro de situacao (sem digitos, ver acima). */
  static final String TOTAL_SO_AUTORIZADAS =
      "Soma apenas das notas AUTORIZADAS do periodo e tipo pedidos, inclusive as que nao aparecem "
          + "na lista. Rascunhos, rejeitadas e canceladas ficam fora.";

  /** Abrangencia do valorTotal de listarNotas com filtro de situacao. */
  static final String TOTAL_DO_FILTRO =
      "Soma de todas as notas do filtro pedido, inclusive as que nao aparecem na lista.";

  private static final Pattern MES =Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");
  private static final Pattern NCM = Pattern.compile("\\d{2,8}");
  private static final int TERMO_MINIMO = 3;
  private static final int TERMO_MAXIMO = 60;
  private static final int LIMITE_PADRAO = 5;
  /** 1,2% + 0,1% (Lei 8.212/1991, art. 25) + 0,2% SENAR (Lei 9.528/1997, art. 6º). */
  static final String ALIQUOTA_FUNRURAL = "1,5% (1,2% + 0,1% + 0,2% SENAR)";
  static final String FUNRURAL_NAO_SE_APLICA = "nao se aplica";
  private static final String AVISO_SPLIT_PAYMENT = "O split payment separa o IBS e a CBS da nota"
      + " no pagamento da venda (LC 214/2025, arts. 31 e 32).";
  /** O que o cálculo não sabe e o produtor precisa ouvir (RN009, issue #145). */
  static final List<String> AVISOS_PESSOA_FISICA = List.of(
      "Se o produtor optou por contribuir sobre a folha de salarios (Lei 8.212/1991, art. 25,"
          + " § 13), o comprador nao retem o Funrural.",
      "Venda de semente para plantio ou de animal para reproducao nao entra na base do Funrural"
          + " (art. 25, § 12); o calculo considera todas as vendas.",
      AVISO_SPLIT_PAYMENT);
  static final List<String> AVISOS_OUTROS = List.of(
      "Produtor pessoa juridica ou sem tipo cadastrado: nao ha retencao do Funrural pelo"
          + " comprador.",
      AVISO_SPLIT_PAYMENT);
  private static final int DIAS_VENCIMENTO_PADRAO = 30;
  private static final int DIAS_LISTAGEM_PADRAO = 90;

  private final DadosProdutorPorta dados;

  /**
   * Constrói as funções sobre a porta de dados do produtor (issue #141: o módulo não enxerga
   * services nem repositories).
   */
  public FerramentasDadosProdutor(DadosProdutorPorta dados) {
    this.dados = dados;
  }

  /** Soma das compras de um insumo no período. */
  @Tool(name = "totalComprasPorInsumo", resultConverter = ResultadoSanitizadoConverter.class,
      description = """
      Soma as COMPRAS do produtor (notas de entrada autorizadas, em que ele e o destinatario) de \
      um insumo num periodo: valor total, numero de notas e quantidade por unidade de medida. Use \
      para perguntas como "quanto comprei de fertilizante no semestre?". O insumo pode ser um \
      nome (ex.: fertilizante, glifosato, semente de soja) ou um codigo NCM completo ou prefixo \
      (ex.: 3808 para defensivos agricolas).""")
  public ComprasInsumo totalComprasPorInsumo(
      @ToolParam(description = "Nome do insumo (3 a 60 letras) ou codigo NCM (2 a 8 digitos)")
      String descricaoOuNcm,
      @ToolParam(description = "Primeiro dia do periodo, formato AAAA-MM-DD") String dataInicio,
      @ToolParam(description = "Ultimo dia do periodo, formato AAAA-MM-DD") String dataFim,
      ToolContext toolContext) {
    ContextoAssistente contexto = ContextoAssistente.de(toolContext);
    LocalDate inicio = data(dataInicio, "dataInicio");
    LocalDate fim = data(dataFim, "dataFim");
    exigirPeriodo(inicio, fim);

    String ncm = descricaoOuNcm == null ? "" : descricaoOuNcm.replaceAll("[.\\s-]", "");
    boolean porNcm = NCM.matcher(ncm).matches();
    String termo = porNcm ? ncm : termoDescricao(descricaoOuNcm);
    DadosProdutorPorta.ComprasInsumo resultado = porNcm
        ? dados.comprasPorNcm(contexto.produtorId(), termo, inicio, fim)
        : dados.comprasPorDescricao(contexto.produtorId(), termo, inicio, fim);

    contexto.registrarFonte("totalComprasPorInsumo", parametros(
        "descricaoOuNcm", termo, "dataInicio", inicio.toString(), "dataFim", fim.toString()),
        resultado.quantidadeNotas() == 0);
    return new ComprasInsumo(
        termo,
        porNcm ? "NCM" : "DESCRICAO",
        FormatoBr.data(inicio),
        FormatoBr.data(fim),
        FormatoBr.moeda(resultado.valorTotal()),
        resultado.quantidadeNotas(),
        resultado.porUnidade().stream()
            .map(u -> new CompraPorUnidade(
                u.unidade(), FormatoBr.quantidade(u.quantidade()), FormatoBr.moeda(u.valorTotal())))
            .toList(),
        resultado.quantidadeNotas() == 0 ? (porNcm ? NADA_POR_NCM : NADA_POR_DESCRICAO) : null);
  }

  /** Apuração mensal de IBS/CBS. */
  @Tool(name = "consultarApuracao", resultConverter = ResultadoSanitizadoConverter.class,
      description = """
      Consulta a apuracao mensal de IBS e CBS do produtor: debito das vendas, credito das compras \
      e saldo a recolher de cada tributo. Use para perguntas como "quanto de IBS eu devo em \
      julho?" ou "qual o saldo de CBS do mes passado?". Mes em aberto traz valores parciais.""")
  public Apuracao consultarApuracao(
      @ToolParam(description = "Mes da apuracao, formato AAAA-MM") String mes,
      ToolContext toolContext) {
    ContextoAssistente contexto = ContextoAssistente.de(toolContext);
    if (mes == null || !MES.matcher(mes.trim()).matches()) {
      throw new ParametroFerramentaInvalidoException(
          "mes deve estar no formato AAAA-MM com mes de 01 a 12 (ex.: 2026-07).");
    }
    String periodo = mes.trim();
    DadosProdutorPorta.Apuracao apuracao = dados.apuracao(contexto.produtorId(), periodo);

    contexto.registrarFonte(
        "consultarApuracao", parametros("mes", periodo), apuracao.totalNotas() == 0);
    return new Apuracao(
        apuracao.periodo(),
        apuracao.status(),
        FormatoBr.moeda(apuracao.debitoIbs()),
        FormatoBr.moeda(apuracao.debitoCbs()),
        FormatoBr.moeda(apuracao.creditoIbs()),
        FormatoBr.moeda(apuracao.creditoCbs()),
        FormatoBr.moeda(apuracao.saldoIbs()),
        FormatoBr.moeda(apuracao.saldoCbs()),
        apuracao.totalNotas());
  }

  /** Créditos de IBS/CBS das compras no período. */
  @Tool(name = "consultarCreditos", resultConverter = ResultadoSanitizadoConverter.class,
      description = """
      Soma os creditos de IBS e de CBS das compras do produtor (notas de entrada autorizadas) num \
      periodo. Use para perguntas como "quanto tenho de credito de IBS este ano?".""")
  public Creditos consultarCreditos(
      @ToolParam(description = "Primeiro dia do periodo, formato AAAA-MM-DD") String dataInicio,
      @ToolParam(description = "Ultimo dia do periodo, formato AAAA-MM-DD") String dataFim,
      ToolContext toolContext) {
    ContextoAssistente contexto = ContextoAssistente.de(toolContext);
    LocalDate inicio = data(dataInicio, "dataInicio");
    LocalDate fim = data(dataFim, "dataFim");
    exigirPeriodo(inicio, fim);

    DadosProdutorPorta.Creditos creditos = dados.creditos(contexto.produtorId(), inicio, fim);

    contexto.registrarFonte("consultarCreditos",
        parametros("dataInicio", inicio.toString(), "dataFim", fim.toString()),
        creditos.quantidadeNotas() == 0);
    return new Creditos(
        FormatoBr.data(inicio),
        FormatoBr.data(fim),
        FormatoBr.moeda(creditos.creditoIbs()),
        FormatoBr.moeda(creditos.creditoCbs()),
        FormatoBr.moeda(creditos.creditoIbs().add(creditos.creditoCbs())),
        creditos.quantidadeNotas());
  }

  /** Retenções sobre as vendas do período: Funrural e split payment (RN009, issue #145). */
  @Tool(name = "consultarRetencoes", resultConverter = ResultadoSanitizadoConverter.class,
      description = """
      Mostra as retencoes sobre as VENDAS autorizadas do produtor num periodo: o Funrural (1,5% \
      da receita, retido pelo comprador quando ele e empresa ou cooperativa; nas vendas a pessoa \
      fisica, no varejo ou ao exterior nao ha retencao e o proprio produtor recolhe) e o IBS e a \
      CBS da nota, que o split payment separa no pagamento. Use para perguntas como "quanto foi \
      retido de Funrural nas minhas vendas?", "tenho retencao nas vendas de agosto?" ou "quanto \
      do pagamento fica retido?". Fale sempre das duas retencoes.""")
  public Retencoes consultarRetencoes(
      @ToolParam(description = "Primeiro dia do periodo, formato AAAA-MM-DD") String dataInicio,
      @ToolParam(description = "Ultimo dia do periodo, formato AAAA-MM-DD") String dataFim,
      ToolContext toolContext) {
    ContextoAssistente contexto = ContextoAssistente.de(toolContext);
    LocalDate inicio = data(dataInicio, "dataInicio");
    LocalDate fim = data(dataFim, "dataFim");
    exigirPeriodo(inicio, fim);

    DadosProdutorPorta.Retencoes r = dados.retencoes(contexto.produtorId(), inicio, fim);

    contexto.registrarFonte("consultarRetencoes",
        parametros("dataInicio", inicio.toString(), "dataFim", fim.toString()),
        r.notasVendidas() == 0);
    String aliquota = r.produtorPessoaFisica() ? ALIQUOTA_FUNRURAL : FUNRURAL_NAO_SE_APLICA;
    return new Retencoes(
        FormatoBr.data(inicio),
        FormatoBr.data(fim),
        r.notasVendidas(),
        funrural(r.retidoPeloComprador(), aliquota),
        funrural(r.semRetencao(), aliquota),
        FormatoBr.moeda(r.ibsSplitPayment()),
        FormatoBr.moeda(r.cbsSplitPayment()),
        FormatoBr.moeda(r.ibsSplitPayment().add(r.cbsSplitPayment())),
        r.produtorPessoaFisica() ? AVISOS_PESSOA_FISICA : AVISOS_OUTROS);
  }

  private static RespostasFerramentas.Funrural funrural(
      DadosProdutorPorta.Funrural f, String aliquota) {
    return new RespostasFerramentas.Funrural(f.notas(), FormatoBr.moeda(f.base()), aliquota,
        FormatoBr.moeda(f.previdencia()), FormatoBr.moeda(f.rat()), FormatoBr.moeda(f.senar()),
        FormatoBr.moeda(f.total()));
  }

  /** As notas mais recentes, com filtro opcional. */
  @Tool(name = "listarNotas", resultConverter = ResultadoSanitizadoConverter.class,
      description = """
      Lista as notas fiscais mais recentes do produtor (no maximo 10) com numero, data, tipo, \
      situacao e valores, informa quantas existem no filtro e traz em valorTotal a soma ja \
      calculada pelo sistema (abrangenciaTotal e quantidadeNoTotal dizem quais e quantas notas \
      entram nela). Sem datas, o periodo e dos ultimos 90 dias: informe sempre o periodo \
      (dataInicio a dataFim) junto com o total. Use para perguntas \
      como "quais foram minhas ultimas vendas?", "tenho nota rejeitada?" ou "quanto somam minhas \
      vendas do mes?". Para totais, repita o valorTotal: nunca some os valores da lista, que e \
      limitada.""")
  public Notas listarNotas(
      @ToolParam(required = false,
          description = "SAIDA (vendas, notas emitidas pelo produtor) ou ENTRADA (compras "
              + "recebidas). Omita para os dois tipos.")
      String tipo,
      @ToolParam(required = false,
          description = "Situacao da nota: RASCUNHO, AUTORIZADA, REJEITADA, CANCELADA, "
              + "PENDENTE_SEFAZ, COM_INCONSISTENCIAS, XML_GERADO. Omita para todas.")
      String situacao,
      @ToolParam(required = false,
          description = "Primeiro dia, formato AAAA-MM-DD. Omitido: 90 dias atras.")
      String dataInicio,
      @ToolParam(required = false, description = "Ultimo dia, formato AAAA-MM-DD. Omitido: hoje.")
      String dataFim,
      @ToolParam(required = false, description = "Quantas notas listar, de 1 a 10. Omitido: 5.")
      Integer limite,
      ToolContext toolContext) {
    ContextoAssistente contexto = ContextoAssistente.de(toolContext);
    Set<OrigemNotaFiscal> origens = origens(tipo);
    Set<StatusNotaFiscal> status = status(situacao);
    LocalDate fim = vazio(dataFim) ? contexto.hoje() : data(dataFim, "dataFim");
    LocalDate inicio = vazio(dataInicio)
        ? fim.minusDays(DIAS_LISTAGEM_PADRAO)
        : data(dataInicio, "dataInicio");
    exigirPeriodo(inicio, fim);
    int quantidade = limite == null ? LIMITE_PADRAO : limite;
    if (quantidade < 1 || quantidade > DadosProdutorPorta.LIMITE_MAXIMO) {
      throw new ParametroFerramentaInvalidoException("limite deve estar entre 1 e 10.");
    }

    Pagina<DadosProdutorPorta.Nota> pagina =
        dados.notas(contexto.produtorId(), origens, status, inicio, fim, quantidade);

    Map<String, String> usados = parametros(
        "dataInicio", inicio.toString(), "dataFim", fim.toString(),
        "limite", String.valueOf(quantidade));
    if (!vazio(tipo)) {
      usados.put("tipo", tipo.trim().toUpperCase(Locale.ROOT));
    }
    if (!vazio(situacao)) {
      usados.put("situacao", situacao.trim().toUpperCase(Locale.ROOT));
    }
    // Sem filtro de situacao, a soma fica nas autorizadas: rascunho, rejeitada e cancelada nao
    // sao venda nem compra, e "quanto vendi?" somando-as daria um total errado com fonte.
    boolean somenteAutorizadas = vazio(situacao);
    DadosProdutorPorta.Totais totais = dados.totalNotas(contexto.produtorId(), origens,
        somenteAutorizadas ? EnumSet.of(StatusNotaFiscal.AUTORIZADA) : status, inicio, fim);

    contexto.registrarFonte("listarNotas", usados, pagina.total() == 0);
    return new Notas(
        FormatoBr.data(inicio),
        FormatoBr.data(fim),
        pagina.total(),
        FormatoBr.moeda(totais.valor()),
        totais.quantidade(),
        somenteAutorizadas ? TOTAL_SO_AUTORIZADAS : TOTAL_DO_FILTRO,
        pagina.itens().stream()
            .map(n -> new Nota(
                n.numero(),
                n.serie(),
                FormatoBr.data(n.dataEmissao()),
                n.origem() == OrigemNotaFiscal.IMPORTADA ? "ENTRADA" : "SAIDA",
                n.status().name(),
                FormatoBr.moeda(n.valorTotal()),
                FormatoBr.moeda(n.valorIbs()),
                FormatoBr.moeda(n.valorCbs())))
            .toList());
  }

  /** Guias não pagas que vencem nos próximos dias. */
  @Tool(name = "proximosVencimentos", resultConverter = ResultadoSanitizadoConverter.class,
      description = """
      Lista as guias fiscais ainda nao pagas do produtor (ICMS, FUNRURAL, DARF, GPS...) que vencem \
      a partir de hoje, da mais proxima para a mais distante, no maximo 10. Traz em valorTotal a \
      soma de todas as guias nao pagas da janela, ja calculada pelo sistema. Use para perguntas \
      como "o que vence este mes?", "tenho imposto para pagar na semana que vem?" ou "quanto \
      somam minhas guias?". Para o total, repita o valorTotal: nunca some os valores da lista.""")
  public Vencimentos proximosVencimentos(
      @ToolParam(required = false,
          description = "Quantos dias a frente olhar, de 1 a 90. Omitido: 30.")
      Integer dias,
      ToolContext toolContext) {
    ContextoAssistente contexto = ContextoAssistente.de(toolContext);
    int janela = dias == null ? DIAS_VENCIMENTO_PADRAO : dias;
    if (janela < 1 || janela > MAXIMO_DIAS_VENCIMENTO) {
      throw new ParametroFerramentaInvalidoException("dias deve estar entre 1 e 90.");
    }
    LocalDate de = contexto.hoje();
    LocalDate ate = de.plusDays(janela);

    Pagina<DadosProdutorPorta.Vencimento> pagina = dados.vencimentosNaoPagos(
        contexto.produtorId(), de, ate, DadosProdutorPorta.LIMITE_MAXIMO);
    DadosProdutorPorta.Totais totais =
        dados.totalVencimentosNaoPagos(contexto.produtorId(), de, ate);

    contexto.registrarFonte(
        "proximosVencimentos", parametros("dias", String.valueOf(janela)), pagina.total() == 0);
    return new Vencimentos(
        FormatoBr.data(de),
        FormatoBr.data(ate),
        pagina.total(),
        FormatoBr.moeda(totais.valor()),
        pagina.itens().stream()
            .map(g -> new Guia(
                g.tipo().name(),
                FormatoBr.data(g.dataVencimento()),
                FormatoBr.moeda(g.valor()),
                g.status().name()))
            .toList());
  }

  /** Alertas tributários ativos: o que vence ou fecha em menos de 5 dias (issue #140, RN010). */
  @Tool(name = "listarAlertas", resultConverter = ResultadoSanitizadoConverter.class,
      description = """
      Lista os alertas tributarios do produtor: guias pendentes, fechamento da apuracao do mes e \
      fim do prazo para cancelar NF-e que vencem em menos de 5 dias, do mais urgente ao mais \
      distante, no maximo 10. Use para perguntas como "tenho algo vencendo?", "o que preciso \
      resolver esta semana?" ou "tenho algum prazo perto?".""")
  public Alertas listarAlertas(ToolContext toolContext) {
    ContextoAssistente contexto = ContextoAssistente.de(toolContext);
    Pagina<DadosProdutorPorta.Alerta> pagina = dados.alertas(contexto.produtorId());

    contexto.registrarFonte("listarAlertas", Map.of(), pagina.total() == 0);
    return new Alertas(
        pagina.total(),
        pagina.itens().stream()
            .map(a -> new Alerta(
                a.tipo(), a.descricao(), FormatoBr.data(a.dataLimite()), a.diasRestantes()))
            .toList());
  }

  // --- validação dos parâmetros vindos do modelo ---

  private static LocalDate data(String valor, String nome) {
    if (vazio(valor)) {
      throw new ParametroFerramentaInvalidoException(nome + " e obrigatorio (formato AAAA-MM-DD).");
    }
    try {
      return LocalDate.parse(valor.trim());
    } catch (DateTimeParseException e) {
      throw new ParametroFerramentaInvalidoException(
          nome + " deve estar no formato AAAA-MM-DD (ex.: 2026-01-31).");
    }
  }

  private static void exigirPeriodo(LocalDate inicio, LocalDate fim) {
    if (inicio.isAfter(fim)) {
      throw new ParametroFerramentaInvalidoException(
          "dataInicio deve ser anterior ou igual a dataFim.");
    }
    if (ChronoUnit.DAYS.between(inicio, fim) > MAXIMO_DIAS_PERIODO) {
      throw new ParametroFerramentaInvalidoException(
          "O periodo maximo de uma consulta e de 24 meses.");
    }
  }

  private static String termoDescricao(String valor) {
    String termo = valor == null ? "" : valor.trim().replaceAll("\\s+", " ");
    if (termo.length() < TERMO_MINIMO || termo.length() > TERMO_MAXIMO) {
      throw new ParametroFerramentaInvalidoException(
          "descricaoOuNcm deve ter de 3 a 60 caracteres, ou ser um NCM de 2 a 8 digitos.");
    }
    return termo;
  }

  private static Set<OrigemNotaFiscal> origens(String tipo) {
    if (vazio(tipo)) {
      return EnumSet.allOf(OrigemNotaFiscal.class);
    }
    return switch (tipo.trim().toUpperCase(Locale.ROOT)) {
      case "SAIDA" -> EnumSet.of(OrigemNotaFiscal.PROPRIA);
      case "ENTRADA" -> EnumSet.of(OrigemNotaFiscal.IMPORTADA);
      default -> throw new ParametroFerramentaInvalidoException(
          "tipo deve ser SAIDA ou ENTRADA (ou omitido).");
    };
  }

  private static Set<StatusNotaFiscal> status(String situacao) {
    if (vazio(situacao)) {
      return EnumSet.allOf(StatusNotaFiscal.class);
    }
    try {
      return EnumSet.of(StatusNotaFiscal.valueOf(situacao.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      throw new ParametroFerramentaInvalidoException(
          "situacao invalida; use um dos valores listados na descricao do parametro.");
    }
  }

  private static boolean vazio(String valor) {
    return valor == null || valor.isBlank();
  }

  private static Map<String, String> parametros(String... chavesEValores) {
    Map<String, String> mapa = new LinkedHashMap<>();
    for (int i = 0; i < chavesEValores.length; i += 2) {
      mapa.put(chavesEValores[i], chavesEValores[i + 1]);
    }
    return mapa;
  }
}
