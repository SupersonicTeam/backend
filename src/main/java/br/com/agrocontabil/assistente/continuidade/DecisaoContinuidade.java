package br.com.agrocontabil.assistente.continuidade;

import br.com.agrocontabil.assistente.orquestracao.AtalhosFixos;
import br.com.agrocontabil.assistente.orquestracao.ConversaLeve;
import br.com.agrocontabil.assistente.orquestracao.FiltroInjecao;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.config.CaminhoCota;
import java.util.Optional;

/**
 * Decide, na thread da requisição e antes da reserva da cota, o caminho da pergunta e quanto ela
 * pode custar (#499). Função pura: o mesmo objeto vai ao {@code AssistenteService} (reserva) e ao
 * {@code OrquestradorService} (resposta), então os dois nunca discordam.
 *
 * <ol>
 *   <li>atalho da tela, sempre primeiro;</li>
 *   <li>conversa leve: agradecimento, despedida, reconhecimento e apresentação sempre; SEGUIMENTO
 *       e saudação ambígua só sem histórico válido (ou com a última resposta sem {@code turno});</li>
 *   <li>sem histórico, ou mensagem com cara de injeção: a pergunta de sempre;</li>
 *   <li>confirmação, contestação pura ou reafirmação: o caminho da confirmação, com o custo pela
 *       rota do último par;</li>
 *   <li>gatilho da reescrita verdadeiro: com reescrita; falso: a pergunta de sempre.</li>
 * </ol>
 */
public final class DecisaoContinuidade {

  /** O caminho da pergunta. */
  public enum Caminho {
    /** Atalho fixo da tela. */
    ATALHO,
    /** Conversa leve, texto fixo. */
    CONVERSA_LEVE,
    /** A pergunta de sempre, sem o histórico. */
    NORMAL,
    /** Confirmação, contestação pura ou reafirmação da resposta anterior. */
    CONFIRMACAO,
    /** Reescrita da mensagem com o histórico. */
    REESCRITA
  }

  /**
   * A decisão.
   *
   * @param caminho  caminho da pergunta
   * @param custo    classe de custo para a reserva
   * @param historico histórico conferido (vazio sem continuidade)
   * @param par      último par válido, nos caminhos que o usam
   * @param tipo     o que a mensagem faz com a resposta anterior
   * @param desfecho desfecho da decisão, para o log (nulo sem continuidade)
   */
  public record Decisao(Caminho caminho, CaminhoCota custo, HistoricoConferido historico,
      Optional<ParConferido> par, MensagemContinuidade.Tipo tipo, DesfechoContinuidade desfecho) {

    /** Sem continuidade: a pergunta de sempre. */
    public static Decisao normal() {
      return new Decisao(Caminho.NORMAL, CaminhoCota.NORMAL, HistoricoConferido.VAZIO,
          Optional.empty(), MensagemContinuidade.Tipo.NENHUMA, null);
    }

    /** Sem texto. */
    @Override
    public String toString() {
      return "Decisao[" + caminho + ", " + custo + ", " + tipo + "]";
    }
  }

  private DecisaoContinuidade() {
  }

