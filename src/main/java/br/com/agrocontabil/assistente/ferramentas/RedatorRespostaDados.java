package br.com.agrocontabil.assistente.ferramentas;

import br.com.agrocontabil.assistente.FonteDado;
import br.com.agrocontabil.assistente.SecaoResposta;
import br.com.agrocontabil.assistente.SecaoResposta.Assunto;
import br.com.agrocontabil.assistente.VerificadorNegativaDados;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Alertas;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Apuracao;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.CompraPorUnidade;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.ComprasInsumo;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Creditos;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Funrural;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Guia;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.GuiasDoRecorte;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.GuiasEmAtraso;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.NotaConsultada;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.NotaDetalhada;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Notas;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Retencoes;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.Vencimentos;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.VencimentosDoMes;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.VendasProduto;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Templates da rota de dados (issue #266): cada função vira uma {@link SecaoResposta} com o texto
 * ao produtor, sem segunda chamada ao modelo.
 *
 * <p>Regras de redação: frases curtas, em segunda pessoa, sem jargão ("imposto a pagar", nunca
 * "valorTotal"); valores e datas no formato brasileiro, já formatados pela função
 * ({@link FormatoBr}); <strong>sempre o recorte</strong> (datas, mês, filtro); e resultado vazio
 * nunca vira negação absoluta: a frase é a fixa do {@link VerificadorNegativaDados}, com o recorte
 * real. Listas param em {@value #MAXIMO_ITENS} itens, com "e mais N" e a tela onde ver o resto.
 * Funções puras: os números saem dos resultados das funções, que o {@code ValidadorNumeros}
 * confere.
 */
public final class RedatorRespostaDados {

  /** Itens de uma lista na resposta. */
  static final int MAXIMO_ITENS = 5;

  /** Situações em que a nota está parada, esperando o produtor ou o contador. */
  static final Set<String> SITUACOES_PARADAS =
      Set.of("DEVOLVIDA", "AGUARDANDO_APROVACAO", "COM_INCONSISTENCIAS", "REJEITADA");

  /** A situação de uma nota, no singular. */
  private static final Map<String, String> SITUACAO = Map.ofEntries(
      Map.entry("RASCUNHO", "em rascunho"),
      Map.entry("COM_INCONSISTENCIAS", "com inconsistências a corrigir"),
      Map.entry("AGUARDANDO_APROVACAO", "aguardando aprovação do contador"),
      Map.entry("APROVADA", "aprovada pelo contador"),
      Map.entry("DEVOLVIDA", "devolvida pelo contador"),
      Map.entry("XML_GERADO", "com XML gerado, ainda não enviada"),
      Map.entry("ENVIANDO", "sendo enviada à SEFAZ"),
      Map.entry("AUTORIZADA", "autorizada"),
      Map.entry("REJEITADA", "rejeitada pela SEFAZ"),
      Map.entry("PENDENTE_SEFAZ", "aguardando a SEFAZ"),
      Map.entry("CANCELANDO", "em cancelamento"),
      Map.entry("CANCELADA", "cancelada"));

  private RedatorRespostaDados() {}

  /** Seção vazia informativa: a frase fixa do backend, com o recorte. */
  static SecaoResposta vazia(String funcao, Map<String, String> parametros, LocalDate hoje) {
    return SecaoResposta.informativa(fraseVazia(funcao, parametros, hoje), true, List.of());
  }

  static String fraseVazia(String funcao, Map<String, String> parametros, LocalDate hoje) {
    return VerificadorNegativaDados.fraseFixa(new FonteDado(funcao, parametros), hoje);
  }

  /** {@code totalComprasPorInsumo}. */
  static SecaoResposta compras(ComprasInsumo c, Map<String, String> parametros, LocalDate hoje) {
    if (c.quantidadeNotas() == 0) {
      return vazia("totalComprasPorInsumo", parametros, hoje);
    }
    return SecaoResposta.informativa("Entre " + c.dataInicio() + " e " + c.dataFim()
        + " você comprou " + c.valorTotal() + " de " + produto(c.insumo(), c.criterio()) + ", em "
        + notas(c.quantidadeNotas(), "de compra") + "." + unidades(c.porUnidade()), false,
        List.of());
  }

  /** {@code totalVendasPorProduto}. */
  static SecaoResposta vendas(VendasProduto v, Map<String, String> parametros, LocalDate hoje) {
    if (v.quantidadeNotas() == 0) {
      return vazia("totalVendasPorProduto", parametros, hoje);
    }
    return SecaoResposta.informativa("Entre " + v.dataInicio() + " e " + v.dataFim()
        + " você vendeu " + v.valorTotal() + " de " + produto(v.produto(), v.criterio()) + ", em "
        + notas(v.quantidadeNotas(), v.quantidadeNotas() == 1 ? "de venda autorizada"
            : "de venda autorizadas") + "." + unidades(v.porUnidade()), false, List.of());
  }

  /** {@code consultarApuracao}. */
  static SecaoResposta apuracao(Apuracao a, Map<String, String> parametros, LocalDate hoje) {
    if (a.totalNotas() == 0) {
      return vazia("consultarApuracao", parametros, hoje);
    }
    String mes = mes(a.mes());
    boolean negativo = a.saldoIbs().contains("-") || a.saldoCbs().contains("-");
    String saldo = negativo
        ? "o saldo é de " + a.saldoIbs() + " de IBS e " + a.saldoCbs()
            + " de CBS (valor negativo é crédito a seu favor)"
        : "o imposto a pagar é de " + a.saldoIbs() + " de IBS e " + a.saldoCbs() + " de CBS";
    String situacao = "FECHADO".equals(a.situacao()) ? "fechada"
        : "ainda aberta: o valor pode mudar até o fechamento";
    return SecaoResposta.informativa("Na apuração de " + mes + " (" + situacao + "), " + saldo
        + ". Imposto das suas vendas: " + a.debitoIbs() + " de IBS e " + a.debitoCbs()
        + " de CBS. Crédito das suas compras: " + a.creditoIbs() + " de IBS e " + a.creditoCbs()
        + " de CBS. " + (a.totalNotas() == 1 ? "Entrou " : "Entraram ")
        + notas(a.totalNotas(), "") + ".", false, List.of(mes));
  }

  /** {@code consultarCreditos}. */
  static SecaoResposta creditos(Creditos c, Map<String, String> parametros, LocalDate hoje) {
    if (c.quantidadeNotas() == 0) {
      return vazia("consultarCreditos", parametros, hoje);
    }
    return SecaoResposta.informativa("Entre " + c.dataInicio() + " e " + c.dataFim()
        + " suas compras geraram " + c.creditoTotal() + " de crédito: " + c.creditoIbs()
        + " de IBS e " + c.creditoCbs() + " de CBS, em " + notas(c.quantidadeNotas(), "de compra")
        + ".", false, List.of());
  }

  /** {@code consultarRetencoes}. */
  static SecaoResposta retencoes(Retencoes r, Map<String, String> parametros, LocalDate hoje) {
    if (r.vendasAutorizadas() == 0) {
      return vazia("consultarRetencoes", parametros, hoje);
    }
    StringBuilder t = new StringBuilder("Entre ").append(r.dataInicio()).append(" e ")
        .append(r.dataFim()).append(", nas suas ")
        .append(plural(r.vendasAutorizadas(), "venda autorizada", "vendas autorizadas"))
        .append(':');
    Funrural retido = r.funruralRetidoPeloComprador();
    if (retido != null && retido.notas() > 0) {
      t.append("\n- Funrural retido pelo comprador: ").append(retido.total()).append(", em ")
          .append(plural(retido.notas(), "venda", "vendas"))
          .append(" a empresa ou cooperativa.");
    }
    Funrural proprio = r.funruralSemRetencao();
    if (proprio != null && proprio.notas() > 0) {
      t.append("\n- Funrural que você mesmo recolhe: ").append(proprio.total()).append(", em ")
          .append(plural(proprio.notas(), "venda", "vendas"))
          .append(" a pessoa física ou no varejo.");
    }
    Funrural exportacao = r.exportacao();
    if (exportacao != null && exportacao.notas() > 0) {
      t.append("\n- Exportação: ").append(plural(exportacao.notas(), "venda", "vendas"))
          .append(", sem Funrural.");
    }
    t.append("\n- Split payment: ").append(r.splitPaymentTotal()).append(" (")
        .append(r.splitPaymentIbs()).append(" de IBS e ").append(r.splitPaymentCbs())
        .append(" de CBS) ficam separados no pagamento das vendas.");
    r.avisos().forEach(aviso -> t.append('\n').append(aviso));
    return SecaoResposta.informativa(t.toString(), false, r.avisos());
  }

  /** {@code listarNotas}. */
  static SecaoResposta notas(Notas n, Map<String, String> parametros, LocalDate hoje) {
    String situacao = parametros.get("situacao");
    boolean parada = situacao != null && SITUACOES_PARADAS.contains(situacao);
    if (n.totalEncontrado() == 0) {
      String frase = fraseVazia("listarNotas", parametros, hoje);
      return parada
          ? new SecaoResposta(Assunto.NOTAS_PARADAS, frase, 0, null,
              "notas " + SITUACAO.get(situacao), true, true, List.of())
          : SecaoResposta.informativa(frase, true, List.of());
    }
    String tipo = parametros.get("tipo");
    String filtro = ("SAIDA".equals(tipo) ? " de venda" : "ENTRADA".equals(tipo) ? " de compra" : "")
        + (situacao == null ? "" : " " + SITUACAO.getOrDefault(situacao, situacao.toLowerCase()));
    StringBuilder t = new StringBuilder("Entre ").append(n.dataInicio()).append(" e ")
        .append(n.dataFim()).append(" encontrei ")
        .append(plural(n.totalEncontrado(), "nota", "notas")).append(filtro).append('.');
    if (n.quantidadeNoTotal() > 0) {
      t.append(situacao == null ? " Somando as autorizadas: " : " Somando todas: ")
          .append(n.valorTotal()).append(", em ").append(notas(n.quantidadeNoTotal(), ""))
          .append('.');
    }
    n.notas().stream().limit(MAXIMO_ITENS).forEach(nota -> t.append("\n- Nota ")
        .append(nota.numero()).append(" (série ").append(nota.serie()).append("), ")
        .append("ENTRADA".equals(nota.tipo()) ? "compra" : "venda").append(" de ")
        .append(nota.dataEmissao()).append(": ").append(nota.valorTotal()).append(", ")
        .append(SITUACAO.getOrDefault(nota.situacao(), nota.situacao().toLowerCase()))
        .append('.'));
    maisNaTela(t, n.totalEncontrado(), Math.min(MAXIMO_ITENS, n.notas().size()), "Notas");
    if (!parada) {
      return SecaoResposta.informativa(t.toString(), false, List.of());
    }
    return new SecaoResposta(Assunto.NOTAS_PARADAS, t.toString(), (int) n.totalEncontrado(),
        plural(n.totalEncontrado(), "nota", "notas") + " " + SITUACAO.get(situacao),
        "notas " + SITUACAO.get(situacao), false, true, List.of());
  }

  /** {@code proximosVencimentos}. */
  static SecaoResposta proximosVencimentos(Vencimentos v, Map<String, String> parametros,
      LocalDate hoje) {
    String recorte = "guias a vencer de " + v.de() + " a " + v.ate();
    if (v.totalEncontrado() == 0) {
      return new SecaoResposta(Assunto.PRAZOS, fraseVazia("proximosVencimentos", parametros, hoje),
          0, null, recorte, true, true, List.of());
    }
    StringBuilder t = new StringBuilder("De ").append(v.de()).append(" a ").append(v.ate())
        .append(v.totalEncontrado() == 1 ? " vence " : " vencem ")
        .append(plural(v.totalEncontrado(), "guia não paga", "guias não pagas"))
        .append(", somando ").append(v.valorTotal()).append(':');
    guias(t, v.guias(), "vence em");
    maisNaTela(t, v.totalEncontrado(), Math.min(MAXIMO_ITENS, v.guias().size()), "Calendário");
    return new SecaoResposta(Assunto.PRAZOS, t.toString(), (int) v.totalEncontrado(),
        plural(v.totalEncontrado(), "guia", "guias") + " a vencer até " + v.ate(), recorte, false,
        true, List.of());
  }

  /** {@code guiasEmAtraso}. */
  static SecaoResposta guiasEmAtraso(GuiasEmAtraso g, LocalDate hoje) {
    String ontem = FormatoBr.data(hoje.minusDays(1));
    String recorte = "guias em atraso (vencimento até " + ontem + ")";
    GuiasDoRecorte atraso = g.emAtraso();
    if (atraso.quantidade() == 0) {
      StringBuilder t = new StringBuilder(g.observacao() == null
          ? fraseVazia("guiasEmAtraso", Map.of(), hoje) : g.observacao());
      Guia proxima = g.proximaGuia();
      if (proxima != null) {
        t.append(" A próxima guia a vencer é ").append(proxima.tipo()).append(", em ")
            .append(proxima.vencimento()).append(", de ").append(proxima.valor()).append('.');
      }
      return new SecaoResposta(Assunto.GUIAS_EM_ATRASO, t.toString(), 0, null, recorte, true,
          true, List.of(ontem));
    }
    StringBuilder t = new StringBuilder("Você tem ")
        .append(plural(atraso.quantidade(), "guia em atraso", "guias em atraso"))
        .append(atraso.quantidade() == 1 ? " (não paga" : " (não pagas")
        .append(", com vencimento até ").append(ontem).append("), somando ")
        .append(atraso.valorTotal()).append(':');
    guias(t, atraso.guias(), "venceu em");
    maisNaTela(t, atraso.quantidade(), Math.min(MAXIMO_ITENS, atraso.guias().size()),
        "Calendário");
    return new SecaoResposta(Assunto.GUIAS_EM_ATRASO, t.toString(), (int) atraso.quantidade(),
        plural(atraso.quantidade(), "guia em atraso", "guias em atraso"), recorte, false, true,
        List.of(ontem));
  }

  /** {@code vencimentosDoMes}. */
  static SecaoResposta vencimentosDoMes(VencimentosDoMes v, LocalDate hoje) {
    String mes = mes(v.mes());
    String recorte = "guias para pagar em " + mes;
    if (v.quantidade() == 0) {
      String frase = v.observacao() == null
          ? fraseVazia("vencimentosDoMes", Map.of("mes", v.mes()), hoje) : v.observacao();
      return new SecaoResposta(Assunto.PRAZOS, frase, 0, null, recorte, true, true,
          List.of(mes));
    }
    StringBuilder t = new StringBuilder("Em ").append(mes).append(": ")
        .append(plural(v.quantidade(), "guia para pagar", "guias para pagar"))
        .append(", somando ").append(v.valorTotal()).append('.');
    if (v.emAtraso() != null && v.emAtraso().quantidade() > 0) {
      t.append("\nEm atraso: ").append(plural(v.emAtraso().quantidade(), "guia", "guias"))
          .append(", ").append(v.emAtraso().valorTotal()).append(':');
      guias(t, v.emAtraso().guias(), "venceu em");
      maisNaTela(t, v.emAtraso().quantidade(),
          Math.min(MAXIMO_ITENS, v.emAtraso().guias().size()), "Calendário");
    }
    if (v.aVencer() != null && v.aVencer().quantidade() > 0) {
      t.append("\nA vencer de ").append(v.aVencer().de()).append(" a ").append(v.aVencer().ate())
          .append(": ").append(plural(v.aVencer().quantidade(), "guia", "guias")).append(", ")
          .append(v.aVencer().valorTotal()).append(':');
      guias(t, v.aVencer().guias(), "vence em");
      maisNaTela(t, v.aVencer().quantidade(),
          Math.min(MAXIMO_ITENS, v.aVencer().guias().size()), "Calendário");
    }
    long atrasadas = v.emAtraso() == null ? 0 : v.emAtraso().quantidade();
    if (atrasadas > 0) {
      // Dinheiro em atraso e o assunto mais urgente; no resumo, conta uma vez com guiasEmAtraso.
      return new SecaoResposta(Assunto.GUIAS_EM_ATRASO, t.toString(), (int) atrasadas,
          plural(atrasadas, "guia em atraso", "guias em atraso"), recorte, false, true,
          List.of(mes));
    }
    return new SecaoResposta(Assunto.PRAZOS, t.toString(), (int) v.quantidade(),
        plural(v.quantidade(), "guia", "guias") + " para pagar em " + mes, recorte, false, true,
        List.of(mes));
  }

  /** {@code consultarNota}. Os sinais da nota (#276) entram pelos textos do backend. */
  static SecaoResposta nota(NotaConsultada c, LocalDate hoje) {
    if (c.notas().isEmpty()) {
      return SecaoResposta.informativa(c.observacao() == null
          ? fraseVazia("consultarNota", Map.of("numero", String.valueOf(c.numeroPedido())), hoje)
          : c.observacao(), true, List.of());
    }
    List<String> livres = new ArrayList<>();
    StringBuilder t = new StringBuilder();
    for (NotaDetalhada n : c.notas()) {
      if (!t.isEmpty()) {
        t.append("\n\n");
      }
      t.append("A nota ").append(n.numero()).append(" (série ").append(n.serie()).append("), ")
          .append("ENTRADA".equals(n.tipo()) ? "de compra recebida" : "de venda emitida")
          .append(" em ").append(n.dataEmissao()).append(", está ")
          .append(SITUACAO.getOrDefault(n.situacao(), n.situacao().toLowerCase())).append('.');
      if (n.explicacao() != null) {
        t.append(' ').append(n.explicacao());
        livres.add(n.explicacao());
      }
      t.append(" Valor: ").append(n.valorTotal()).append("; IBS: ").append(n.valorIbs())
          .append("; CBS: ").append(n.valorCbs()).append('.');
      if (n.motivoDevolucao() != null) {
        t.append(" Motivo da devolução: ").append(n.motivoDevolucao()).append('.');
        livres.add(n.motivoDevolucao());
      }
      if (!n.inconsistencias().isEmpty()) {
        t.append(" O que corrigir:");
        n.inconsistencias().stream().limit(MAXIMO_ITENS).forEach(i -> {
          t.append("\n- ").append(i.mensagem());
          livres.add(i.mensagem());
          if (i.sugestao() != null) {
            t.append(" (").append(i.sugestao()).append(')');
            livres.add(i.sugestao());
          }
        });
        maisNaTela(t, n.inconsistencias().size(),
            Math.min(MAXIMO_ITENS, n.inconsistencias().size()), "Notas");
      }
    }
    maisNaTela(t, c.encontradas(), c.notas().size(), "Notas");
    long paradas = c.notas().stream().filter(n -> SITUACOES_PARADAS.contains(n.situacao()))
        .count();
    if (paradas == 0) {
      return SecaoResposta.informativa(t.toString(), false, livres);
    }
    return new SecaoResposta(Assunto.NOTAS_PARADAS, t.toString(), (int) paradas,
        plural(paradas, "nota parada", "notas paradas"), null, false, true, livres);
  }

  /** {@code listarAlertas}. */
  static SecaoResposta alertas(Alertas a, LocalDate hoje) {
    String recorte = "prazos dos próximos dias";
    if (a.totalEncontrado() == 0) {
      return new SecaoResposta(Assunto.PRAZOS, fraseVazia("listarAlertas", Map.of(), hoje), 0,
          null, recorte, true, true, List.of());
    }
    List<String> livres = new ArrayList<>();
    StringBuilder t = new StringBuilder(a.totalEncontrado() == 1 ? "Há 1 prazo"
        : "Há " + a.totalEncontrado() + " prazos").append(" nos próximos dias:");
    a.alertas().stream().limit(MAXIMO_ITENS).forEach(alerta -> {
      t.append("\n- ").append(alerta.descricao()).append(": até ").append(alerta.prazo())
          .append(" (").append(quando(alerta.diasRestantes())).append(").");
      livres.add(alerta.descricao());
    });
    maisNaTela(t, a.totalEncontrado(), Math.min(MAXIMO_ITENS, a.alertas().size()), "Calendário");
    return new SecaoResposta(Assunto.PRAZOS, t.toString(), (int) a.totalEncontrado(),
        plural(a.totalEncontrado(), "prazo nos próximos dias", "prazos nos próximos dias"),
        recorte, false, true, livres);
  }

  /** Notas paradas do resumo do produtor. */
  static SecaoResposta notasParadas(List<RespostasFerramentas.Nota> notas, long total,
      String desde) {
    String recorte = "notas paradas (devolvidas, com inconsistências, aguardando aprovação ou"
        + " rejeitadas, desde " + desde + ")";
    if (total == 0) {
      return new SecaoResposta(Assunto.NOTAS_PARADAS, "Não encontrei " + recorte + ".", 0, null,
          recorte, true, false, List.of(desde));
    }
    StringBuilder t = new StringBuilder(total == 1 ? "1 nota está parada:"
        : total + " notas estão paradas:");
    notas.stream().limit(MAXIMO_ITENS).forEach(n -> t.append("\n- Nota ").append(n.numero())
        .append(" (série ").append(n.serie()).append("), de ").append(n.dataEmissao())
        .append(": ").append(SITUACAO.getOrDefault(n.situacao(), n.situacao().toLowerCase()))
        .append('.'));
    maisNaTela(t, total, Math.min(MAXIMO_ITENS, notas.size()), "Notas");
    return new SecaoResposta(Assunto.NOTAS_PARADAS, t.toString(), (int) total,
        plural(total, "nota parada", "notas paradas"), recorte, false, false, List.of(desde));
  }

  /**
   * Pedidos de documento do escritório ainda sem resposta (#180), do resumo do produtor.
   *
   * @param pedidos título (texto do escritório) e prazo formatado (nulo sem prazo)
   */
  static SecaoResposta pedidos(List<String[]> pedidos, long total) {
    String recorte = "pedidos do escritório";
    if (total == 0) {
      return new SecaoResposta(Assunto.PEDIDOS_DO_ESCRITORIO,
          "Não encontrei pedidos do escritório esperando documento seu.", 0, null, recorte, true,
          false, List.of());
    }
    List<String> livres = new ArrayList<>();
    StringBuilder t = new StringBuilder(total == 1
        ? "O escritório espera 1 documento seu:" : "O escritório espera " + total
        + " documentos seus:");
    pedidos.stream().limit(MAXIMO_ITENS).forEach(p -> {
      t.append("\n- ").append(p[0]);
      livres.add(p[0]);
      if (p[1] != null) {
        t.append(", até ").append(p[1]);
        livres.add(p[1]);
      }
      t.append('.');
    });
    maisNaTela(t, total, Math.min(MAXIMO_ITENS, pedidos.size()), "Pedidos");
    return new SecaoResposta(Assunto.PEDIDOS_DO_ESCRITORIO, t.toString(), (int) total,
        plural(total, "pedido do escritório", "pedidos do escritório"), recorte, false, false,
        livres);
  }

  /** Notas a conferir do resumo (#276): as frases fixas dos sinais, uma por nota. */
  static SecaoResposta notasAConferir(List<String> frases) {
    String recorte = "notas a conferir";
    if (frases.isEmpty()) {
      return new SecaoResposta(Assunto.NOTAS_A_CONFERIR,
          "Não encontrei pontos a conferir nas suas notas ainda não emitidas nem nas autorizadas"
              + " dentro do prazo de cancelamento.", 0, null, recorte, true, false, List.of());
    }
    String texto = String.join("\n\n", frases.stream().limit(MAXIMO_ITENS).toList());
    return new SecaoResposta(Assunto.NOTAS_A_CONFERIR, texto, frases.size(),
        plural(frases.size(), "nota a conferir", "notas a conferir"), recorte, false, false,
        frases);
  }

  private static void guias(StringBuilder t, List<Guia> guias, String verbo) {
    guias.stream().limit(MAXIMO_ITENS).forEach(g -> t.append("\n- ").append(g.tipo())
        .append(", ").append(verbo).append(' ').append(g.vencimento()).append(": ")
        .append(g.valor()).append('.'));
  }

  private static void maisNaTela(StringBuilder t, long total, int mostrados, String tela) {
    if (total > mostrados) {
      t.append("\nE mais ").append(total - mostrados).append(", na tela de ").append(tela)
          .append('.');
    }
  }

  private static String unidades(List<CompraPorUnidade> porUnidade) {
    if (porUnidade == null || porUnidade.isEmpty()) {
      return "";
    }
    List<String> partes = porUnidade.stream()
        .map(u -> u.quantidade() + " " + u.unidade() + " (" + u.valor() + ")").toList();
    return " Quantidade: " + String.join("; ", partes) + ".";
  }

  private static String produto(String termo, String criterio) {
    return "NCM".equals(criterio) ? "produtos do NCM " + termo : termo;
  }

  private static String notas(long quantidade, String complemento) {
    String sufixo = complemento.isEmpty() ? "" : " " + complemento;
    return quantidade == 1 ? "1 nota" + sufixo : quantidade + " notas" + sufixo;
  }

  static String plural(long quantidade, String singular, String plural) {
    return quantidade + " " + (quantidade == 1 ? singular : plural);
  }

  private static String quando(long dias) {
    if (dias <= 0) {
      return "hoje";
    }
    return dias == 1 ? "amanhã" : "em " + dias + " dias";
  }

  /** {@code 2026-08} vira {@code 08/2026}. */
  static String mes(String anoMes) {
    if (anoMes == null || !anoMes.matches("\\d{4}-\\d{2}")) {
      return anoMes;
    }
    return anoMes.substring(5) + "/" + anoMes.substring(0, 4);
  }
}
