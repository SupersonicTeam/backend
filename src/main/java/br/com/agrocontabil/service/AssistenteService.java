package br.com.agrocontabil.service;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.ResultadoRota;
import br.com.agrocontabil.assistente.RotaDados;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.dto.AssistenteChatRequest;
import br.com.agrocontabil.dto.AssistenteChatResponse;
import br.com.agrocontabil.exception.AssistenteOcupadoException;
import br.com.agrocontabil.model.Produtor;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

/**
 * Entrada da Ceres, a assistente fiscal (issue #142).
 *
 * <p>Nesta entrega responde só pela rota de dados (Tool Calling); o roteamento entre dados,
 * legislação e as duas vem na issue #157.
 *
 * <p>A ordem importa:
 * <ol>
 *   <li><strong>identidade primeiro</strong> ({@link AuthenticatedProdutorProvider}): sem produtor
 *       autenticado a resposta é 401/403 antes de qualquer chamada ao modelo. Resolvida aqui, na
 *       thread da requisição, e entregue às funções pelo {@link ContextoAssistente} (issue #143);</li>
 *   <li><strong>vaga</strong>: no máximo {@code app.assistente.max-conversas-simultaneas} perguntas
 *       em andamento; acima disso 429, sem fila — cada pergunta prende uma thread pelo tempo de
 *       geração do modelo;</li>
 *   <li><strong>rota</strong>.</li>
 * </ol>
 *
 * <p>Não é transacional: nenhuma conexão do pool fica presa enquanto o modelo gera.
 */
@Service
public class AssistenteService {

  /** Fuso de referência do projeto: "hoje" para a Ceres é a data em Brasília. */
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  private final AuthenticatedProdutorProvider produtorProvider;
  private final RotaDados rotaDados;
  private final Clock clock;
  private final Semaphore vagas;

  /** Constrói o serviço. */
  public AssistenteService(
      AuthenticatedProdutorProvider produtorProvider,
      RotaDados rotaDados,
      Clock clock,
      AssistenteProperties propriedades) {
    this.produtorProvider = produtorProvider;
    this.rotaDados = rotaDados;
    this.clock = clock;
    this.vagas = new Semaphore(propriedades.getMaxConversasSimultaneas());
  }

  /**
   * Responde uma pergunta do produtor autenticado.
   *
   * @param request pergunta validada
   * @return resposta com as fontes de dados consultadas
   * @throws br.com.agrocontabil.exception.NaoAutenticadoException sem identidade (401)
   * @throws br.com.agrocontabil.exception.PapelNaoAutorizadoException contador (403)
   * @throws AssistenteOcupadoException acima do limite de perguntas simultâneas (429)
   * @throws br.com.agrocontabil.exception.AssistenteIndisponivelException modelo fora (503)
   */
  public AssistenteChatResponse conversar(AssistenteChatRequest request) {
    Produtor produtor = produtorProvider.get();
    if (!vagas.tryAcquire()) {
      throw new AssistenteOcupadoException();
    }
    try {
      ContextoAssistente contexto =
          new ContextoAssistente(produtor.getId(), LocalDate.now(clock.withZone(ZONE)));
      ResultadoRota resultado = rotaDados.responder(request.pergunta(), contexto);
      // Resposta descartada = texto fixo do backend: nada dele veio das funcoes, entao o selo
      // "calculado a partir das suas notas" nao pode aparecer ao lado.
      List<AssistenteChatResponse.FonteDado> fontes = resultado.aceita()
          ? contexto.fontes().stream()
              .map(f -> new AssistenteChatResponse.FonteDado(f.funcao(), f.parametros()))
              .toList()
          : List.of();
      return new AssistenteChatResponse(resultado.texto(), "DADOS", List.of(), fontes, false);
    } finally {
      vagas.release();
    }
  }
}
