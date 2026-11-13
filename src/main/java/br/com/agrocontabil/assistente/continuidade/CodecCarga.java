package br.com.agrocontabil.assistente.continuidade;

import br.com.agrocontabil.assistente.continuidade.CargaTurno.Faixa;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lê e escreve a carga assinada (#499) em JSON compacto. A leitura é estrita: campo repetido,
 * campo desconhecido, tipo errado ou valor fora da forma recusam a carga inteira (a conferência
 * ignora então o histórico todo). Nenhuma falha vai a log com o valor.
 */
public final class CodecCarga {

  /** Tamanho máximo da carga, em bytes (o mesmo teto da assinatura). */
  public static final int TAMANHO_MAXIMO = 1500;

  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build());
  private static final JsonNodeFactory NOS = JsonNodeFactory.instance;
  private static final Set<String> CHAVES = Set.of(
      "exp", "rota", "reescrita", "consulta", "dados", "t", "lei");
  private static final Set<String> CHAVES_LEI = Set.of("d", "r", "c", "q", "s", "k", "a");
  private static final Set<String> CHAVES_CHAMADA = Set.of("f", "a");
  private static final Set<String> ROTAS = Set.of("DADOS", "LEGISLACAO", "MISTO");
  private static final Pattern HASH = Pattern.compile("[A-Za-z0-9_-]{22}");
  private static final Pattern NOME_FUNCAO = Pattern.compile("[A-Za-z]{1,40}");
  private static final Pattern NOME_ATALHO = Pattern.compile("[A-Z_]{1,40}");

  private CodecCarga() {
  }

  /**
   * Escreve a carga em JSON compacto, aplicando os cortes de tamanho nesta ordem até caber em
   * {@value #TAMANHO_MAXIMO} bytes: sai {@code lei.c}, {@code t} vira {@code "*"}, {@code q} vira
   * {@code s} e saem {@code consulta}/{@code dados}.
   *
   * @param carga carga
   * @return o JSON, ou vazio se nem com os cortes coube
   */
  public static Optional<String> escrever(CargaTurno carga) {
    List<CargaTurno> tentativas = new ArrayList<>();
    tentativas.add(carga);
    CargaTurno atual = carga.semCitacoesDeLei();
    tentativas.add(atual);
    atual = atual.comLivresTodos();
    tentativas.add(atual);
    atual = atual.semPerguntaLei();
    tentativas.add(atual);
    atual = atual.semConsulta();
    tentativas.add(atual);
    for (CargaTurno tentativa : tentativas) {
      String json = json(tentativa);
      if (json.getBytes(StandardCharsets.UTF_8).length <= TAMANHO_MAXIMO) {
        return Optional.of(json);
      }
    }
    return Optional.empty();
  }

  /** A carga como JSON, sem cortes. */
  static String json(CargaTurno carga) {
    ObjectNode raiz = NOS.objectNode();
    raiz.put("exp", carga.exp());
    raiz.put("rota", carga.rota().name());
    raiz.put("reescrita", carga.reescrita());
    if (carga.consulta() != null) {
      ArrayNode consulta = raiz.putArray("consulta");
      for (ChamadaAssinada c : carga.consulta()) {
        ObjectNode chamada = consulta.addObject();
        chamada.put("f", c.funcao());
        chamada.set("a", c.argumentosComoObjeto());
      }
    }
    if (carga.dados() != null) {
      raiz.put("dados", carga.dados());
    }
    if (carga.livresTodos()) {
      raiz.put("t", "*");
    } else if (carga.livres() != null) {
      ArrayNode livres = raiz.putArray("t");
      carga.livres().forEach(f -> livres.addArray().add(f.inicio()).add(f.fim()));
    }
    if (carga.lei() != null) {
      LeiAssinada lei = carga.lei();
      ObjectNode no = raiz.putObject("lei");
      no.put("d", lei.classe().name());
      if (lei.textoModelo() != null) {
        no.putArray("r").add(lei.textoModelo().inicio()).add(lei.textoModelo().fim());
      }
      if (lei.citacoes() != null) {
        ArrayNode citacoes = no.putArray("c");
        lei.citacoes().forEach(citacoes::add);
      }
      if (lei.perguntaLei() != null) {
        no.put("q", lei.perguntaLei());
      }
      if (lei.semPergunta()) {
        no.put("s", true);
      }
      if (lei.reconferida()) {
        no.put("k", true);
      }
      if (lei.atalho() != null) {
        no.put("a", lei.atalho());
      }
    }
    try {
      return JSON.writeValueAsString(raiz);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("carga", e);
    }
  }

  /**
   * Lê a carga, estrita. Só a forma: validade, rota e faixas contra a resposta são da conferência.
   *
   * @param json carga já autenticada pelo MAC
   * @return a carga, ou vazio se a forma não confere
   */
  public static Optional<CargaTurno> ler(String json) {
    try {
      JsonNode raiz = JSON.readTree(json);
      return Optional.ofNullable(raiz).flatMap(CodecCarga::carga);
    } catch (JsonProcessingException | RuntimeException e) {
      // So o fato: o token lido iria junto com a mensagem do Jackson.
      return Optional.empty();
    }
  }

  private static Optional<CargaTurno> carga(JsonNode raiz) {
    if (!raiz.isObject() || !somenteChaves(raiz, CHAVES)) {
      return Optional.empty();
    }
    JsonNode exp = raiz.get("exp");
    JsonNode rota = raiz.get("rota");
    JsonNode reescrita = raiz.get("reescrita");
    if (exp == null || !exp.isIntegralNumber() || !exp.canConvertToLong()
        || rota == null || !rota.isTextual() || !ROTAS.contains(rota.asText())
        || reescrita == null || !reescrita.isBoolean()) {
      return Optional.empty();
    }
    List<ChamadaAssinada> consulta = null;
    if (raiz.has("consulta")) {
      Optional<List<ChamadaAssinada>> lida = consulta(raiz.get("consulta"));
      if (lida.isEmpty()) {
        return Optional.empty();
      }
      consulta = lida.get();
    }
    String dados = null;
    if (raiz.has("dados")) {
      JsonNode no = raiz.get("dados");
      if (!no.isTextual() || !HASH.matcher(no.asText()).matches()) {
        return Optional.empty();
      }
      dados = no.asText();
    }
    List<Faixa> livres = null;
    boolean livresTodos = false;
    if (raiz.has("t")) {
      JsonNode t = raiz.get("t");
      if (t.isTextual() && "*".equals(t.asText())) {
        livresTodos = true;
      } else {
        Optional<List<Faixa>> lidas = faixas(t, CargaTurno.MAXIMO_LIVRES);
        if (lidas.isEmpty()) {
          return Optional.empty();
        }
        livres = lidas.get();
      }
    }
    LeiAssinada lei = null;
    if (raiz.has("lei")) {
      Optional<LeiAssinada> lida = lei(raiz.get("lei"));
      if (lida.isEmpty()) {
        return Optional.empty();
      }
      lei = lida.get();
    }
    return Optional.of(new CargaTurno(exp.asLong(), Intencao.valueOf(rota.asText()),
        reescrita.asBoolean(), consulta, dados, livres, livresTodos, lei));
  }

  private static Optional<List<ChamadaAssinada>> consulta(JsonNode no) {
    if (!no.isArray() || no.isEmpty() || no.size() > CargaTurno.MAXIMO_CHAMADAS) {
      return Optional.empty();
    }
    List<ChamadaAssinada> chamadas = new ArrayList<>();
    for (JsonNode item : no) {
      if (!item.isObject() || !somenteChaves(item, CHAVES_CHAMADA) || item.size() != 2) {
        return Optional.empty();
      }
      JsonNode f = item.get("f");
      JsonNode a = item.get("a");
      if (f == null || !f.isTextual() || !NOME_FUNCAO.matcher(f.asText()).matches()
          || a == null || !a.isObject()) {
        return Optional.empty();
      }
      chamadas.add(ChamadaAssinada.deNo(f.asText(), a));
    }
    return Optional.of(chamadas);
  }

  private static Optional<LeiAssinada> lei(JsonNode no) {
    if (!no.isObject() || !somenteChaves(no, CHAVES_LEI)) {
      return Optional.empty();
    }
    JsonNode d = no.get("d");
    if (d == null || !d.isTextual()) {
      return Optional.empty();
    }
    LeiAssinada.Classe classe;
    try {
      classe = LeiAssinada.Classe.valueOf(d.asText());
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    Faixa r = null;
    if (no.has("r")) {
      Optional<Faixa> faixa = faixa(no.get("r"));
      if (faixa.isEmpty()) {
        return Optional.empty();
      }
      r = faixa.get();
    }
    List<String> citacoes = null;
    if (no.has("c")) {
      JsonNode c = no.get("c");
      if (!c.isArray() || c.size() > LeiAssinada.MAXIMO_CITACOES || r != null) {
        return Optional.empty();
      }
      citacoes = new ArrayList<>();
      for (JsonNode item : c) {
        if (!item.isTextual() || item.asText().indexOf('|') <= 0) {
          return Optional.empty();
        }
        citacoes.add(item.asText());
      }
    }
    String q = null;
    if (no.has("q")) {
      JsonNode qNo = no.get("q");
      if (!qNo.isTextual() || qNo.asText().isBlank()
          || qNo.asText().length() > LeiAssinada.MAXIMO_PERGUNTA) {
        return Optional.empty();
      }
      q = qNo.asText();
    }
    boolean s = verdadeiro(no, "s");
    boolean k = verdadeiro(no, "k");
    if ((no.has("s") && !s) || (no.has("k") && !k) || (s && q != null)) {
      return Optional.empty();
    }
    String a = null;
    if (no.has("a")) {
      JsonNode aNo = no.get("a");
      if (!aNo.isTextual() || !NOME_ATALHO.matcher(aNo.asText()).matches()) {
        return Optional.empty();
      }
      a = aNo.asText();
    }
    return Optional.of(new LeiAssinada(classe, r, citacoes, q, s, k, a));
  }

  private static boolean verdadeiro(JsonNode no, String chave) {
    JsonNode valor = no.get(chave);
    return valor != null && valor.isBoolean() && valor.asBoolean();
  }

  private static Optional<List<Faixa>> faixas(JsonNode no, int maximo) {
    if (!no.isArray() || no.size() > maximo) {
      return Optional.empty();
    }
    List<Faixa> faixas = new ArrayList<>();
    for (JsonNode item : no) {
      Optional<Faixa> faixa = faixa(item);
      if (faixa.isEmpty()) {
        return Optional.empty();
      }
      faixas.add(faixa.get());
    }
    return Optional.of(faixas);
  }

  private static Optional<Faixa> faixa(JsonNode no) {
    if (!no.isArray() || no.size() != 2 || !no.get(0).isInt() || !no.get(1).isInt()) {
      return Optional.empty();
    }
    int inicio = no.get(0).asInt();
    int fim = no.get(1).asInt();
    return inicio < 0 || fim < inicio ? Optional.empty() : Optional.of(new Faixa(inicio, fim));
  }

  private static boolean somenteChaves(JsonNode objeto, Set<String> permitidas) {
    Iterator<String> nomes = objeto.fieldNames();
    while (nomes.hasNext()) {
      if (!permitidas.contains(nomes.next())) {
        return false;
      }
    }
    return true;
  }

  /** Para os testes: o leitor estrito recusa campo repetido. */
  static boolean leituraEstrita() {
    return JSON.getFactory().isEnabled(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  }
}
