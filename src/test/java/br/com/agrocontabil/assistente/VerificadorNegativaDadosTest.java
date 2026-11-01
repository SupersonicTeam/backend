package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Negativa absoluta sobre consulta vazia vira a frase com o recorte (issue #274, item 6). */
class VerificadorNegativaDadosTest {

  private static final LocalDate HOJE = LocalDate.of(2026, 10, 28);

  private final VerificadorNegativaDados verificador = new VerificadorNegativaDados();
  private ContextoAssistente contexto;

  @BeforeEach
  void setUp() {
    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
  }

  private void notasRejeitadasVazias() {
    contexto.registrarFonte("listarNotas", Map.of("situacao", "REJEITADA",
        "dataInicio", "2026-07-30", "dataFim", "2026-10-28", "limite", "5"), true);
  }

  @Test
  @DisplayName("Funcao vazia + 'nao tem' sem recorte: frase fixa com o periodo e o filtro usado")
  void trocaNegativaSemRecorte() {
    notasRejeitadasVazias();

    assertThat(verificador.substituir("Voce nao tem notas devolvidas.", contexto)).contains(
        "Nao encontrei notas rejeitadas pela SEFAZ entre 30/07/2026 e 28/10/2026.");
  }

  @Test
  @DisplayName("Funcao com resultado: o texto do modelo fica")
  void funcaoComResultadoPreserva() {
    contexto.registrarFonte("listarNotas", Map.of("situacao", "DEVOLVIDA",
        "dataInicio", "2026-07-30", "dataFim", "2026-10-28"), false);

    assertThat(verificador.substituir("Nao ha outras notas devolvidas.", contexto)).isEmpty();
  }

  @Test
  @DisplayName("Negativa que ja traz o recorte fica")
  void negativaComRecortePreserva() {
    notasRejeitadasVazias();

    assertThat(verificador.substituir(
        "Nao encontrei notas rejeitadas entre 30/07/2026 e 28/10/2026, entao nao ha nenhuma.",
        contexto)).isEmpty();
  }

  @Test
  @DisplayName("Parte vazia: so a frase negativa sai; o resto da resposta fica")
  void parcial() {
    contexto.registrarFonte("consultarCreditos",
        Map.of("dataInicio", "2026-01-01", "dataFim", "2026-08-31"), false);
    contexto.registrarFonte("guiasEmAtraso", Map.of(), true);

    assertThat(verificador.substituir(
        "Seu credito de CBS foi de R$ 1.234,00. Voce nao possui debito pendente.", contexto))
        .contains("Seu credito de CBS foi de R$ 1.234,00.\n\nNao encontrei guias em atraso (nao "
            + "pagas com vencimento ate 27/10/2026).");
  }

  @Test
  @DisplayName("Sem acento e sem caixa: 'NÃO HÁ', 'nenhuma'")
  void semAcentoNemCaixa() {
    contexto.registrarFonte("consultarNota", Map.of("numero", "118"), true);

    assertThat(verificador.substituir("NÃO HÁ essa nota.", contexto))
        .contains("Nao encontrei a nota 118 nas suas notas.");
    // O numero e o recorte: com ele, a negativa fica.
    assertThat(verificador.substituir("Nenhuma nota 118 foi encontrada.", contexto)).isEmpty();
  }

  @Test
  @DisplayName("Sem negativa, nada muda mesmo com funcao vazia")
  void semNegativa() {
    notasRejeitadasVazias();

    assertThat(verificador.substituir("Consultei suas notas do periodo.", contexto)).isEmpty();
  }

  @Test
  @DisplayName("Periodo certo e filtro errado (#254): 'devolvidas' sobre consulta de rejeitadas sai")
  void filtroFazParteDoRecorte() {
    notasRejeitadasVazias();

    assertThat(verificador.substituir(
        "Entre 30/07/2026 e 28/10/2026 voce nao tem notas devolvidas.", contexto))
        .contains("Nao encontrei notas rejeitadas pela SEFAZ entre 30/07/2026 e 28/10/2026.");
  }

  @Test
  @DisplayName("Nova tentativa que achou dados: a consulta vazia anterior nao gera frase")
  void novaTentativaComResultado() {
    contexto.registrarFonte("totalComprasPorInsumo", Map.of("descricaoOuNcm", "semente de soja",
        "dataInicio", "2026-01-01", "dataFim", "2026-08-31"), true);
    contexto.registrarFonte("totalComprasPorInsumo", Map.of("descricaoOuNcm", "1201",
        "dataInicio", "2026-01-01", "dataFim", "2026-08-31"), false);

    assertThat(verificador.substituir(
        "Voce comprou R$ 8.000,00 de semente (NCM 1201). Nao ha outras compras.", contexto))
        .isEmpty();
  }

  @Test
  @DisplayName("Numero casa inteiro: '15 dias' nao e o recorte de 5 dias")
  void numeroInteiro() {
    contexto.registrarFonte("proximosVencimentos", Map.of("dias", "5"), true);

    assertThat(verificador.substituir("Nao ha guias vencendo nos proximos 15 dias.", contexto))
        .contains("Nao encontrei guias a vencer de 28/10/2026 a 02/11/2026.");
    assertThat(verificador.substituir("Nao ha guias vencendo nos proximos 5 dias.", contexto))
        .isEmpty();
  }

  @Test
  @DisplayName("'Nao encontrei' e 'nao teve' tambem sao negativa absoluta")
  void outrasFormas() {
    notasRejeitadasVazias();

    assertThat(verificador.substituir("Nao encontrei notas devolvidas.", contexto)).isPresent();
    assertThat(verificador.substituir("Voce nao  teve notas devolvidas.", contexto)).isPresent();
  }

  @Test
  @DisplayName("A observacao da funcao ja repassada nao sai duplicada")
  void semDuplicar() {
    contexto.registrarFonte("guiasEmAtraso", Map.of(), true);

    assertThat(verificador.substituir("Nao encontrei guias em atraso (nao pagas com vencimento "
        + "ate 27/10/2026). Voce nao tem debito pendente.", contexto))
        .contains("Nao encontrei guias em atraso (nao pagas com vencimento ate 27/10/2026).");
  }

  @Test
  @DisplayName("Formatacao preservada: lista e abreviacao nao quebram a resposta")
  void formatacao() {
    contexto.registrarFonte("listarNotas", Map.of("situacao", "AUTORIZADA",
        "dataInicio", "2026-07-30", "dataFim", "2026-10-28"), false);
    contexto.registrarFonte("guiasEmAtraso", Map.of(), true);

    assertThat(verificador.substituir("Suas notas:\n1. Nota 113 de R$ 10,00\n2. Nota 114 de "
        + "R$ 20,00\nVeja a Lei 8.212/1991, art. 30, IV.\nVoce nao tem debito pendente.", contexto))
        .contains("Suas notas:\n1. Nota 113 de R$ 10,00\n2. Nota 114 de R$ 20,00\nVeja a Lei "
            + "8.212/1991, art. 30, IV.\n\nNao encontrei guias em atraso (nao pagas com "
            + "vencimento ate 27/10/2026).");
  }

  @Test
  @DisplayName("Mes por extenso conta como recorte; NCM sai como NCM na frase fixa")
  void mesPorExtensoENcm() {
    contexto.registrarFonte("vencimentosDoMes", Map.of("mes", "2026-12"), true);
    assertThat(verificador.substituir("Voce nao tem guias para pagar em dezembro de 2026.",
        contexto)).isEmpty();

    ContextoAssistente outro = new ContextoAssistente(UUID.randomUUID(), HOJE);
    outro.registrarFonte("totalComprasPorInsumo", Map.of("descricaoOuNcm", "3808",
        "dataInicio", "2026-01-01", "dataFim", "2026-08-31"), true);
    assertThat(verificador.substituir("Voce nao comprou nada; nao ha compras.", outro))
        .contains("Nao encontrei compras do NCM 3808 entre 01/01/2026 e 31/08/2026.");
  }
}
