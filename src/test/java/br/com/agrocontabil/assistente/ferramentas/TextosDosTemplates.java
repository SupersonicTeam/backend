package br.com.agrocontabil.assistente.ferramentas;

import br.com.agrocontabil.assistente.FonteDado;
import br.com.agrocontabil.assistente.SecaoResposta;
import br.com.agrocontabil.assistente.VerificadorNegativaDados;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Alerta;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Alertas;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Apuracao;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.CompraPorUnidade;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.ComprasInsumo;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Creditos;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Funrural;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Guia;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.GuiasDoRecorte;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.GuiasEmAtraso;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Inconsistencia;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Nota;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.NotaConsultada;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.NotaDetalhada;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Notas;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Retencoes;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Vencimentos;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.VencimentosDoMes;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.VendasProduto;
import br.com.agrocontabil.assistente.porta.DadosProdutorPorta.Sinal;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.Alternativa;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.Produto;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.TratamentoConsultado;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * Os textos fixos que a rota de dados entrega ao produtor (issue #357), um por variante de cada
 * template, para a varredura do {@code TextosFixosAcentuadosTest}: a saída de cada função do
 * {@link RedatorRespostaDados}, as frases fixas das consultas vazias, as frases dos sinais de
 * nota, as variantes do tratamento do produto e as constantes de {@link FerramentasDadosProdutor}
 * que chegam à resposta. Vive neste pacote porque os templates e as constantes são do pacote.
 *
 * <p>As fixtures não levam texto livre sem acento (descrição de item, título de pedido): a
 * varredura é do texto do backend, e o texto livre do produtor passa como veio.
 */
public final class TextosDosTemplates {

  private static final LocalDate HOJE = LocalDate.of(2026, 11, 2);
  private static final Map<String, String> PERIODO =
      Map.of("dataInicio", "2026-09-01", "dataFim", "2026-09-30");

  private TextosDosTemplates() {}

  /** Todos os textos, por nome da variante. */
  public static Map<String, String> todos() {
    Map<String, String> textos = new TreeMap<>();
    templatesDeDados(textos);
    frasesFixasVazias(textos);
    sinaisDeNota(textos);
    tratamentoDoProduto(textos);
    constantesDasFuncoes(textos);
    return textos;
  }

  private static void secao(Map<String, String> textos, String nome, SecaoResposta s) {
    textos.put(nome, s.texto());
    if (s.resumo() != null) {
      textos.put(nome + " (resumo)", s.resumo());
    }
    if (s.semPendencia() != null) {
      textos.put(nome + " (sem pendência)", s.semPendencia());
    }
  }

  private static void templatesDeDados(Map<String, String> t) {
    List<CompraPorUnidade> unidades = List.of(new CompraPorUnidade("KG", "1.500", "R$ 4.320,00"));
    secao(t, "compras", RedatorRespostaDados.compras(new ComprasInsumo("adubo", "DESCRICAO",
        "01/09/2026", "30/09/2026", "R$ 4.320,00", 3, unidades, null), PERIODO, HOJE));
    secao(t, "compras (1 nota, NCM)", RedatorRespostaDados.compras(new ComprasInsumo("3808",
        "NCM", "01/09/2026", "30/09/2026", "R$ 10,00", 1, List.of(), null), PERIODO, HOJE));
    secao(t, "compras vazia", RedatorRespostaDados.compras(new ComprasInsumo("adubo", "DESCRICAO",
        "01/09/2026", "30/09/2026", "R$ 0,00", 0, List.of(), "obs"),
        Map.of("descricaoOuNcm", "adubo", "dataInicio", "2026-09-01", "dataFim", "2026-09-30"),
        HOJE));
    secao(t, "vendas", RedatorRespostaDados.vendas(new VendasProduto("soja", "DESCRICAO",
        "01/09/2026", "30/09/2026", "R$ 9.000,00", 4, unidades, null), PERIODO, HOJE));
    secao(t, "vendas (1 nota, NCM)", RedatorRespostaDados.vendas(new VendasProduto("12019000",
        "NCM", "01/09/2026", "30/09/2026", "R$ 900,00", 1, List.of(), null), PERIODO, HOJE));
    secao(t, "vendas vazia", RedatorRespostaDados.vendas(new VendasProduto("soja", "DESCRICAO",
        "01/09/2026", "30/09/2026", "R$ 0,00", 0, List.of(), "obs"),
        Map.of("descricaoOuNcm", "soja", "dataInicio", "2026-09-01", "dataFim", "2026-09-30"),
        HOJE));
    secao(t, "apuração aberta", RedatorRespostaDados.apuracao(new Apuracao("2026-08", "ABERTO",
        "R$ 150,00", "R$ 1.350,00", "R$ 30,00", "R$ 270,00", "R$ 120,00", "R$ 1.080,00", 1),
        Map.of("mes", "2026-08"), HOJE));
    secao(t, "apuração fechada, saldo negativo", RedatorRespostaDados.apuracao(
        new Apuracao("2026-07", "FECHADO", "R$ 10,00", "R$ 90,00", "R$ 30,00", "R$ 270,00",
            "R$ -20,00", "R$ -180,00", 4), Map.of("mes", "2026-07"), HOJE));
    secao(t, "apuração vazia", RedatorRespostaDados.apuracao(new Apuracao("2026-05", "ABERTO",
        "R$ 0,00", "R$ 0,00", "R$ 0,00", "R$ 0,00", "R$ 0,00", "R$ 0,00", 0),
        Map.of("mes", "2026-05"), HOJE));
    secao(t, "créditos", RedatorRespostaDados.creditos(new Creditos("01/09/2026", "30/09/2026",
        "R$ 30,00", "R$ 270,00", "R$ 300,00", 2), PERIODO, HOJE));
    secao(t, "créditos vazio", RedatorRespostaDados.creditos(new Creditos("01/09/2026",
        "30/09/2026", "R$ 0,00", "R$ 0,00", "R$ 0,00", 0), PERIODO, HOJE));

    Funrural retido = new Funrural(2, "R$ 10.000,00", FerramentasDadosProdutor.ALIQUOTA_FUNRURAL,
        "R$ 120,00", "R$ 10,00", "R$ 20,00", "R$ 150,00");
    Funrural proprio = new Funrural(1, "R$ 5.000,00", FerramentasDadosProdutor.ALIQUOTA_FUNRURAL,
        "R$ 60,00", "R$ 5,00", "R$ 10,00", "R$ 75,00");
    Funrural exportacao = new Funrural(1, "R$ 3.000,00",
        FerramentasDadosProdutor.FUNRURAL_EXPORTACAO, "R$ 0,00", "R$ 0,00", "R$ 0,00", "R$ 0,00");
    List<String> avisosPf = new ArrayList<>(FerramentasDadosProdutor.AVISOS_PESSOA_FISICA);
    avisosPf.add(0, FerramentasDadosProdutor.AVISO_EXPORTACAO);
    secao(t, "retenções (pessoa física, as três linhas)", RedatorRespostaDados.retencoes(
        new Retencoes("01/09/2026", "30/09/2026", 4, retido, proprio, exportacao, "R$ 10,00",
            "R$ 90,00", "R$ 100,00", avisosPf), PERIODO, HOJE));
    Funrural nada = new Funrural(0, "R$ 0,00", FerramentasDadosProdutor.FUNRURAL_NAO_SE_APLICA,
        "R$ 0,00", "R$ 0,00", "R$ 0,00", "R$ 0,00");
    secao(t, "retenções (outros, uma venda)", RedatorRespostaDados.retencoes(
        new Retencoes("01/09/2026", "30/09/2026", 1, nada, nada, nada, "R$ 1,00", "R$ 9,00",
            "R$ 10,00", FerramentasDadosProdutor.AVISOS_OUTROS), PERIODO, HOJE));
    secao(t, "retenções vazia", RedatorRespostaDados.retencoes(new Retencoes("01/09/2026",
        "30/09/2026", 0, nada, nada, nada, "R$ 0,00", "R$ 0,00", "R$ 0,00", List.of()),
        PERIODO, HOJE));

    notas(t);
    guias(t);
    notaConsultada(t);

    secao(t, "alertas", RedatorRespostaDados.alertas(new Alertas(7, List.of(
        new Alerta("VENCIMENTO_GUIA", "Guia de ICMS", "02/11/2026", 0),
        new Alerta("FECHAMENTO_APURACAO", "Fechamento de 10/2026", "03/11/2026", 1),
        new Alerta("VENCIMENTO_GUIA", "Guia de GPS", "05/11/2026", 3))), HOJE));
    secao(t, "alerta único", RedatorRespostaDados.alertas(new Alertas(1, List.of(
        new Alerta("VENCIMENTO_GUIA", "Guia de ICMS", "03/11/2026", 1))), HOJE));
    secao(t, "alertas vazio", RedatorRespostaDados.alertas(new Alertas(0, List.of()), HOJE));

    List<Nota> paradas = IntStream.rangeClosed(1, 6)
        .mapToObj(i -> nota(100 + i, "DEVOLVIDA")).toList();
    secao(t, "notas paradas (6)", RedatorRespostaDados.notasParadas(paradas, 6, "04/11/2024"));
    secao(t, "nota parada (1)", RedatorRespostaDados.notasParadas(paradas.subList(0, 1), 1,
        "04/11/2024"));
    secao(t, "notas paradas vazio", RedatorRespostaDados.notasParadas(List.of(), 0, "04/11/2024"));

    List<String[]> pedidos = IntStream.rangeClosed(1, 6)
        .mapToObj(i -> new String[] {"Extrato " + i, i % 2 == 0 ? "07/11/2026" : null}).toList();
    secao(t, "pedidos (6)", RedatorRespostaDados.pedidos(pedidos, 6));
    secao(t, "pedido (1)", RedatorRespostaDados.pedidos(pedidos.subList(0, 1), 1));
    secao(t, "pedidos vazio", RedatorRespostaDados.pedidos(List.of(), 0));
    secao(t, "notas a conferir vazio", RedatorRespostaDados.notasAConferir(List.of()));
    secao(t, "notas a conferir", RedatorRespostaDados.notasAConferir(List.of("Na nota 1, ok.")));
  }

  private static Nota nota(long numero, String situacao) {
    return new Nota(numero, 1, "28/10/2026", "SAIDA", situacao, "R$ 500,00", "R$ 0,50",
        "R$ 4,50");
  }

  /** Uma nota por situação, filtrada e sem filtro, com tipo de venda e de compra. */
  private static void notas(Map<String, String> t) {
    for (StatusNotaFiscal status : StatusNotaFiscal.values()) {
      String s = status.name();
      secao(t, "notas " + s, RedatorRespostaDados.notas(new Notas("04/11/2024", "02/11/2026", 1,
          "R$ 500,00", 1, "filtro", List.of(nota(118, s))),
          Map.of("situacao", s, "tipo", "ENTRADA"), HOJE));
      secao(t, "notas " + s + " vazio", RedatorRespostaDados.notas(new Notas("04/11/2024",
          "02/11/2026", 0, "R$ 0,00", 0, "filtro", List.of()),
          Map.of("situacao", s, "tipo", "SAIDA", "dataInicio", "2024-11-04",
              "dataFim", "2026-11-02"), HOJE));
    }
    List<Nota> sete = IntStream.rangeClosed(1, 7).mapToObj(i -> nota(100 + i, "AUTORIZADA"))
        .toList();
    secao(t, "notas sem filtro, com teto", RedatorRespostaDados.notas(new Notas("01/09/2026",
        "30/09/2026", 12, "R$ 12.000,00", 12, "autorizadas", sete), Map.of(), HOJE));
  }

  private static void guias(Map<String, String> t) {
    Guia atrasada = new Guia("FUNRURAL", "20/10/2026", "R$ 430,00", "VENCIDA");
    Guia aVencer = new Guia("ICMS", "20/11/2026", "R$ 310,00", "PENDENTE");
    List<Guia> sete = IntStream.rangeClosed(1, 7).mapToObj(i -> aVencer).toList();
    secao(t, "próximos vencimentos", RedatorRespostaDados.proximosVencimentos(
        new Vencimentos("02/11/2026", "02/12/2026", 7, "R$ 2.170,00", sete),
        Map.of("dias", "30"), HOJE));
    secao(t, "próximo vencimento (1)", RedatorRespostaDados.proximosVencimentos(
        new Vencimentos("02/11/2026", "02/12/2026", 1, "R$ 310,00", List.of(aVencer)),
        Map.of("dias", "30"), HOJE));
    secao(t, "próximos vencimentos vazio", RedatorRespostaDados.proximosVencimentos(
        new Vencimentos("02/11/2026", "02/12/2026", 0, "R$ 0,00", List.of()),
        Map.of("dias", "30"), HOJE));

    GuiasDoRecorte semAtraso = new GuiasDoRecorte(null, "01/11/2026", 0, "R$ 0,00", List.of());
    secao(t, "guias em atraso", RedatorRespostaDados.guiasEmAtraso(new GuiasEmAtraso(
        new GuiasDoRecorte(null, "01/11/2026", 7, "R$ 3.010,00",
            IntStream.rangeClosed(1, 7).mapToObj(i -> atrasada).toList()), null, null), HOJE));
    secao(t, "guia em atraso (1)", RedatorRespostaDados.guiasEmAtraso(new GuiasEmAtraso(
        new GuiasDoRecorte(null, "01/11/2026", 1, "R$ 430,00", List.of(atrasada)), null, null),
        HOJE));
    secao(t, "sem atraso, com a próxima guia", RedatorRespostaDados.guiasEmAtraso(
        new GuiasEmAtraso(semAtraso, aVencer, FerramentasDadosProdutor.SEM_ATRASO
            + "01/11/2026)."), HOJE));
    secao(t, "sem atraso, sem observação", RedatorRespostaDados.guiasEmAtraso(
        new GuiasEmAtraso(semAtraso, null, null), HOJE));

    secao(t, "vencimentos do mês, atraso e a vencer", RedatorRespostaDados.vencimentosDoMes(
        new VencimentosDoMes("2026-11",
            new GuiasDoRecorte(null, "01/11/2026", 1, "R$ 430,00", List.of(atrasada)),
            new GuiasDoRecorte("02/11/2026", "30/11/2026", 7, "R$ 2.170,00", sete),
            8, "R$ 2.600,00", null), HOJE));
    secao(t, "vencimentos de outro mês", RedatorRespostaDados.vencimentosDoMes(
        new VencimentosDoMes("2026-12", null, new GuiasDoRecorte("01/12/2026", "31/12/2026", 1,
            "R$ 310,00", List.of(aVencer)), 1, "R$ 310,00", null), HOJE));
    secao(t, "vencimentos do mês vazio", RedatorRespostaDados.vencimentosDoMes(
        new VencimentosDoMes("2026-12", null, null, 0, "R$ 0,00", null), HOJE));
    secao(t, "vencimentos do mês vazio, com observação", RedatorRespostaDados.vencimentosDoMes(
        new VencimentosDoMes("2026-12", null, null, 0, "R$ 0,00",
            FerramentasDadosProdutor.SEM_GUIAS_NO_MES
                + "(sem guias não pagas de 01/12/2026 a 31/12/2026)."), HOJE));
  }

  private static void notaConsultada(Map<String, String> t) {
    List<Inconsistencia> seis = IntStream.rangeClosed(1, 6)
        .mapToObj(i -> new Inconsistencia("X" + i, "campo", "Mensagem " + i, "Sugestão " + i))
        .toList();
    for (StatusNotaFiscal status : StatusNotaFiscal.values()) {
      String explicacao = FerramentasDadosProdutor.EXPLICACAO_SITUACAO.get(status);
      secao(t, "nota consultada " + status, RedatorRespostaDados.nota(new NotaConsultada(118, 1,
          List.of(new NotaDetalhada(118, 1, "28/10/2026", "ENTRADA", status.name(), explicacao,
              "R$ 500,00", "R$ 0,50", "R$ 4,50", null, List.of(), List.of())), null), HOJE));
    }
    secao(t, "nota consultada, devolvida com inconsistências", RedatorRespostaDados.nota(
        new NotaConsultada(118, 3, List.of(new NotaDetalhada(118, 1, "28/10/2026", "SAIDA",
            "DEVOLVIDA", FerramentasDadosProdutor.EXPLICACAO_SITUACAO.get(
                StatusNotaFiscal.DEVOLVIDA), "R$ 500,00", "R$ 0,50", "R$ 4,50", "Motivo",
            seis, List.of())), null), HOJE));
    secao(t, "nota consultada vazia", RedatorRespostaDados.nota(
        new NotaConsultada(5, 0, List.of(), null), HOJE));
    secao(t, "nota consultada vazia, com observação", RedatorRespostaDados.nota(
        new NotaConsultada(5, 0, List.of(), "Não encontrei a nota 5 nas suas notas."), HOJE));
  }

  /** A frase fixa de cada consulta vazia, com o recorte e com cada filtro de situação. */
  private static void frasesFixasVazias(Map<String, String> t) {
    Map<String, Map<String, String>> consultas = new TreeMap<>(Map.ofEntries(
        Map.entry("totalComprasPorInsumo", Map.of("descricaoOuNcm", "adubo",
            "dataInicio", "2026-09-01", "dataFim", "2026-09-30")),
        Map.entry("totalVendasPorProduto", Map.of("descricaoOuNcm", "3808",
            "dataInicio", "2026-09-01", "dataFim", "2026-09-30")),
        Map.entry("consultarCreditos", PERIODO),
        Map.entry("consultarRetencoes", PERIODO),
        Map.entry("consultarApuracao", Map.of("mes", "2026-03")),
        Map.entry("consultarApuracao sem mês", Map.of()),
        Map.entry("proximosVencimentos", Map.of("dias", "15")),
        Map.entry("guiasEmAtraso", Map.of()),
        Map.entry("vencimentosDoMes", Map.of("mes", "2026-12")),
        Map.entry("vencimentosDoMes sem mês", Map.of()),
        Map.entry("consultarNota", Map.of("numero", "118")),
        Map.entry("listarAlertas", Map.of()),
        Map.entry("resumoDoProdutor", Map.of()),
        Map.entry("consultarTratamentoProduto", Map.of("produtoOuNcm", "leite")),
        Map.entry("consultarTratamentoProduto sem produto", Map.of()),
        Map.entry("função desconhecida", Map.of())));
    consultas.forEach((nome, parametros) -> {
      String funcao = nome.replaceAll(" .*", "");
      funcao = nome.startsWith("função") ? "naoExiste" : funcao;
      t.put("frase fixa " + nome, VerificadorNegativaDados.fraseFixa(
          new FonteDado(funcao, parametros), HOJE));
    });
    for (StatusNotaFiscal status : StatusNotaFiscal.values()) {
      for (String tipo : new String[] {"SAIDA", "ENTRADA"}) {
        t.put("frase fixa listarNotas " + tipo + " " + status, VerificadorNegativaDados.fraseFixa(
            new FonteDado("listarNotas", Map.of("tipo", tipo, "situacao", status.name(),
                "dataInicio", "2026-09-01", "dataFim", "2026-09-30")), HOJE));
      }
    }
    t.put("frase fixa listarNotas sem filtro", VerificadorNegativaDados.fraseFixa(
        new FonteDado("listarNotas", PERIODO), HOJE));
  }

  private static void sinaisDeNota(Map<String, String> t) {
    OffsetDateTime limite = OffsetDateTime.of(2026, 11, 26, 10, 0, 0, 0, ZoneOffset.ofHours(-3));
    BigDecimal preco = new BigDecimal("1450.00");
    BigDecimal referencia = new BigDecimal("1.45");
    String[] momentos = {"ANTES_DE_EMITIR", "AUTORIZADA_NO_PRAZO", "AUTORIZADA_FORA_DO_PRAZO"};
    for (String momento : momentos) {
      for (String motivo : new String[] {"CFOP_GENERICO", "NCM_FORA_DA_REFERENCIA",
          "UNIDADE_DE_SERVICO"}) {
        t.put("sinal PRODUTO_PARECE_SERVICO " + motivo + " " + momento,
            FrasesSinaisNota.texto(116, 2, List.of(new Sinal("PRODUTO_PARECE_SERVICO", motivo,
                momento, 1, "Frete", "5949", limite, "HA", null, null, null, null))));
      }
      t.put("sinal PRODUTO_PARECE_SERVICO CFOP sem quatro dígitos " + momento,
          FrasesSinaisNota.texto(116, null, List.of(new Sinal("PRODUTO_PARECE_SERVICO",
              "CFOP_GENERICO", momento, 1, "Frete", "59", limite, null, null, null, null,
              null))));
      for (String motivo : new String[] {"ACIMA_DO_PADRAO", "ABAIXO_DO_PADRAO"}) {
        t.put("sinal VALOR_UNITARIO_FORA_PADRAO " + motivo + " " + momento,
            FrasesSinaisNota.texto(116, null, List.of(new Sinal("VALOR_UNITARIO_FORA_PADRAO",
                motivo, momento, 1, "Milho", "5101", limite, "KG", preco, referencia, null,
                null))));
      }
      t.put("sinal POSSIVEL_DUPLICIDADE " + momento,
          FrasesSinaisNota.texto(116, 2, List.of(new Sinal("POSSIVEL_DUPLICIDADE",
              "MESMA_VENDA", momento, null, null, null, limite, null, null, null, 115L, 1))));
      t.put("sinal POSSIVEL_DUPLICIDADE sem série " + momento,
          FrasesSinaisNota.texto(116, null, List.of(new Sinal("POSSIVEL_DUPLICIDADE",
              "MESMA_VENDA", momento, null, null, null, limite, null, null, null, 115L,
              null))));
      for (String codigo : new String[] {"ICMS_PADRAO_A_CONFIRMAR", "CCLASSTRIB_NAO_ADMITIDO",
          "ICMS_A_DEFINIR", "OUTRO_CODIGO"}) {
        t.put("sinal " + codigo + " " + momento, FrasesSinaisNota.texto(116, null,
            List.of(new Sinal(codigo, "MOTIVO", momento, 1, "Milho", "5101", limite, null, null,
                null, null, null))));
      }
    }
    t.put("sinal sem descrição do item", FrasesSinaisNota.texto(1, null, List.of(
        new Sinal("ICMS_A_DEFINIR", "SEM_CST_ICMS", "ANTES_DE_EMITIR", 1, null, "5101", null,
            null, null, null, null, null))));
  }

  private static void tratamentoDoProduto(Map<String, String> t) {
    Produto soja = new Produto("12019000", "Soja, mesmo triturada", 1, "200036",
        new BigDecimal("60.00"), true, null, "137", "vendido in natura", true);
    Produto arroz = new Produto("10063021", "Arroz polido", 1, "200003",
        new BigDecimal("100.00"), false, 1, "125", "destinado à alimentação humana", true);
    Produto adubo = new Produto(null, "Fertilizantes", 35, "200038", new BigDecimal("60.00"),
        false, 9, "138", null, true);
    Produto integral = new Produto("99999999", null, 1, "000001", BigDecimal.ZERO, false, null,
        null, null, true);
    Produto desconhecido = new Produto("07133390", null, 1, "000001", BigDecimal.ZERO, false,
        null, null, null, false);
    Produto leite = new Produto("04012090", "Leite, outros", 1, "200036",
        new BigDecimal("60.00"), true, null, "137", "leite cru ou apenas resfriado", true,
        new Alternativa("200003", new BigDecimal("100.00"), 1, "125", "leite para consumo direto"));
    Produto leiteSemArtigo = new Produto("04012010", "Leite UHT", 1, "200003",
        new BigDecimal("100.00"), false, 1, "125", "leite UHT", true,
        new Alternativa("200036", new BigDecimal("30.50"), null, null, "leite cru"));
    t.put("tratamento: in natura", TemplateTratamentoProduto.texto(
        new TratamentoConsultado("soja", List.of(soja), "IT 2025.002 v1.70")));
    t.put("tratamento: anexo, alíquota zero", TemplateTratamentoProduto.texto(
        new TratamentoConsultado("arroz", List.of(arroz), null)));
    t.put("tratamento: grupo de NCMs", TemplateTratamentoProduto.texto(
        new TratamentoConsultado("adubo", List.of(adubo), "IT 2025.002 v1.70")));
    t.put("tratamento: alíquota cheia", TemplateTratamentoProduto.texto(
        new TratamentoConsultado("x", List.of(integral), null)));
    t.put("tratamento: NCM fora da referência", TemplateTratamentoProduto.texto(
        new TratamentoConsultado("07133390", List.of(desconhecido), null)));
    t.put("tratamento: leite, duas situações", TemplateTratamentoProduto.texto(
        new TratamentoConsultado("leite", List.of(leite), "IT 2025.002 v1.70")));
    t.put("tratamento: alternativa sem artigo", TemplateTratamentoProduto.texto(
        new TratamentoConsultado("leite", List.of(leiteSemArtigo), null)));
    t.put("tratamento: ressalvas juntas", TemplateTratamentoProduto.texto(
        new TratamentoConsultado("x", List.of(leite, arroz, soja), null)));
    t.put("tratamento: nada achado", TemplateTratamentoProduto.texto(
        new TratamentoConsultado("trator", List.of(), null)));
    t.put("tratamento: nada achado, sem termo", TemplateTratamentoProduto.naoEncontrado(null));
    t.put("tratamento: ressalva", TemplateTratamentoProduto.RESSALVA);
    t.put("tratamento: ressalva da alternativa", TemplateTratamentoProduto.RESSALVA_ALTERNATIVA);
    t.put("tratamento: ressalva do enquadramento",
        TemplateTratamentoProduto.RESSALVA_ENQUADRAMENTO);
  }

  /** As constantes de {@link FerramentasDadosProdutor} que chegam à resposta ou ao DTO. */
  private static void constantesDasFuncoes(Map<String, String> t) {
    t.put("NADA_POR_DESCRICAO", FerramentasDadosProdutor.NADA_POR_DESCRICAO);
    t.put("NADA_VENDIDO", FerramentasDadosProdutor.NADA_VENDIDO);
    t.put("NADA_POR_NCM", FerramentasDadosProdutor.NADA_POR_NCM);
    t.put("TOTAL_SO_AUTORIZADAS", FerramentasDadosProdutor.TOTAL_SO_AUTORIZADAS);
    t.put("TOTAL_DO_FILTRO", FerramentasDadosProdutor.TOTAL_DO_FILTRO);
    t.put("FUNRURAL_NAO_SE_APLICA", FerramentasDadosProdutor.FUNRURAL_NAO_SE_APLICA);
    t.put("FUNRURAL_EXPORTACAO", FerramentasDadosProdutor.FUNRURAL_EXPORTACAO);
    t.put("AVISO_EXPORTACAO", FerramentasDadosProdutor.AVISO_EXPORTACAO);
    t.put("SEM_ATRASO", FerramentasDadosProdutor.SEM_ATRASO + "28/09/2026).");
    t.put("SEM_GUIAS_NO_MES", FerramentasDadosProdutor.SEM_GUIAS_NO_MES + "(sem guias).");
    List<String> pf = FerramentasDadosProdutor.AVISOS_PESSOA_FISICA;
    IntStream.range(0, pf.size()).forEach(i -> t.put("AVISOS_PESSOA_FISICA #" + i, pf.get(i)));
    List<String> outros = FerramentasDadosProdutor.AVISOS_OUTROS;
    IntStream.range(0, outros.size()).forEach(i -> t.put("AVISOS_OUTROS #" + i, outros.get(i)));
    FerramentasDadosProdutor.EXPLICACAO_SITUACAO.forEach((status, texto) ->
        t.put("EXPLICACAO_SITUACAO " + status, texto));
  }
}
