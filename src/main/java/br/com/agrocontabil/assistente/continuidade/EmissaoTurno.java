package br.com.agrocontabil.assistente.continuidade;

import br.com.agrocontabil.assistente.continuidade.CargaTurno.Faixa;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.assistente.porta.HistoricoConversaPorta;
import br.com.agrocontabil.dto.TurnoConversa;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Monta o {@code turno} assinado de uma resposta (#499): a pergunta considerada, a resposta
 * entregue (inteira até {@value #TAMANHO_MAXIMO} caracteres, cortada acima disso) e a assinatura
 * sobre a carga. A carga é escrita a partir do {@link RegistroTurno}; as faixas ({@code t} e
 * {@code lei.r}) são procuradas no texto já cortado, então a assinatura vale para ele.
 */
@Component
public class EmissaoTurno {

  /** Tamanho máximo da resposta do turno. */
  public static final int TAMANHO_MAXIMO = TurnoConversa.TAMANHO_RESPOSTA;

  /** Tamanho máximo de cada parte do MISTO no turno. */
  static final int TAMANHO_PARTE_MISTO = 3000;

  /** Marca do corte. */
  static final String MARCA_CORTE = "\n[…]";

  /** Janela em que o corte procura uma quebra de linha antes de aceitar um espaço. */
  private static final int JANELA_QUEBRA = 400;

  private final HistoricoConversaPorta porta;

  public EmissaoTurno(HistoricoConversaPorta porta) {
    this.porta = porta;
  }

  /**
   * O turno da resposta.
   *
   * @param produtorId produtor do token
   * @param pergunta   pergunta considerada
   * @param resposta   resposta entregue
   * @param rota       rota da resposta ({@code DADOS}, {@code LEGISLACAO} ou {@code MISTO})
   * @param registro   o que a pergunta registrou
   * @param agora      instante da requisição
   * @return o turno, ou vazio se ele não pôde ser assinado
   */
  public Optional<TurnoConversa> emitir(UUID produtorId, String pergunta, String resposta,
      Intencao rota, RegistroTurno registro, Instant agora) {
    if (!registro.emiteTurno()) {
      return Optional.empty();
    }
    if (registro.turnoDeOrigem() != null) {
      return Optional.of(registro.turnoDeOrigem());
    }
    if (rota != Intencao.DADOS && rota != Intencao.LEGISLACAO && rota != Intencao.MISTO) {
      return Optional.empty();
    }
    String perguntaTurno = semControle(pergunta);
    String respostaTurno = cortar(semControle(resposta), rota, registro.parteLei());
    if (perguntaTurno.isBlank() || perguntaTurno.length() > TurnoConversa.TAMANHO_PERGUNTA
        || respostaTurno.isBlank()) {
      return Optional.empty();
    }
    CargaTurno carga = carga(rota, respostaTurno, registro, agora);
    return CodecCarga.escrever(carga)
        .flatMap(json -> porta.assinar(produtorId, json, perguntaTurno, respostaTurno))
        .map(assinatura -> new TurnoConversa(perguntaTurno, respostaTurno, assinatura));
  }

  static CargaTurno carga(Intencao rota, String resposta, RegistroTurno registro,
      Instant agora) {
    long exp = registro.expHerdado() != null
        ? registro.expHerdado()
        : agora.plus(ConferenciaHistorico.VALIDADE).getEpochSecond();
    List<ChamadaAssinada> consulta = null;
    String dados = null;
    if (rota != Intencao.LEGISLACAO && registro.consulta() != null
        && !registro.consulta().isEmpty()
        && registro.consulta().size() <= CargaTurno.MAXIMO_CHAMADAS) {
      consulta = registro.consulta();
      dados = registro.textoDados() == null ? null : HashDados.de(registro.textoDados());
    }
    List<Faixa> livres = faixas(resposta, registro.livres());
    boolean livresTodos = livres.size() > CargaTurno.MAXIMO_LIVRES;
    LeiAssinada lei = null;
    if (rota != Intencao.DADOS && registro.classeLei() != null) {
      Faixa r = registro.classeLei() == LeiAssinada.Classe.CONFIRMADA
          ? faixa(resposta, registro.textoModeloLei()).orElse(null)
          : null;
      List<String> citacoes = r != null ? null : registro.citacoesLei().stream()
          .limit(LeiAssinada.MAXIMO_CITACOES).toList();
      String q = registro.perguntaLei();
      boolean s = false;
      if (rota == Intencao.MISTO && q != null && q.length() > LeiAssinada.MAXIMO_PERGUNTA) {
        q = null;
        s = true;
      }
      if (rota != Intencao.MISTO) {
        q = null;
      }
      lei = new LeiAssinada(registro.classeLei(), r, citacoes, q, s, registro.reconferida(),
          registro.atalhoLei());
    }
    return new CargaTurno(exp, rota, registro.reescrita(), consulta, dados,
        livres.isEmpty() || livresTodos ? null : livres, livresTodos, lei);
  }

  /** Todas as ocorrências dos textos livres na resposta, em ordem e sem sobreposição. */
  static List<Faixa> faixas(String resposta, List<String> textos) {
    List<Faixa> faixas = new ArrayList<>();
    for (String texto : textos.stream().distinct().toList()) {
      int de = 0;
      int achado;
      while ((achado = resposta.indexOf(texto, de)) >= 0) {
        faixas.add(new Faixa(achado, achado + texto.length()));
        de = achado + texto.length();
      }
    }
    faixas.sort(Comparator.comparingInt(Faixa::inicio));
    List<Faixa> unidas = new ArrayList<>();
    for (Faixa f : faixas) {
      Faixa ultima = unidas.isEmpty() ? null : unidas.get(unidas.size() - 1);
      if (ultima != null && f.inicio() <= ultima.fim()) {
        unidas.set(unidas.size() - 1,
            new Faixa(ultima.inicio(), Math.max(ultima.fim(), f.fim())));
      } else {
        unidas.add(f);
      }
    }
    return unidas;
  }

  /** A faixa da primeira ocorrência do texto do modelo de lei na resposta. */
  static Optional<Faixa> faixa(String resposta, String texto) {
    if (texto == null || texto.isBlank()) {
      return Optional.empty();
    }
    int achado = resposta.indexOf(texto);
    return achado < 0 ? Optional.empty()
        : Optional.of(new Faixa(achado, achado + texto.length()));
  }

  /** Caracteres de controle, exceto a quebra de linha, viram espaço. */
  static String semControle(String texto) {
    if (texto == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(texto.length());
    for (int i = 0; i < texto.length(); i++) {
      char c = texto.charAt(i);
      sb.append(c != '\n' && Character.isISOControl(c) ? ' ' : c);
    }
    return sb.toString();
  }

  /**
   * Corta a resposta acima de {@value #TAMANHO_MAXIMO}; no MISTO, cada parte até
   * {@value #TAMANHO_PARTE_MISTO}.
   */
  static String cortar(String resposta, Intencao rota, String parteLei) {
    if (resposta.length() <= TAMANHO_MAXIMO) {
      return resposta;
    }
    if (rota == Intencao.MISTO && parteLei != null) {
      int inicioLei = resposta.lastIndexOf(parteLei);
      if (inicioLei > 0) {
        String dados = resposta.substring(0, inicioLei).stripTrailing();
        String lei = resposta.substring(inicioLei);
        return cortarEm(dados, TAMANHO_PARTE_MISTO) + "\n\n" + cortarEm(lei, TAMANHO_PARTE_MISTO);
      }
    }
    return cortarEm(resposta, TAMANHO_MAXIMO);
  }

  /**
   * Corta o texto para caber em {@code teto} com a marca: na última quebra de linha antes do
   * limite, ou no último espaço se não houver quebra nos últimos {@value #JANELA_QUEBRA}
   * caracteres, sem partir par substituto.
   */
  static String cortarEm(String texto, int teto) {
    if (texto.length() <= teto) {
      return texto;
    }
    int limite = teto - MARCA_CORTE.length();
    int quebra = texto.lastIndexOf('\n', limite);
    int corte;
    if (quebra > 0 && quebra >= limite - JANELA_QUEBRA) {
      corte = quebra;
    } else {
      int espaco = texto.lastIndexOf(' ', limite);
      corte = espaco > 0 ? espaco : limite;
    }
    if (corte > 0 && Character.isHighSurrogate(texto.charAt(corte - 1))) {
      corte--;
    }
    return texto.substring(0, corte).stripTrailing() + MARCA_CORTE;
  }
}