  /**
   * Decide o caminho.
   *
   * @param mensagem              mensagem nova
   * @param historico             histórico conferido (vazio com a continuidade desligada)
   * @param ultimaRespostaSemTurno a última mensagem da Ceres na tela não trouxe {@code turno}
   * @return a decisão
   */
  public static Decisao decidir(String mensagem, HistoricoConferido historico,
      boolean ultimaRespostaSemTurno) {
    if (AtalhosFixos.ehAtalho(mensagem)) {
      return new Decisao(Caminho.ATALHO, CaminhoCota.SEM_MODELO, historico, Optional.empty(),
          MensagemContinuidade.Tipo.NENHUMA, null);
    }
    boolean semHistorico = historico.vazio() || ultimaRespostaSemTurno;
    if (ConversaLeve.ehConversaLeve(mensagem)
        && (semHistorico || !ConversaLeve.dependeDoHistorico(mensagem))) {
      return new Decisao(Caminho.CONVERSA_LEVE, CaminhoCota.SEM_MODELO, historico,
          Optional.empty(), MensagemContinuidade.Tipo.NENHUMA, null);
    }
    if (semHistorico || FiltroInjecao.pareceInjecao(mensagem)) {
      return new Decisao(Caminho.NORMAL, CaminhoCota.NORMAL, historico, Optional.empty(),
          MensagemContinuidade.Tipo.NENHUMA, null);
    }
    ParConferido par = historico.ultimo().orElseThrow();
    MensagemContinuidade.Tipo tipo = MensagemContinuidade.ler(mensagem, par);
    if (tipo.caminhoDaConfirmacao()) {
      return new Decisao(Caminho.CONFIRMACAO, custoDaConfirmacao(par), historico,
          Optional.of(par), tipo, null);
    }
    if (GatilhoReescrita.dispara(mensagem, tipo)) {
      return new Decisao(Caminho.REESCRITA, CaminhoCota.REESCRITA, historico, Optional.of(par),
          tipo, null);
    }
    return new Decisao(Caminho.NORMAL, CaminhoCota.NORMAL, historico, Optional.of(par), tipo,
        DesfechoContinuidade.CONTINUIDADE_DISPENSADA);
  }

  /** Custo do caminho da confirmação pela rota do par. */
  static CaminhoCota custoDaConfirmacao(ParConferido par) {
    CargaTurno carga = par.carga();
    boolean bate = consultaBate(par);
    return switch (carga.rota()) {
      case DADOS -> bate ? CaminhoCota.SEM_MODELO : CaminhoCota.NORMAL;
      case LEGISLACAO -> leiPorTextoFixo(par) ? CaminhoCota.SEM_MODELO : CaminhoCota.RECONFERENCIA;
      case MISTO -> {
        boolean fixa = leiPorTextoFixo(par);
        if (bate) {
          yield fixa ? CaminhoCota.SEM_MODELO : CaminhoCota.RECONFERENCIA;
        }
        yield fixa ? CaminhoCota.NORMAL : CaminhoCota.RECONFERENCIA_MISTO;
      }
      default -> CaminhoCota.NORMAL;
    };
  }

  /** A consulta assinada do par bate com a pergunta dele (no dia do par). */
  public static boolean consultaBate(ParConferido par) {
    CargaTurno carga = par.carga();
    if (carga.consulta() == null) {
      return false;
    }
    if (carga.rota() == Intencao.DADOS && AtalhosFixos.ehAtalho(par.pergunta())) {
      // Atalho de dados da tela: a funcao e fixa ({@code {}}, com o padrao dela) e coerente por
      // construcao. As regras de periodo mudariam os argumentos ("este mes" vira o mes) e
      // mandariam ao modelo uma pergunta que se refaz sem ele.
      return true;
    }
    return ConferenciaConsulta.bate(par.pergunta(), carga.consulta(),
        ConferenciaConsulta.diaDoPar(carga.exp()));
  }

  /**
   * A reconferência deste par sai por texto fixo, sem o modelo: atalho de lei, resposta que não
   * afirmou regra, já reconferida, texto do modelo cortado, MISTO sem a pergunta legal ou com
   * cara de injeção no texto do modelo.
   *
   * @param par par de lei
   * @return se não há o que o modelo conferir
   */
  public static boolean leiPorTextoFixo(ParConferido par) {
    LeiAssinada lei = par.carga().lei();
    if (lei == null || lei.atalho() != null || lei.classe() != LeiAssinada.Classe.CONFIRMADA
        || lei.reconferida() || lei.textoModelo() == null) {
      return true;
    }
    if (par.carga().rota() == Intencao.MISTO && lei.semPergunta()) {
      return true;
    }
    String fatia = lei.textoModelo().de(par.resposta());
    return fatia.contains("[…]") || FiltroInjecao.pareceInjecao(fatia);
  }
}
