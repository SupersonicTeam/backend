package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.orquestracao.FundamentoRetencao;
import br.com.agrocontabil.assistente.orquestracao.TextoReformaTributaria;
import br.com.agrocontabil.model.TipoProdutor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Os textos fixos da Ceres entregues ao produtor levam acento (issue #357): sem ele o "é" vira "e"
 * e a frase muda de sentido ("A CBS é a contribuição..." lido como "A CBS e a contribuição...").
 *
 * <p>Varre cada texto atrás de palavras comuns que se escrevem com acento. A lista é de propósito
 * curta e sem ambiguidade (palavra que também existe sem acento, como "tem" e "para", fica de
 * fora), e as exceções, se houver, vão em {@link #EXCECOES} com o motivo. Quem acrescenta um grupo
 * de textos fixos (templates de dados, fallback) o acrescenta em {@link #textosFixos()}.
 *
 * <p>A normalização (sem acento nem caixa) é só da <strong>comparação</strong> dos validadores,
 * nunca do texto entregue.
 */
class TextosFixosAcentuadosTest {

  /** Palavras que, sem acento, são erro de escrita no texto entregue. */
  private static final List<String> PALAVRAS = List.of(
      "nao", "voce", "voces", "sao", "tambem", "alem", "apos", "ate", "ja", "so",
      "informacao", "informacoes", "contribuicao", "contribuicoes", "operacao", "operacoes",
      "aliquota", "aliquotas", "reducao", "legislacao", "obrigacao", "obrigacoes", "acessoria",
      "acessorias", "calendario", "proximo", "proxima", "proximos", "escritorio", "escritorios",
      "fisica", "juridica", "credito", "creditos", "eletronico", "eletronica", "uniao",
      "municipio", "municipios", "competencia", "referencia", "periodo", "periodos", "retencao",
      "retencoes", "declaracao", "situacao", "emissao", "apuracao", "tributario", "tributaria",
      "numero", "codigo", "pagina", "unico", "unica", "agropecuario", "agropecuaria", "basica",
      "horticolas", "ministerio", "pecuaria", "instituicao", "liquidacao",
      "previsao", "preve", "conferencia", "orientacao", "relatorio",
      "variacao", "disponivel", "possivel", "visualizacao", "notificacao", "pendencia",
      "pendencias", "excecao", "excecoes", "inscricao", "tecnico", "juridico");

  /** Onde o "é" verbo vira "e": "isso e", "quem e", "o que e"... */
  private static final Pattern E_VERBO = Pattern.compile(
      "(?iu)(?<![\\p{L}])(isso|isto|aquilo|ele|ela|tudo|quem|onde|qual|que|cada) e(?![\\p{L}])"
          + "(?! (a|o|as|os) )");

  /** Trechos que ficam sem acento de propósito, com o motivo (hoje, nenhum). */
  private static final List<String> EXCECOES = List.of();

  private static final Pattern SEM_ACENTO = Pattern.compile(
      "(?iu)(?<![\\p{L}\\p{N}_-])(" + String.join("|", PALAVRAS) + ")(?![\\p{L}\\p{N}_-])");

  /** Os textos fixos entregues ao produtor, por grupo. */
  private static Map<String, String> textosFixos() {
    Map<String, String> textos = new TreeMap<>();
    List<TextoReformaTributaria.Perfil> perfis = new ArrayList<>();
    perfis.add(TextoReformaTributaria.Perfil.DESCONHECIDO);
    for (TipoProdutor tipo : TipoProdutor.values()) {
      perfis.add(new TextoReformaTributaria.Perfil(tipo, true, List.of()));
      perfis.add(new TextoReformaTributaria.Perfil(tipo, false, List.of()));
    }
    for (int i = 0; i < perfis.size(); i++) {
      textos.put("atalho IBS/CBS #" + i, TextoReformaTributaria.montar(perfis.get(i)).texto());
    }
    textos.put("fundamento das retenções", FundamentoRetencao.TEXTO);
    return textos;
  }

  /** Palavras sem acento achadas no texto, fora das exceções. */
  static List<String> semAcento(String texto) {
    String limpo = texto;
    for (String excecao : EXCECOES) {
      limpo = limpo.replace(excecao, "");
    }
    List<String> achadas = new ArrayList<>();
    Matcher m = SEM_ACENTO.matcher(limpo);
    while (m.find()) {
      achadas.add(m.group());
    }
    Matcher e = E_VERBO.matcher(limpo);
    while (e.find()) {
      achadas.add(e.group());
    }
    return achadas;
  }

  @Test
  @DisplayName("#357: nenhum texto fixo entregue ao produtor traz palavra comum sem acento")
  void textosFixosComAcento() {
    textosFixos().forEach((nome, texto) ->
        assertThat(semAcento(texto)).as("texto fixo '%s'", nome).isEmpty());
  }

  @Test
  @DisplayName("#357: a varredura acusa o texto sem acento (o teste não passa por não achar nada)")
  void varreduraAcusa() {
    assertThat(semAcento("A CBS e a contribuicao da Uniao. Voce nao paga; isso e com o contador."))
        .contains("contribuicao", "Uniao", "Voce", "nao", "isso e");
    assertThat(semAcento("A CBS é a contribuição da União. Você não paga; isso é com o contador."))
        .isEmpty();
    // Palavra que existe sem acento no português corrente não é acusada.
    assertThat(semAcento("O comprador tem 10 dias para pagar a nota fiscal.")).isEmpty();
  }

  @Test
  @DisplayName("#357: os acentos saem corretos em UTF-8 (JSON e SSE da Ceres)")
  void utf8() throws Exception {
    String texto = TextoReformaTributaria.montar(TextoReformaTributaria.Perfil.DESCONHECIDO)
        .texto();
    byte[] bytes = texto.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(texto);
    assertThat(texto).contains("é a contribuição da União");
  }
}
