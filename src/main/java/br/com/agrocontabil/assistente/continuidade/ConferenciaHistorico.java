package br.com.agrocontabil.assistente.continuidade;

import br.com.agrocontabil.assistente.continuidade.CargaTurno.Faixa;
import br.com.agrocontabil.assistente.orquestracao.FiltroInjecao;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.assistente.porta.HistoricoConversaPorta;
import br.com.agrocontabil.dto.TurnoConversa;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Confere o histórico recebido do cliente (#499), uma vez por requisição e com um único
 * {@link Instant}. Silenciosa: nada vira 400, para a conferência não servir de oráculo, e nada do
 * texto vai a log (só o enum).
 *
 * <p>Ordem, por par: MAC (a porta), carga estrita ({@link CodecCarga}), validade não mais distante
 * que {@code agora + 31 min}, rota, classe da lei, faixas dentro da resposta e funções na lista
 * fechada. Qualquer falha nesses passos ignora o histórico <em>inteiro</em>: um par adulterado diz
 * que o cliente não é confiável. Par vencido cai sozinho; par com pergunta de cara de injeção cai
 * sozinho; resposta de cara de injeção tira o par só do prompt da reescrita. Pares repetidos
 * contam uma vez, ordenados pelo {@code exp} e, no empate, pela posição.
 */
@Component
public class ConferenciaHistorico {

  /** Validade de um par a partir da emissão. */
  public static final Duration VALIDADE = Duration.ofMinutes(30);

  /** Folga sobre a validade (relógio): {@code exp} além de {@code agora + 31 min} é forjado. */
  static final Duration FOLGA = Duration.ofMinutes(31);

  private static final Logger log = LoggerFactory.getLogger(ConferenciaHistorico.class);

  private final HistoricoConversaPorta porta;

  public ConferenciaHistorico(HistoricoConversaPorta porta) {
    this.porta = porta;
  }

  /**
   * Confere o histórico.
   *
   * @param produtorId produtor do token
   * @param historico  trocas recebidas (já validadas no tamanho)
   * @param agora      instante único da requisição
   * @param funcoes    lista fechada das funções de dados
   * @return os pares válidos, em ordem
   */
  public HistoricoConferido conferir(UUID produtorId, List<TurnoConversa> historico,
      Instant agora, Set<String> funcoes) {
    if (historico == null || historico.isEmpty()) {
      return HistoricoConferido.VAZIO;
    }
    List<DesfechoContinuidade> desfechos = new ArrayList<>();
    List<ParConferido> pares = new ArrayList<>();
    Set<String> vistas = new HashSet<>();
    long limite = agora.plus(FOLGA).getEpochSecond();
    for (int i = 0; i < historico.size(); i++) {
      TurnoConversa turno = historico.get(i);
      if (turno == null || turno.pergunta() == null || turno.resposta() == null
          || turno.assinatura() == null) {
        return ignorar();
      }
      Optional<CargaTurno> lida = porta.conferir(
              produtorId, turno.assinatura(), turno.pergunta(), turno.resposta())
          .flatMap(CodecCarga::ler);
      if (lida.isEmpty()) {
        return ignorar();
      }
      CargaTurno carga = lida.get();
      if (carga.exp() > limite || !coerente(carga, turno.resposta(), funcoes)) {
        return ignorar();
      }
      if (carga.exp() < agora.getEpochSecond()) {
        desfechos.add(DesfechoContinuidade.PAR_VENCIDO);
        continue;
      }
      if (!vistas.add(turno.assinatura())) {
        desfechos.add(DesfechoContinuidade.PAR_REPETIDO);
        continue;
      }
      if (FiltroInjecao.pareceInjecao(turno.pergunta())) {
        desfechos.add(DesfechoContinuidade.PAR_COM_INJECAO);
        continue;
      }
      boolean foraDaReescrita = FiltroInjecao.pareceInjecao(turno.resposta());
      if (foraDaReescrita) {
        desfechos.add(DesfechoContinuidade.PAR_FORA_DA_REESCRITA);
      }
      pares.add(new ParConferido(turno.pergunta(), turno.resposta(), turno.assinatura(), carga,
          i, foraDaReescrita));
    }
    pares.sort(Comparator.comparingLong((ParConferido p) -> p.carga().exp())
        .thenComparingInt(ParConferido::posicao));
    HistoricoConferido conferido = new HistoricoConferido(pares, desfechos);
    if (!desfechos.isEmpty()) {
      log.info("Ceres: historico conferido com {} par(es); {}", pares.size(), desfechos);
    }
    return conferido;
  }

  private static HistoricoConferido ignorar() {
    log.info("Ceres: {}", DesfechoContinuidade.HISTORICO_IGNORADO);
    return HistoricoConferido.ignorado();
  }

  /** A carga faz sentido para esta resposta: rota com a lei certa, faixas e funções. */
  static boolean coerente(CargaTurno carga, String resposta, Set<String> funcoes) {
    int tamanho = resposta.length();
    LeiAssinada lei = carga.lei();
    if (carga.rota() == Intencao.LEGISLACAO && lei == null) {
      return false;
    }
    if (carga.rota() == Intencao.DADOS && lei != null) {
      return false;
    }
    if (carga.rota() == Intencao.MISTO && lei == null) {
      return false;
    }
    if (carga.rota() == Intencao.LEGISLACAO
        && (carga.consulta() != null || carga.dados() != null)) {
      return false;
    }
    if (lei != null && lei.textoModelo() != null && !lei.textoModelo().dentroDe(tamanho)) {
      return false;
    }
    if (carga.livres() != null) {
      for (Faixa faixa : carga.livres()) {
        if (!faixa.dentroDe(tamanho)) {
          return false;
        }
      }
    }
    return carga.consulta() == null
        || carga.consulta().stream().allMatch(c -> funcoes.contains(c.funcao()));
  }
}
