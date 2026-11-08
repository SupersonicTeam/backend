package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.ContextoAssistente.TextoDoBackend;
import br.com.agrocontabil.assistente.FonteDado;
import br.com.agrocontabil.assistente.RotaDados;
import br.com.agrocontabil.assistente.RotaLegislacao;
import br.com.agrocontabil.assistente.fallback.ModoFallback;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Põe na resposta os textos que o backend montou a partir do dado (issue #283): hoje, o tratamento
 * de um produto pela tabela oficial ({@code consultarTratamentoProduto}). O texto e a citação vêm
 * da tabela, nunca do modelo — mesmo padrão do {@link FundamentoRetencao}.
 *
 * <ul>
 *   <li>Rota de dados em que o tratamento foi a única consulta: a resposta é o texto do backend,
 *       e o do modelo sai (ele só repetiria, com risco de trocar uma condição por afirmação). O
 *       desfecho vira {@code RESPONDIDA} só quando o problema era o texto do modelo (número sem
 *       fonte, resposta vazia, negativa trocada); sinais como função não permitida, teto de rodadas
 *       e fallback ficam na auditoria.</li>
 *   <li>Rota de lei (#443): o texto só existe quando a pergunta era o tratamento de um produto e
 *       a tabela o achou ({@link TratamentoNaPergunta}); a tabela responde no lugar do
 *       texto e das citações do RAG.</li>
 *   <li>Com outras consultas (ou no MISTO): o texto do backend vem depois do que a rota
 *       respondeu, sem repetir um texto que já está lá (a frase fixa da consulta vazia é a mesma da
 *       negativa e do MISTO sem dados) e sem a recusa "não encontrei fundamento legal" ao lado de um
 *       artigo citado.</li>
 * </ul>
 * As citações são os dispositivos do dado (o artigo do {@code cClassTrib}), com o texto da base de
 * legislação; sem a base, o texto segue sozinho (ele já nomeia o artigo).
 */
@Component
public class TextosDoBackend {

  /** Funções cujo resultado vira texto do backend. */
  static final Set<String> FUNCOES = Set.of(RotaDados.FUNCAO_TRATAMENTO);

  /** Desfechos de texto do modelo que o texto do backend resolve. */
  static final Set<DesfechoInteracao> SUBSTITUIVEIS = EnumSet.of(DesfechoInteracao.RESPONDIDA,
      DesfechoInteracao.NUMERO_SEM_FONTE, DesfechoInteracao.SEM_RESPOSTA,
      DesfechoInteracao.NEGATIVA_SUBSTITUIDA);

  /** Recusas da lei que contradiriam um artigo citado logo depois. */
  private static final List<String> RECUSAS_DA_LEI = List.of(
      OrquestradorService.SEM_FUNDAMENTO_PARTE_LEGAL, ValidadorCitacao.SEM_FUNDAMENTO,
      ModoFallback.SEM_MODELO_SEM_TRECHO);

  private final BuscaTextualLegislacaoService busca;

  /** Constrói sobre a busca por dispositivo exato. */
  public TextosDoBackend(BuscaTextualLegislacaoService busca) {
    this.busca = busca;
  }

  /**
   * Aplica os textos do backend registrados na pergunta.
   *
   * @param resultado resposta já validada pela rota
   * @param contexto  contexto da pergunta
   * @return a resposta com os textos e as citações, ou a mesma resposta
   */
  public ResultadoOrquestracao aplicar(ResultadoOrquestracao resultado,
      ContextoAssistente contexto) {
    List<TextoDoBackend> textos = contexto.textosDoBackend();
    if (textos.isEmpty() || resultado.rota() == Intencao.FORA_DE_ESCOPO) {
      return resultado;
    }
    // Na lei, o texto do backend so existe se a pergunta era o tratamento de um produto achado na
    // tabela (#443): a tabela responde, e o texto do RAG sai junto com as citacoes dele (podia
    // afirmar outro percentual para o mesmo produto, sem a ressalva).
    boolean substitui = resultado.rota() == Intencao.LEGISLACAO
        || resultado.rota() == Intencao.DADOS
            && contexto.fontes().stream().map(FonteDado::funcao).allMatch(FUNCOES::contains);
    String daRota = resultado.texto();
    List<TextoDoBackend> novos = textos.stream()
        .filter(t -> substitui || !daRota.contains(t.texto()))
        .distinct()
        .toList();
    if (novos.isEmpty()) {
      return resultado;
    }
    Set<String> dispositivos = new LinkedHashSet<>();
    novos.forEach(t -> dispositivos.addAll(t.dispositivos()));
    String doBackend = novos.stream().map(TextoDoBackend::texto).distinct()
        .collect(Collectors.joining("\n\n"));
    boolean relacionados = comTrechosRelacionados(daRota, contexto);
    String base = dispositivos.isEmpty() ? daRota : semRecusaDaLei(daRota, relacionados);
    String texto = substitui || base.isBlank() ? doBackend : base + "\n\n" + doBackend;
    DesfechoInteracao desfecho = substitui && SUBSTITUIVEIS.contains(resultado.desfecho())
        ? DesfechoInteracao.RESPONDIDA : resultado.desfecho();
    // Os trechos relacionados (#343) so fazem sentido com a frase que os apresenta: sem ela, ao
    // lado do artigo do dado, seriam artigos soltos. Os que o modelo citou (#258) ficam.
    List<CitacaoLegal> daLei = substitui || !dispositivos.isEmpty() && relacionados
        ? List.of() : resultado.citacoes();
    return new ResultadoOrquestracao(resultado.rota(), texto, desfecho,
        CitacoesFixas.juntar(daLei, CitacoesFixas.buscar(busca,
            List.copyOf(dispositivos), contexto, "Tratamento do produto")),
        substitui || resultado.exibirFontes(), resultado.modoFallback(), resultado.atalhos());
  }

  /**
   * As citações da rota são os trechos relacionados da lei (#343), ainda com a frase que os
   * apresenta: o sinal do contexto e a frase no texto. Depois que um fundamento do backend tirou a
   * frase (o {@code TextosDoBackend} roda antes do {@code FundamentoRetencao}), as citações já são
   * as dele e ficam.
   */
  static boolean comTrechosRelacionados(String texto, ContextoAssistente contexto) {
    return contexto.trechosRelacionados()
        && texto.contains(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO);
  }

  /**
   * O texto sem as recusas da lei, para um fundamento do backend entrar logo depois sem
   * contradição. Lista única, usada também pelo {@link FundamentoRetencao} (#343).
   *
   * @param texto               texto da rota
   * @param trechosRelacionados a lei respondeu só com os trechos relacionados
   *                            ({@link ContextoAssistente#trechosRelacionados()}): a frase que os
   *                            apresenta sai junto
   */
  static String semRecusaDaLei(String texto, boolean trechosRelacionados) {
    String limpo = texto;
    for (String recusa : RECUSAS_DA_LEI) {
      limpo = limpo.replace(recusa, "");
    }
    if (trechosRelacionados) {
      limpo = limpo.replace(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO, "");
    }
    return limpo.replaceAll("\n{3,}", "\n\n").strip();
  }
}
