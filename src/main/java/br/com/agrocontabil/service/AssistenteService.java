package br.com.agrocontabil.service;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.ResultadoRota;
import br.com.agrocontabil.assistente.RotaDados;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.dto.AssistenteChatRequest;
import br.com.agrocontabil.dto.AssistenteChatResponse;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.exception.AssistenteOcupadoException;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.model.Produtor;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Entrada da Ceres, a assistente fiscal (issues #142 e #144).
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
 *   <li><strong>rota</strong>;</li>
 *   <li><strong>auditoria</strong> (issue #144): toda pergunta que chegou à rota é registrada, sem o
 *       texto da pergunta nem da resposta. É requisito (RF020): se a gravação falhar, a resposta não
 *       é entregue.</li>
 * </ol>
 *
 * <p>Não é transacional: nenhuma conexão do pool fica presa enquanto o modelo gera.
 */
@Service
public class AssistenteService {

  /** Rota desta entrega (a #157 traz as demais). */
  static final String ROTA_DADOS = "DADOS";

  private static final Logger log = LoggerFactory.getLogger(AssistenteService.class);

  /** Fuso de referência do projeto: "hoje" para a Ceres é a data em Brasília. */
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  private final AuthenticatedProdutorProvider produtorProvider;
  private final RotaDados rotaDados;
  private final AuditoriaAssistenteService auditoria;
  private final ModeloLinguagem modeloLinguagem;
  private final Clock clock;
  private final Semaphore vagas;

  /** Constrói o serviço. */
  public AssistenteService(
      AuthenticatedProdutorProvider produtorProvider,
      RotaDados rotaDados,
      AuditoriaAssistenteService auditoria,
      ModeloLinguagem modeloLinguagem,
      Clock clock,
      AssistenteProperties propriedades) {
    this.produtorProvider = produtorProvider;
    this.rotaDados = rotaDados;
    this.auditoria = auditoria;
    this.modeloLinguagem = modeloLinguagem;
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
   * @throws AssistenteIndisponivelException modelo fora ou auditoria não gravada (503)
   */
  public AssistenteChatResponse conversar(AssistenteChatRequest request) {
    Produtor produtor = produtorProvider.get();
    if (!vagas.tryAcquire()) {
      throw new AssistenteOcupadoException();
    }
    try {
      return responder(produtor, request.pergunta());
    } finally {
      vagas.release();
    }
  }

  private AssistenteChatResponse responder(Produtor produtor, String pergunta) {
    ContextoAssistente contexto =
        new ContextoAssistente(produtor.getId(), LocalDate.now(clock.withZone(ZONE)));
    long inicio = System.nanoTime();
    ResultadoRota resultado;
    try {
      resultado = rotaDados.responder(pergunta, contexto);
    } catch (AssistenteIndisponivelException e) {
      auditarSemFalhar(contexto, DesfechoInteracao.MODELO_INDISPONIVEL, inicio, pergunta);
      throw e;
    } catch (RuntimeException e) {
      auditarSemFalhar(contexto, DesfechoInteracao.ERRO, inicio, pergunta);
      throw e;
    }

    try {
      auditoria.registrar(contexto, ROTA_DADOS, resultado.desfecho(),
          modeloLinguagem.identificacao(), decorridoMs(inicio), pergunta.length());
    } catch (RuntimeException e) {
      // RF020: sem registro de auditoria, a resposta nao e entregue.
      throw new AssistenteIndisponivelException(
          "Falha ao registrar a auditoria da Ceres: " + e.getClass().getSimpleName(), e);
    }

    // Resposta descartada = texto fixo do backend: nada dele veio das funcoes, entao o selo
    // "calculado a partir das suas notas" nao pode aparecer ao lado.
    List<AssistenteChatResponse.FonteDado> fontes = resultado.aceita()
        ? contexto.fontes().stream()
            .map(f -> new AssistenteChatResponse.FonteDado(f.funcao(), f.parametros()))
            .toList()
        : List.of();
    return new AssistenteChatResponse(resultado.texto(), ROTA_DADOS, List.of(), fontes, false);
  }

  /** A falha original é a que importa ao cliente; a da auditoria vai só ao log. */
  private void auditarSemFalhar(
      ContextoAssistente contexto, DesfechoInteracao desfecho, long inicio, String pergunta) {
    try {
      auditoria.registrar(contexto, ROTA_DADOS, desfecho, modeloLinguagem.identificacao(),
          decorridoMs(inicio), pergunta.length());
    } catch (RuntimeException e) {
      log.error("Falha ao registrar a auditoria de uma pergunta que falhou: {}",
          e.getClass().getName());
    }
  }

  private static long decorridoMs(long inicioNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicioNanos);
  }
}
