package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.ContextoAssistente.TextoDoBackend;
import br.com.agrocontabil.assistente.FonteDado;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Põe na resposta os textos que o backend montou a partir do dado (issue #283): hoje, o tratamento
 * de um produto pela tabela oficial ({@code consultarTratamentoProduto}). O texto e a citação vêm
 * da tabela, nunca do modelo — mesmo padrão do {@link FundamentoRetencao}.
 *
 * <ul>
 *   <li>Rota de dados em que o tratamento foi a única consulta: a resposta é o texto do backend,
 *       e o do modelo sai (ele só repetiria, com risco de trocar uma condição por afirmação).</li>
 *   <li>Com outras consultas (ou no MISTO): o texto do backend vem depois do que a rota
 *       respondeu.</li>
 * </ul>
 * As citações são os dispositivos do dado (o artigo do {@code cClassTrib}), com o texto da base de
 * legislação; sem a base, o texto segue sozinho (ele já nomeia o artigo).
 */
@Component
public class TextosDoBackend {

  /** Funções cujo resultado vira texto do backend. */
  static final Set<String> FUNCOES = Set.of("consultarTratamentoProduto");

  private static final Logger log = LoggerFactory.getLogger(TextosDoBackend.class);

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
    String doBackend = textos.stream().map(TextoDoBackend::texto).distinct()
        .collect(Collectors.joining("\n\n"));
    boolean soEstas = contexto.fontes().stream().map(FonteDado::funcao).allMatch(FUNCOES::contains);
    boolean substitui = resultado.rota() == Intencao.DADOS && soEstas;
    String texto = substitui || resultado.texto().isBlank()
        ? doBackend : resultado.texto() + "\n\n" + doBackend;
    DesfechoInteracao desfecho = substitui ? DesfechoInteracao.RESPONDIDA : resultado.desfecho();
    Set<String> dispositivos = new LinkedHashSet<>();
    textos.forEach(t -> dispositivos.addAll(t.dispositivos()));
    return new ResultadoOrquestracao(resultado.rota(), texto, desfecho,
        juntar(resultado.citacoes(), citacoes(List.copyOf(dispositivos), contexto)),
        substitui || resultado.exibirFontes(), resultado.modoFallback(), resultado.atalhos());
  }

  private List<CitacaoLegal> citacoes(List<String> dispositivos, ContextoAssistente contexto) {
    if (dispositivos.isEmpty()) {
      return List.of();
    }
    contexto.verificarAtiva();
    try {
      List<TrechoRecuperado> trechos = busca.buscarDispositivos(dispositivos, contexto.hoje());
      contexto.registrarTrechosCitados(trechos.stream().map(TrechoRecuperado::id).toList());
      return trechos.stream().map(TrechoRecuperado::comoCitacao).toList();
    } catch (AssistenteIndisponivelException e) {
      log.warn("Tratamento do produto sem citacoes: base de legislacao indisponivel");
      return List.of();
    }
  }

  private static List<CitacaoLegal> juntar(List<CitacaoLegal> daRota, List<CitacaoLegal> fixas) {
    Map<String, CitacaoLegal> porReferencia = new LinkedHashMap<>();
    daRota.forEach(c -> porReferencia.putIfAbsent(c.referencia(), c));
    fixas.forEach(c -> porReferencia.putIfAbsent(c.referencia(), c));
    return new ArrayList<>(porReferencia.values());
  }
}
