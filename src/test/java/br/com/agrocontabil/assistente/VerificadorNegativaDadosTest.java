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
        .contains("Seu credito de CBS foi de R$ 1.234,00. Nao encontrei guias em atraso (nao "
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
}
