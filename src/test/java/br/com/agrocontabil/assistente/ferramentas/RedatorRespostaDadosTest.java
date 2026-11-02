package br.com.agrocontabil.assistente.ferramentas;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.SecaoResposta;
import br.com.agrocontabil.assistente.SecaoResposta.Assunto;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Templates da rota de dados (issue #266): valores, vazio, recorte, plural e singular, teto. */
class RedatorRespostaDadosTest {

  private static final LocalDate HOJE = LocalDate.of(2026, 11, 2);
  private static final Map<String, String> PERIODO =
      Map.of("dataInicio", "2026-09-01", "dataFim", "2026-09-30");

  @Test
  @DisplayName("compras: valor, recorte, notas no plural e quantidade por unidade")
  void compras() {
    SecaoResposta s = RedatorRespostaDados.compras(new ComprasInsumo("adubo npk", "DESCRICAO",
        "01/09/2026", "30/09/2026", "R$ 4.320,00", 3,
        List.of(new CompraPorUnidade("KG", "1.500", "R$ 4.320,00")), null),
        PERIODO, HOJE);

    assertThat(s.texto()).isEqualTo("Entre 01/09/2026 e 30/09/2026 voce comprou R$ 4.320,00 de"
        + " adubo npk, em 3 notas de compra. Quantidade: 1.500 KG (R$ 4.320,00).");
    assertThat(s.assunto()).isEqualTo(Assunto.INFORMATIVO);
    assertThat(s.vazia()).isFalse();
  }

  @Test
  @DisplayName("compras vazia: a frase fixa com o recorte, nunca negativa absoluta")
  void comprasVazia() {
    SecaoResposta s = RedatorRespostaDados.compras(new ComprasInsumo("glifosato", "DESCRICAO",
        "01/09/2026", "30/09/2026", "R$ 0,00", 0, List.of(), "obs"),
        Map.of("descricaoOuNcm", "glifosato", "dataInicio", "2026-09-01",
            "dataFim", "2026-09-30"), HOJE);

    assertThat(s.texto())
        .isEqualTo("Nao encontrei compras de glifosato entre 01/09/2026 e 30/09/2026.");
    assertThat(s.vazia()).isTrue();
  }

  @Test
  @DisplayName("vendas: singular e o NCM dito como codigo")
  void vendasSingularPorNcm() {
    SecaoResposta s = RedatorRespostaDados.vendas(new VendasProduto("12019000", "NCM",
        "01/09/2026", "30/09/2026", "R$ 900,00", 1, List.of(), null), PERIODO, HOJE);

    assertThat(s.texto()).isEqualTo("Entre 01/09/2026 e 30/09/2026 voce vendeu R$ 900,00 de"
        + " produtos do NCM 12019000, em 1 nota de venda autorizada.");
  }

  @Test
  @DisplayName("apuracao aberta: imposto a pagar, debito, credito e o aviso de que pode mudar")
  void apuracaoAberta() {
    SecaoResposta s = RedatorRespostaDados.apuracao(new Apuracao("2026-08", "ABERTO",
        "R$ 150,00", "R$ 1.350,00", "R$ 30,00", "R$ 270,00", "R$ 120,00", "R$ 1.080,00", 1),
        Map.of("mes", "2026-08"), HOJE);

    assertThat(s.texto()).isEqualTo("Na apuracao de 08/2026 (ainda aberta: o valor pode mudar"
        + " ate o fechamento), o imposto a pagar e de R$ 120,00 de IBS e R$ 1.080,00 de CBS."
        + " Imposto das suas vendas: R$ 150,00 de IBS e R$ 1.350,00 de CBS. Credito das suas"
        + " compras: R$ 30,00 de IBS e R$ 270,00 de CBS. Entraram 1 nota.");
  }

  @Test
  @DisplayName("apuracao fechada com saldo negativo: credito a favor, nunca 'a pagar'")
  void apuracaoSaldoNegativo() {
    SecaoResposta s = RedatorRespostaDados.apuracao(new Apuracao("2026-07", "FECHADO",
        "R$ 10,00", "R$ 90,00", "R$ 30,00", "R$ 270,00", "R$ -20,00", "R$ -180,00", 4),
        Map.of("mes", "2026-07"), HOJE);

    assertThat(s.texto()).startsWith("Na apuracao de 07/2026 (fechada), o saldo e de R$ -20,00"
        + " de IBS e R$ -180,00 de CBS (valor negativo e credito a seu favor).")
        .doesNotContain("a pagar");
  }

  @Test
  @DisplayName("apuracao sem nota: a frase fixa do mes")
  void apuracaoVazia() {
    SecaoResposta s = RedatorRespostaDados.apuracao(new Apuracao("2026-05", "ABERTO",
        "R$ 0,00", "R$ 0,00", "R$ 0,00", "R$ 0,00", "R$ 0,00", "R$ 0,00", 0),
        Map.of("mes", "2026-05"), HOJE);

    assertThat(s.texto()).isEqualTo("Nao encontrei notas na apuracao de 05/2026.");
  }

  @Test
  @DisplayName("creditos com o recorte")
  void creditos() {
    SecaoResposta s = RedatorRespostaDados.creditos(new Creditos("01/09/2026", "30/09/2026",
        "R$ 30,00", "R$ 270,00", "R$ 300,00", 2), PERIODO, HOJE);

    assertThat(s.texto()).isEqualTo("Entre 01/09/2026 e 30/09/2026 suas compras geraram"
        + " R$ 300,00 de credito: R$ 30,00 de IBS e R$ 270,00 de CBS, em 2 notas de compra.");
  }

  @Test
  @DisplayName("retencoes: as linhas so do que existe, o split payment e os avisos")
  void retencoes() {
    Funrural nada = new Funrural(0, "R$ 0,00", "nao se aplica", "R$ 0,00", "R$ 0,00",
        "R$ 0,00", "R$ 0,00");
    SecaoResposta s = RedatorRespostaDados.retencoes(new Retencoes("01/09/2026", "30/09/2026",
        3, new Funrural(2, "R$ 10.000,00", "1,5%", "R$ 120,00", "R$ 10,00", "R$ 20,00",
            "R$ 150,00"), nada, nada, "R$ 10,00", "R$ 90,00", "R$ 100,00",
        List.of("Aviso fixo.")), PERIODO, HOJE);

    assertThat(s.texto()).isEqualTo("Entre 01/09/2026 e 30/09/2026, nas suas 3 vendas"
        + " autorizadas:\n- Funrural retido pelo comprador: R$ 150,00, em 2 vendas a empresa ou"
        + " cooperativa.\n- Split payment: R$ 100,00 (R$ 10,00 de IBS e R$ 90,00 de CBS) ficam"
        + " separados no pagamento das vendas.\nAviso fixo.");
  }

  @Test
  @DisplayName("notas: total do filtro, soma das autorizadas, lista e 'e mais N' na tela")
  void notasComTeto() {
    List<Nota> notas = IntStream.rangeClosed(1, 7)
        .mapToObj(i -> new Nota(100 + i, 1, "0" + i + "/09/2026", "SAIDA", "AUTORIZADA",
            "R$ 1.000,00", "R$ 1,00", "R$ 9,00"))
        .toList();
    SecaoResposta s = RedatorRespostaDados.notas(new Notas("01/09/2026", "30/09/2026", 12,
        "R$ 12.000,00", 12, "autorizadas", notas), Map.of("tipo", "SAIDA"), HOJE);

    assertThat(s.texto()).startsWith("Entre 01/09/2026 e 30/09/2026 encontrei 12 notas de"
        + " venda. Somando as autorizadas: R$ 12.000,00, em 12 notas.\n- Nota 101 (serie 1),"
        + " venda de 01/09/2026: R$ 1.000,00, autorizada.")
        .endsWith("\nE mais 7, na tela de Notas.");
    assertThat(s.texto().lines().filter(l -> l.startsWith("- Nota"))).hasSize(5);
    assertThat(s.assunto()).isEqualTo(Assunto.INFORMATIVO);
  }

  @Test
  @DisplayName("notas devolvidas: secao de nota parada, com pontos e resumo")
  void notasDevolvidas() {
    SecaoResposta s = RedatorRespostaDados.notas(new Notas("04/11/2024", "02/11/2026", 1,
        "R$ 500,00", 1, "filtro", List.of(new Nota(118, 1, "28/10/2026", "SAIDA", "DEVOLVIDA",
            "R$ 500,00", "R$ 0,50", "R$ 4,50"))), Map.of("situacao", "DEVOLVIDA"), HOJE);

    assertThat(s.assunto()).isEqualTo(Assunto.NOTAS_PARADAS);
    assertThat(s.pontos()).isEqualTo(1);
    assertThat(s.resumo()).isEqualTo("1 nota devolvida pelo contador");
    assertThat(s.texto()).contains("encontrei 1 nota devolvida pelo contador. Somando todas:");
  }

  @Test
  @DisplayName("proximos vencimentos: janela, soma, singular e secao de prazo")
  void proximosVencimentos() {
    SecaoResposta s = RedatorRespostaDados.proximosVencimentos(new Vencimentos("02/11/2026",
        "02/12/2026", 1, "R$ 310,00", List.of(new Guia("ICMS", "20/11/2026", "R$ 310,00",
            "PENDENTE"))), Map.of("dias", "30"), HOJE);

    assertThat(s.texto()).isEqualTo("De 02/11/2026 a 02/12/2026 vence 1 guia nao paga, somando"
        + " R$ 310,00:\n- ICMS, vence em 20/11/2026: R$ 310,00.");
    assertThat(s.assunto()).isEqualTo(Assunto.PRAZOS);
    assertThat(s.resumo()).isEqualTo("1 guia a vencer ate 02/12/2026");
  }

  @Test
  @DisplayName("guias em atraso vazia: a frase com a data de corte e a proxima guia")
  void semAtrasoComProxima() {
    SecaoResposta s = RedatorRespostaDados.guiasEmAtraso(new GuiasEmAtraso(
        new GuiasDoRecorte(null, "01/11/2026", 0, "R$ 0,00", List.of()),
        new Guia("FUNRURAL", "20/11/2026", "R$ 430,00", "PENDENTE"),
        "Nao encontrei guias em atraso (nao pagas com vencimento ate 01/11/2026)."), HOJE);

    assertThat(s.texto()).isEqualTo("Nao encontrei guias em atraso (nao pagas com vencimento ate"
        + " 01/11/2026). A proxima guia a vencer e FUNRURAL, em 20/11/2026, de R$ 430,00.");
    assertThat(s.vazia()).isTrue();
    assertThat(s.semPendencia()).isEqualTo("guias em atraso (vencimento ate 01/11/2026)");
  }

  @Test
  @DisplayName("vencimentos do mes: total, os dois subtotais e as listas")
  void vencimentosDoMes() {
    SecaoResposta s = RedatorRespostaDados.vencimentosDoMes(new VencimentosDoMes("2026-11",
        new GuiasDoRecorte(null, "01/11/2026", 1, "R$ 430,00",
            List.of(new Guia("FUNRURAL", "20/10/2026", "R$ 430,00", "VENCIDA"))),
        new GuiasDoRecorte("02/11/2026", "30/11/2026", 1, "R$ 310,00",
            List.of(new Guia("ICMS", "20/11/2026", "R$ 310,00", "PENDENTE"))),
        2, "R$ 740,00", null), HOJE);

    assertThat(s.texto()).isEqualTo("Em 11/2026: 2 guias para pagar, somando R$ 740,00.\nEm"
        + " atraso: 1 guia, R$ 430,00:\n- FUNRURAL, venceu em 20/10/2026: R$ 430,00.\nA vencer"
        + " de 02/11/2026 a 30/11/2026: 1 guia, R$ 310,00:\n- ICMS, vence em 20/11/2026:"
        + " R$ 310,00.");
  }

  @Test
  @DisplayName("nota devolvida: situacao, explicacao, valores, motivo e o que corrigir")
  void notaDevolvida() {
    SecaoResposta s = RedatorRespostaDados.nota(new NotaConsultada(118, 1, List.of(
        new NotaDetalhada(118, 1, "28/10/2026", "SAIDA", "DEVOLVIDA", "Devolvida pelo contador.",
            "R$ 500,00", "R$ 0,50", "R$ 4,50", "Peso divergente do romaneio",
            List.of(new Inconsistencia("X", "peso", "Peso bruto menor que o liquido",
                "confira o romaneio")), List.of())), null), HOJE);

    assertThat(s.texto()).isEqualTo("A nota 118 (serie 1), de venda emitida em 28/10/2026, esta"
        + " devolvida pelo contador. Devolvida pelo contador. Valor: R$ 500,00; IBS: R$ 0,50;"
        + " CBS: R$ 4,50. Motivo da devolucao: Peso divergente do romaneio. O que corrigir:\n-"
        + " Peso bruto menor que o liquido (confira o romaneio)");
    assertThat(s.assunto()).isEqualTo(Assunto.NOTAS_PARADAS);
    // O motivo e a inconsistencia sao texto livre: entram nas fontes do validador.
    assertThat(s.textosLivres()).contains("Peso divergente do romaneio",
        "Peso bruto menor que o liquido", "confira o romaneio");
  }

  @Test
  @DisplayName("alertas: prazo em dias, hoje e amanha")
  void alertas() {
    SecaoResposta s = RedatorRespostaDados.alertas(new Alertas(2, List.of(
        new Alerta("VENCIMENTO_GUIA", "Guia de ICMS", "02/11/2026", 0),
        new Alerta("FECHAMENTO_APURACAO", "Fechamento de 10/2026", "03/11/2026", 1))), HOJE);

    assertThat(s.texto()).isEqualTo("Ha 2 prazos nos proximos dias:\n- Guia de ICMS: ate"
        + " 02/11/2026 (hoje).\n- Fechamento de 10/2026: ate 03/11/2026 (amanha).");
  }

  @Test
  @DisplayName("pedidos do escritorio: titulo como texto livre, prazo opcional, nao pedida")
  void pedidos() {
    SecaoResposta s = RedatorRespostaDados.pedidos(List.<String[]>of(
        new String[] {"Extrato de 2026-10", "07/11/2026"}, new String[] {"Contrato", null}), 2);

    assertThat(s.texto()).isEqualTo("O escritorio espera 2 documentos seus:\n- Extrato de"
        + " 2026-10, ate 07/11/2026.\n- Contrato.");
    assertThat(s.pedida()).isFalse();
    assertThat(s.textosLivres()).contains("Extrato de 2026-10");
  }
}
