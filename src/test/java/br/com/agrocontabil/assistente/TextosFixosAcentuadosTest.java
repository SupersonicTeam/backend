package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.SecaoResposta.Assunto;
import br.com.agrocontabil.assistente.fallback.ModoFallback;
import br.com.agrocontabil.assistente.ferramentas.TextosDosTemplates;
import br.com.agrocontabil.assistente.orquestracao.FundamentoRetencao;
import br.com.agrocontabil.assistente.orquestracao.OrquestradorService;
import br.com.agrocontabil.assistente.orquestracao.TextoReformaTributaria;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.exception.AssistenteOcupadoException;
import br.com.agrocontabil.exception.AssistenteTempoEsgotadoException;
import br.com.agrocontabil.exception.DomainException;
import br.com.agrocontabil.exception.LimiteUsoAssistenteException;
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
public class TextosFixosAcentuadosTest {

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
      "pendencias", "excecao", "excecoes", "inscricao", "tecnico", "juridico",
      // Textos da rota de dados (PR 2).
      "especifica", "devolucao", "aprovacao", "inconsistencia", "inconsistencias",
      "exportacao", "atencao", "servico", "servicos", "destinatario", "serie", "salarios",
      "reproducao", "calculo", "retem", "producao", "padrao", "condicao", "situacoes",
      "grao", "horario", "brasilia", "amanha", "saida", "mes", "rejeicao", "revisao",
      "correcao", "validacao", "previdenciaria", "mostra-los");

  /** Onde o "é" verbo vira "e": "isso e", "quem e", "o que e"... */
  private static final Pattern E_VERBO = Pattern.compile(
      "(?iu)(?<![\\p{L}])(isso|isto|aquilo|ele|ela|tudo|quem|onde|qual|que|cada) e(?![\\p{L}])"
          + "(?! (a|o|as|os) )");

  /**
   * Construções sem acento (verbo "estar", "é", "até", crase) que a lista de palavras não pega
   * porque a forma sem acento também existe ("esta", "e", "as").
   */
  private static final Pattern CONSTRUCOES = Pattern.compile("(?iu)(?<![\\p{L}])("
      + "ate (\\d|o |a |que )|as \\d{1,2}:\\d{2}|(enviad[oa]s?|ainda nao enviad[oa]s?) a sefaz|"
      + "esta (bem|sem|na tabela|parada|devolvida|aprovada|rejeitada|autorizada)|estao |"
      + "(saldo|nota|guia|prazo|item|valor|oficial|credito) e (de |imune|barrada)|"
      + "e barrada|e imune|e com o |nao e )");

  /** Trechos que ficam sem acento de propósito, com o motivo (hoje, nenhum). */
  private static final List<String> EXCECOES = List.of();

  private static final Pattern SEM_ACENTO = Pattern.compile(
      "(?iu)(?<![\\p{L}\\p{N}_])(" + String.join("|", PALAVRAS) + ")(?![\\p{L}\\p{N}_])");

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
    // Rota de dados (PR 2): templates, frases fixas das consultas vazias, sinais de nota,
    // tratamento do produto e as constantes das funções que chegam à resposta.
    textos.putAll(TextosDosTemplates.todos());
    textos.put("RotaDados.MENSAGEM_SEM_CONCLUSAO", RotaDados.MENSAGEM_SEM_CONCLUSAO);
    textos.put("RotaDados.MENSAGEM_SEM_FUNCAO", RotaDados.MENSAGEM_SEM_FUNCAO);
    textos.put("RotaDados.MENSAGEM_NUMERO_SEM_FONTE", RotaDados.MENSAGEM_NUMERO_SEM_FONTE);
    textos.putAll(composicaoDeDados());
    // Fallback e mensagens fixas das rotas (PR 3).
    textos.put("ModoFallback.SEM_MODELO_DADOS", ModoFallback.SEM_MODELO_DADOS);
    textos.put("ModoFallback.SEM_MODELO_LEI", ModoFallback.SEM_MODELO_LEI);
    textos.put("ModoFallback.SEM_MODELO_SEM_TRECHO", ModoFallback.SEM_MODELO_SEM_TRECHO);
    textos.put("RotaLegislacao.SO_TRECHOS", RotaLegislacao.SO_TRECHOS);
    textos.put("RotaLegislacao.TRECHOS_SEM_CONFIRMACAO", RotaLegislacao.TRECHOS_SEM_CONFIRMACAO);
    textos.put("OrquestradorService.LIGACAO_REGRA", OrquestradorService.LIGACAO_REGRA);
    textos.put("OrquestradorService.LIGACAO_SEM_DADOS", OrquestradorService.LIGACAO_SEM_DADOS);
    textos.put("OrquestradorService.SEM_DADOS", OrquestradorService.SEM_DADOS);
    textos.put("OrquestradorService.SEM_FUNDAMENTO_PARTE_LEGAL",
        OrquestradorService.SEM_FUNDAMENTO_PARTE_LEGAL);
    textos.put("PromptsCeres.FORA_DE_ESCOPO", PromptsCeres.FORA_DE_ESCOPO);
    textos.put("ValidadorCitacao.SEM_FUNDAMENTO", ValidadorCitacao.SEM_FUNDAMENTO);
    // Textos de erro que o produtor lê (ErrorDetail.message dos erros da Ceres).
    for (DomainException erro : List.<DomainException>of(
        new AssistenteIndisponivelException("falha tecnica", null),
        AssistenteOcupadoException.limiteGlobal(), AssistenteOcupadoException.perguntaEmAndamento(),
        new AssistenteTempoEsgotadoException(), new LimiteUsoAssistenteException(60))) {
      erro.getErrors().forEach(d -> textos.put(
          "erro " + d.code() + " " + textos.size(), d.message()));
    }
    textos.put("LimiteUsoAssistenteException.MENSAGEM_CLIENTE",
        LimiteUsoAssistenteException.MENSAGEM_CLIENTE);
    return textos;
  }

  /** Linha de resumo e siglas explicadas da composição da resposta de dados. */
  private static Map<String, String> composicaoDeDados() {
    Map<String, String> textos = new TreeMap<>();
    textos.put("siglas explicadas", ComposicaoRespostaDados.compor(List.of(
        SecaoResposta.informativa("IBS, CBS, Funrural, SEFAZ, NCM, CFOP e NF-e.", false,
            List.of()))));
    SecaoResposta atraso = new SecaoResposta(Assunto.GUIAS_EM_ATRASO, "Atraso.", 2,
        "2 guias em atraso", "guias em atraso (vencimento até 01/11/2026)", false, false,
        List.of());
    SecaoResposta semPrazo = new SecaoResposta(Assunto.PRAZOS, "Sem prazo.", 0, null,
        "prazos dos próximos dias", true, false, List.of());
    SecaoResposta semNota = new SecaoResposta(Assunto.NOTAS_PARADAS, "Sem nota.", 0, null,
        "notas paradas", true, false, List.of());
    textos.put("resumo com pontos e sem pendência",
        ComposicaoRespostaDados.compor(List.of(atraso, semPrazo)));
    textos.put("resumo com um ponto", ComposicaoRespostaDados.compor(List.of(
        new SecaoResposta(Assunto.GUIAS_EM_ATRASO, "Atraso.", 1, "1 guia em atraso",
            "guias em atraso", false, false, List.of()), semPrazo)));
    textos.put("resumo sem nenhum ponto",
        ComposicaoRespostaDados.compor(List.of(semPrazo, semNota)));
    return textos;
  }

  /** Palavras sem acento achadas no texto, fora das exceções. */
  public static List<String> semAcento(String texto) {
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
    Matcher c = CONSTRUCOES.matcher(limpo);
    while (c.find()) {
      achadas.add(c.group());
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
  @DisplayName("#357: a varredura cobre cada grupo de textos (não passa por varrer pouco)")
  void varreduraCobreOsGrupos() {
    Map<String, String> textos = textosFixos();
    // Atalhos (PR 1) e rota de dados (PR 2): os grupos têm que estar na varredura.
    assertThat(textos.keySet()).anyMatch(n -> n.startsWith("atalho IBS/CBS"))
        .anyMatch(n -> n.startsWith("compras"))
        .anyMatch(n -> n.startsWith("apuração"))
        .anyMatch(n -> n.startsWith("retenções"))
        .anyMatch(n -> n.startsWith("notas AUTORIZADA"))
        .anyMatch(n -> n.startsWith("guias em atraso"))
        .anyMatch(n -> n.startsWith("vencimentos do mês"))
        .anyMatch(n -> n.startsWith("frase fixa"))
        .anyMatch(n -> n.startsWith("sinal ICMS_A_DEFINIR"))
        .anyMatch(n -> n.startsWith("tratamento: leite"))
        .anyMatch(n -> n.startsWith("EXPLICACAO_SITUACAO"))
        .anyMatch(n -> n.startsWith("resumo"))
        .anyMatch(n -> n.startsWith("erro ASSISTENTE_OCUPADO"))
        .contains("RotaDados.MENSAGEM_SEM_FUNCAO", "siglas explicadas",
            "ModoFallback.SEM_MODELO_LEI", "PromptsCeres.FORA_DE_ESCOPO");
    assertThat(textos.size()).isGreaterThan(150);
    // Os textos não são vazios: um template que devolve vazio não prova nada.
    textos.forEach((nome, texto) -> assertThat(texto).as(nome).isNotBlank());
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
  @DisplayName("#357: a varredura acusa o verbo 'estar', o 'é' em frase de dado, o 'até' e a crase")
  void varreduraAcusaConstrucoes() {
    assertThat(semAcento("A nota 118 esta devolvida pelo contador.")).isNotEmpty();
    assertThat(semAcento("O preço esta bem acima do que você cobra.")).isNotEmpty();
    assertThat(semAcento("O saldo e de R$ 10,00 de IBS.")).isNotEmpty();
    assertThat(semAcento("Pode cancelar ate 26/11/2026, as 10:00.")).isNotEmpty();
    assertThat(semAcento("Sendo enviada a SEFAZ neste momento.")).isNotEmpty();
    assertThat(semAcento("Sem o CST a emissão e barrada.")).isNotEmpty();
    assertThat(semAcento("A nota 118 está devolvida. O saldo é de R$ 10,00. Até 26/11/2026, às"
        + " 10:00. Sendo enviada à SEFAZ. Esta nota é de venda.")).isEmpty();
  }

  @Test
  @DisplayName("#357: o fonte é compilado em UTF-8: o texto traz os caracteres, não mojibake")
  void fonteEmUtf8() {
    String texto = TextoReformaTributaria.montar(TextoReformaTributaria.Perfil.DESCONHECIDO)
        .texto();
    // Escapes Unicode: se o javac lesse o fonte em cp1252, "é" viraria dois caracteres.
    assertThat(texto).contains("é a contribuição da União");
    // O mesmo para os textos da rota de dados.
    assertThat(RotaDados.MENSAGEM_SEM_CONCLUSAO)
        .startsWith("Não consegui concluir a consulta.")
        .endsWith("mais específica.");
  }

  @Test
  @DisplayName("#357: palavra sem acento colada a hífen também é acusada")
  void hifen() {
    assertThat(semAcento("no ano-calendario ou sem-credito")).contains("calendario", "credito");
  }
}
