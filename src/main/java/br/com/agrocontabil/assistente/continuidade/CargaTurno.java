package br.com.agrocontabil.assistente.continuidade;

import br.com.agrocontabil.assistente.orquestracao.Intencao;
import java.util.List;
import java.util.Objects;

/**
 * Carga assinada de uma troca (#499). O que o servidor afirma sobre o par: até quando ele vale, a
 * rota, se a pergunta veio de reescrita, o que foi consultado e onde, na resposta, estão os textos
 * livres e o texto do modelo de lei. A conferência só a lê depois do MAC.
 *
 * @param exp       validade, em segundos desde a época (emissão + 30 min, ou herdada)
 * @param rota      {@code DADOS}, {@code LEGISLACAO} ou {@code MISTO}
 * @param reescrita {@code true} se a pergunta considerada saiu de uma reescrita do modelo
 *                  (cumulativo)
 * @param consulta  chamadas de função executadas (parte de dados aceita, até 6), ou nulo
 * @param dados     hash do texto de dados normalizado (Base64url de 16 bytes), ou nulo
 * @param livres    faixas dos textos livres do template na resposta, ou nulo
 * @param livresTodos {@code true} quando os textos livres passaram de 16 ({@code "t": "*"})
 * @param lei       a parte de lei, ou nulo
 */
public record CargaTurno(long exp, Intencao rota, boolean reescrita,
    List<ChamadaAssinada> consulta, String dados, List<Faixa> livres, boolean livresTodos,
    LeiAssinada lei) {

  /** Máximo de chamadas na consulta assinada. */
  public static final int MAXIMO_CHAMADAS = 6;

  /** Máximo de faixas de texto livre antes de virar {@code "*"}. */
  public static final int MAXIMO_LIVRES = 16;

  /** Normaliza as listas. */
  public CargaTurno {
    Objects.requireNonNull(rota, "rota");
    consulta = consulta == null ? null : List.copyOf(consulta);
    livres = livres == null ? null : List.copyOf(livres);
  }

  /** Mesma carga sem as citações de lei ({@code lei.c}), o primeiro corte de tamanho. */
  CargaTurno semCitacoesDeLei() {
    return lei == null || lei.citacoes() == null ? this
        : new CargaTurno(exp, rota, reescrita, consulta, dados, livres, livresTodos,
            lei.semCitacoes());
  }

  /** Mesma carga com {@code "t": "*"}, o segundo corte. */
  CargaTurno comLivresTodos() {
    return livres == null && !livresTodos ? this
        : new CargaTurno(exp, rota, reescrita, consulta, dados, null, true, lei);
  }

  /** Mesma carga com {@code s} no lugar de {@code q}, o terceiro corte. */
  CargaTurno semPerguntaLei() {
    return lei == null || lei.perguntaLei() == null ? this
        : new CargaTurno(exp, rota, reescrita, consulta, dados, livres, livresTodos,
            lei.semPerguntaLei());
  }

  /** Mesma carga sem {@code consulta} e {@code dados}, o último corte. */
  CargaTurno semConsulta() {
    return new CargaTurno(exp, rota, reescrita, null, null, livres, livresTodos, lei);
  }

  /**
   * Faixa de texto na resposta, em unidades UTF-16.
   *
   * @param inicio índice do primeiro caractere
   * @param fim    índice depois do último
   */
  public record Faixa(int inicio, int fim) {

    /** Valida a faixa. */
    public Faixa {
      if (inicio < 0 || fim < inicio) {
        throw new IllegalArgumentException("faixa invalida");
      }
    }

    /** {@code true} se a faixa cabe num texto deste tamanho. */
    public boolean dentroDe(int tamanho) {
      return fim <= tamanho;
    }

    /** O pedaço do texto. */
    public String de(String texto) {
      return texto.substring(inicio, fim);
    }
  }
}
