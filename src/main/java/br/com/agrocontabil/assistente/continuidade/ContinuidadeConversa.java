package br.com.agrocontabil.assistente.continuidade;

import br.com.agrocontabil.assistente.RotaDados;
import br.com.agrocontabil.assistente.continuidade.DecisaoContinuidade.Decisao;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.dto.AssistenteChatRequest;
import br.com.agrocontabil.dto.TurnoConversa;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A continuidade da conversa (#499) vista pelo {@code AssistenteService}: a flag, a conferência
 * do histórico com a decisão (antes da reserva da cota) e a emissão do {@code turno}. Desligada, o
 * histórico é ignorado e nenhum turno sai: o caminho de antes, sem campo novo nem chamada extra.
 */
@Component
public class ContinuidadeConversa {

  private final boolean habilitada;
  private final ConferenciaHistorico conferencia;
  private final EmissaoTurno emissao;
  private final RotaDados rotaDados;
  private final boolean modeloPorApi;

  /** A continuidade da aplicação. */
  @Autowired
  public ContinuidadeConversa(AssistenteProperties propriedades, ConferenciaHistorico conferencia,
      EmissaoTurno emissao, RotaDados rotaDados, ModeloLinguagem modelo) {
    this(propriedades.getContinuidade().isHabilitada(), conferencia, emissao, rotaDados,
        modelo.porApi());
  }

  private ContinuidadeConversa(boolean habilitada, ConferenciaHistorico conferencia,
      EmissaoTurno emissao, RotaDados rotaDados, boolean modeloPorApi) {
    this.habilitada = habilitada;
    this.conferencia = conferencia;
    this.emissao = emissao;
    this.rotaDados = rotaDados;
    this.modeloPorApi = modeloPorApi;
  }

  /** A continuidade desligada (testes do serviço sem ela). */
  public static ContinuidadeConversa desligada() {
    return new ContinuidadeConversa(false, null, null, null, false);
  }

  /** A flag {@code app.assistente.continuidade.habilitada}. */
  public boolean habilitada() {
    return habilitada;
  }

  /**
   * {@code true} com a continuidade ligada e o modelo por API: as trocas anteriores vão inteiras
   * ao provedor, fora do Brasil, e a tela mostra o aviso.
   */
  public boolean conversaVaiAoProvedor() {
    return habilitada && modeloPorApi;
  }

  /**
   * Confere o histórico e decide o caminho, na thread da requisição e antes da reserva.
   *
   * @param produtorId produtor do token
   * @param request    pergunta com o histórico
   * @param agora      instante único da requisição
   * @return a decisão; desligada, a de antes (atalho, conversa leve ou a pergunta de sempre)
   */
  public Decisao decidir(UUID produtorId, AssistenteChatRequest request, Instant agora) {
    if (!habilitada) {
      return DecisaoContinuidade.decidir(request.pergunta(), HistoricoConferido.VAZIO, false);
    }
    Set<String> funcoes = rotaDados.funcoesPermitidas();
    HistoricoConferido historico = conferencia.conferir(produtorId, request.historicoOuVazio(),
        agora, funcoes);
    return DecisaoContinuidade.decidir(request.pergunta(), historico, request.semTurnoNaUltima());
  }

  /**
   * O turno da resposta, com a continuidade ligada.
   *
   * @param produtorId produtor do token
   * @param pergunta   pergunta considerada
   * @param resposta   resposta entregue
   * @param rota       rota da resposta
   * @param registro   registro da pergunta
   * @param agora      instante da requisição
   * @return o turno, ou vazio (desligada, rota sem turno, falha da assinatura)
   */
  public Optional<TurnoConversa> emitir(UUID produtorId, String pergunta, String resposta,
      Intencao rota, RegistroTurno registro, Instant agora) {
    if (!habilitada) {
      return Optional.empty();
    }
    return emissao.emitir(produtorId, pergunta, resposta, rota, registro, agora);
  }
}
