package br.com.agrocontabil.assistente.continuidade;

import br.com.agrocontabil.dto.TurnoConversa;
import java.util.ArrayList;
import java.util.List;

/**
 * O que uma pergunta deixa para a carga do {@code turno} (#499), preenchido pelas rotas e pelo
 * orquestrador durante a pergunta e lido na emissão. Vive no {@code ContextoAssistente}, só
 * durante a pergunta.
 */
public final class RegistroTurno {

  private List<ChamadaAssinada> consulta;
  private String textoDados;
  private final List<String> livres = new ArrayList<>();
  private LeiAssinada.Classe classeLei;
  private String textoModeloLei;
  private List<String> citacoesLei = List.of();
  private String perguntaLei;
  private boolean reconferida;
  private String atalhoLei;
  private Long expHerdado;
  private boolean reescrita;
  private String parteLei;
  private TurnoConversa turnoDeOrigem;
  private boolean semTurno;

  /**
   * A consulta executada pela parte de dados aceita, depois do período da pergunta.
   *
   * @param consulta chamadas, na ordem em que rodaram
   */
  public synchronized void registrarConsulta(List<ChamadaAssinada> consulta) {
    this.consulta = consulta == null ? null : List.copyOf(consulta);
  }

  /** Esquece a consulta (parte de dados não aceita). */
  public synchronized void descartarConsulta() {
    this.consulta = null;
    this.textoDados = null;
  }

  /**
   * O texto de dados aceito, para o hash da autocorreção: o da rota (antes dos textos do backend,
   * do fundamento da retenção e da abertura) mais os textos do backend, na ordem.
   *
   * @param texto texto de dados normalizado pelo {@link HashDados}
   */
  public synchronized void registrarTextoDados(String texto) {
    this.textoDados = texto;
  }

  /**
   * Os textos livres que o template inseriu (título do pedido, motivo da devolução, descrição de
   * alerta e de item): as faixas deles vão na carga como {@code t}.
   *
   * @param textos textos livres
   */
  public synchronized void registrarLivres(List<String> textos) {
    textos.stream().filter(t -> t != null && !t.isBlank()).forEach(livres::add);
  }

  /**
   * A parte de lei da pergunta.
   *
   * @param classe      o que ela foi
   * @param textoModelo o texto do modelo aceito (com as referências), ou nulo
   * @param citacoes    citações da rota, {@code "norma|dispositivo"}
   */
  public synchronized void registrarLei(LeiAssinada.Classe classe, String textoModelo,
      List<String> citacoes) {
    this.classeLei = classe;
    this.textoModeloLei = textoModelo;
    this.citacoesLei = citacoes == null ? List.of() : List.copyOf(citacoes);
  }

  /** No MISTO, a pergunta legal separada ({@code lei.q}). */
  public synchronized void registrarPerguntaLei(String perguntaLei) {
    this.perguntaLei = perguntaLei;
  }

  /** O par saiu de uma reconferência com o modelo ({@code lei.k}). */
  public synchronized void marcarReconferida() {
    this.reconferida = true;
  }

  /** O atalho de lei respondido ({@code lei.a}). */
  public synchronized void registrarAtalhoLei(String atalho) {
    this.atalhoLei = atalho;
  }

  /** Validade herdada do par de origem (confirmação, contestação sem conteúdo, reconferência). */
  public synchronized void herdarValidade(long exp) {
    this.expHerdado = exp;
  }

  /** A pergunta considerada saiu de uma reescrita (cumulativo). */
  public synchronized void marcarReescrita() {
    this.reescrita = true;
  }

  /** O texto da parte de lei no MISTO, para o corte por parte. */
  public synchronized void registrarParteLei(String texto) {
    this.parteLei = texto;
  }

  /**
   * Texto fixo de lei: o {@code turno} é o par de origem, com os mesmos bytes.
   *
   * @param origem o par de origem
   */
  public synchronized void devolverOrigem(TurnoConversa origem) {
    this.turnoDeOrigem = origem;
  }

  /** Esta resposta não emite {@code turno} (falha da reexecução). */
  public synchronized void semTurno() {
    this.semTurno = true;
  }

  synchronized List<ChamadaAssinada> consulta() {
    return consulta;
  }

  synchronized String textoDados() {
    return textoDados;
  }

  synchronized List<String> livres() {
    return List.copyOf(livres);
  }

  synchronized LeiAssinada.Classe classeLei() {
    return classeLei;
  }

  synchronized String textoModeloLei() {
    return textoModeloLei;
  }

  synchronized List<String> citacoesLei() {
    return citacoesLei;
  }

  synchronized String perguntaLei() {
    return perguntaLei;
  }

  synchronized boolean reconferida() {
    return reconferida;
  }

  synchronized String atalhoLei() {
    return atalhoLei;
  }

  synchronized Long expHerdado() {
    return expHerdado;
  }

  synchronized boolean reescrita() {
    return reescrita;
  }

  synchronized String parteLei() {
    return parteLei;
  }

  synchronized TurnoConversa turnoDeOrigem() {
    return turnoDeOrigem;
  }

  synchronized boolean emiteTurno() {
    return !semTurno;
  }

  /** Sem texto. */
  @Override
  public String toString() {
    return "RegistroTurno";
  }
}
