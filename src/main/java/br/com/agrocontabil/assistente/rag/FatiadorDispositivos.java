package br.com.agrocontabil.assistente.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Fatia o texto compilado de uma norma (HTML do Planalto) em {@link DispositivoLegal dispositivos}
 * (issue #156).
 *
 * <p><strong>Um trecho por dispositivo, nunca por número fixo de caracteres:</strong> a citação da
 * Ceres é o dispositivo ("LC 214/2025, art. 138, § 1º, inciso IV"). Um corte por tamanho partiria
 * artigos ao meio e juntaria dois dispositivos num trecho, e a citação deixaria de ser verdadeira.
 *
 * <p>Regras:
 * <ul>
 *   <li>texto <strong>tachado</strong> ({@code strike}, {@code s}, {@code del}) é redação revogada
 *       no compilado do Planalto — descartado;</li>
 *   <li>anotações editoriais ("(Redação dada pela...)", "(Incluído pela...)", "(Vide...)") saem do
 *       texto: não são lei;</li>
 *   <li>cada dispositivo leva o texto de quem o contém (o caput antes do inciso), porque um inciso
 *       sozinho não diz do que trata;</li>
 *   <li>em emenda constitucional, o texto entre aspas é a nova redação de outro dispositivo; a
 *       referência fica "art. 1º (redação do art. 156-A, § 1º)" — o artigo da emenda que a deu e o
 *       dispositivo que ela alterou;</li>
 *   <li>títulos, capítulos e seções não viram trecho; texto antes do primeiro artigo (ementa,
 *       preâmbulo) é ignorado;</li>
 *   <li>o texto articulado termina na assinatura ("Brasília, 16 de janeiro de 2025; ..."):
 *       assinaturas e a nota "Este texto não substitui o publicado no DOU" não são lei;</li>
 *   <li><strong>anexos</strong> (cabeçalho "ANEXO IX" num parágrafo próprio, título e tabela): cada
 *       linha numerada da tabela vira um trecho "Anexo IX, item 5", com o título do anexo, e a
 *       Ceres cita o item exato — é nos anexos que estão os produtos e os NCM de cada redução. Sem
 *       isso o último artigo engolia todos os anexos (mais de 90 mil caracteres na LC 214).</li>
 * </ul>
 */
@Component
public class FatiadorDispositivos {

  private static final Pattern ARTIGO = Pattern.compile(
      "^Art\\.\\s*(\\d+)\\s*[º°]?\\s*(-\\s*[A-Z](?![a-z]))?\\s*\\.?\\s*(.*)$");
  private static final Pattern PARAGRAFO_UNICO = Pattern.compile(
      "^Par[áa]grafo\\s+[úu]nico\\s*\\.?\\s*[-–—]?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern PARAGRAFO = Pattern.compile(
      "^§\\s*(\\d+)\\s*[º°]?\\s*(-\\s*[A-Z](?![a-z]))?\\s*\\.?\\s*(.*)$");
  /** Só numeral romano válido: "CIVIL - ..." ou "MIL - ..." não são incisos. */
  private static final Pattern INCISO = Pattern.compile(
      "^(M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3}))(-[A-Z])?\\s*[-–—]\\s*(.*)$");
  private static final Pattern ALINEA = Pattern.compile("^([a-z])\\)\\s*(.*)$");
  private static final Pattern TITULO = Pattern.compile(
      "^(T[ÍI]TULO|CAP[ÍI]TULO|SE[ÇC][ÃA]O|Se[çc][ãa]o|SUBSE[ÇC][ÃA]O|Subse[çc][ãa]o|LIVRO|PARTE)\\b.*");
  private static final Pattern ANOTACAO = Pattern.compile(
      "\\((?:Reda[çc][ãa]o dada|Inclu[íi]d[oa]|Vide|Revogad[oa]|Produ[çc][ãa]o de efeito"
          + "|Vig[êe]ncia|Regulamento|Promulga[çc][ãa]o)[^)]*\\)",
      Pattern.CASE_INSENSITIVE);
  /**
   * Anotações entre parênteses depois do fechamento das aspas: "(NR)", "(AC)" e qualquer outra que
   * o Planalto acrescente. Removidas antes de conferir se a linha fecha a citação — sem isso uma
   * anotação não prevista deixaria a citação aberta e o resto da norma seria rotulado como
   * redação citada.
   */
  private static final Pattern MARCA_ALTERACAO =
      Pattern.compile("([\"”’'])(?:\\s*\\([^()]*\\))+\\s*[.;]?\\s*$");
  private static final Pattern SO_PONTOS = Pattern.compile("^[.…\\s\"“”]*$");
  /** Cabeçalho de anexo: o parágrafo inteiro é "ANEXO" + numeral romano. */
  private static final Pattern ANEXO = Pattern.compile("^ANEXO\\s+([IVXLC]+)$");
  /** Local e data da assinatura: fim do texto articulado. */
  private static final Pattern ASSINATURA = Pattern.compile(
      "^Bras[íi]lia\\s*,\\s*\\d{1,2}[ºo°]?\\s+de\\s+\\p{L}+\\s+de\\s+\\d{4}.*");
  /**
   * Maior salto aceito entre dois artigos da própria norma. Acima disso (ou numeração voltando) o
   * artigo é texto citado sem aspa de abertura: na LC 227/2026, um "Art. 493-A" inserido na LC 214
   * logo depois do art. 174 da própria LC 227 virava artigo dela e rotulava errado 48 trechos.
   */
  private static final int SALTO_MAXIMO_ARTIGO = 30;
  /** Número de item de anexo ("5", "1.2"). */
  private static final Pattern NUMERO_ITEM = Pattern.compile("^\\d+(?:\\.\\d+)*$");
  private static final String ABRE_ASPAS = "\"“‘'";
  private static final String FECHA_ASPAS = "\"”’'";

  /**
   * Fatia o HTML de uma norma.
   *
   * @param html conteúdo da página (ex.: texto compilado do Planalto)
   * @return dispositivos na ordem do texto
   */
  public List<DispositivoLegal> fatiar(String html) {
    return fatiar(Jsoup.parse(html));
  }

  /**
   * Fatia um documento já lido (o chamador escolhe o charset — o Planalto usa windows-1252).
   *
   * @param documento documento HTML
   * @return dispositivos na ordem do texto
   */
  public List<DispositivoLegal> fatiar(Document documento) {
    documento.select("strike, s, del, script, style").remove();
    List<String> linhas = new ArrayList<>();
    List<Element> paragrafos = documento.select("p");
    int inicioAnexos = paragrafos.size();
    if (paragrafos.isEmpty()) {
      for (String linha : documento.body().wholeText().split("\\R")) {
        linhas.add(linha);
      }
    } else {
      // Anexos so depois da assinatura: um "ANEXO I" antes dela e texto citado (ex.: emenda que
      // altera o anexo de outra lei), e cortar ali descartaria o resto da norma. Sem assinatura,
      // vale o primeiro cabecalho.
      int assinatura = -1;
      for (int i = 0; i < paragrafos.size() && assinatura < 0; i++) {
        if (ASSINATURA.matcher(limpar(paragrafos.get(i).text())).matches()) {
          assinatura = i;
        }
      }
      for (int i = 0; i < paragrafos.size(); i++) {
        if (i > assinatura && ANEXO.matcher(limpar(paragrafos.get(i).text())).matches()) {
          inicioAnexos = i;
          break;
        }
        linhas.add(paragrafos.get(i).text());
      }
    }
    Estado estado = new Estado();
    for (String bruta : linhas) {
      estado.processar(limpar(bruta));
    }
    fatiarAnexos(paragrafos.subList(inicioAnexos, paragrafos.size()), estado);
    return estado.resultado;
  }

  /**
   * Anexos: cabeçalho ("ANEXO IX"), título (parágrafos fora de tabela até a tabela) e uma linha de
   * tabela por item. Linha cuja primeira célula não é número (cabeçalho "ITEM | DESCRIÇÃO" ou
   * continuação) não abre item: a continuação vai para o item anterior, o cabeçalho é descartado.
   */
  private static void fatiarAnexos(List<Element> paragrafos, Estado estado) {
    String anexo = null;
    StringBuilder titulo = new StringBuilder();
    boolean temItem = false;
    Set<Element> linhasVistas = new HashSet<>();
    for (Element p : paragrafos) {
      String texto = limpar(p.text());
      Matcher m = ANEXO.matcher(texto);
      if (m.matches()) {
        anexo = "Anexo " + m.group(1);
        titulo.setLength(0);
        temItem = false;
        continue;
      }
      if (anexo == null || texto.isEmpty()) {
        continue;
      }
      Element linha = p.closest("tr");
      if (linha == null) {
        if (temItem) {
          estado.anexarAoUltimo(texto); // nota depois da tabela
        } else {
          titulo.append(titulo.isEmpty() ? "" : " ").append(texto);
        }
        continue;
      }
      if (!linhasVistas.add(linha)) {
        continue; // outra celula da mesma linha
      }
      List<String> celulas = new ArrayList<>();
      for (Element celula : linha.select("td, th")) {
        String conteudo = limpar(celula.text());
        if (!conteudo.isEmpty()) {
          celulas.add(conteudo);
        }
      }
      if (celulas.size() >= 2 && NUMERO_ITEM.matcher(celulas.get(0)).matches()) {
        String cabecalho = anexo.toUpperCase(Locale.ROOT)
            + (titulo.isEmpty() ? "" : " - " + titulo);
        estado.emitir(anexo + ", item " + celulas.get(0), cabecalho + ". Item " + celulas.get(0)
            + ": " + String.join(" - ", celulas.subList(1, celulas.size())));
        temItem = true;
      } else if (temItem && !celulas.isEmpty()) {
        estado.anexarAoUltimo(String.join(" - ", celulas));
      }
    }
  }

  private static String limpar(String linha) {
    String texto = linha.replace(' ', ' ');
    texto = ANOTACAO.matcher(texto).replaceAll("");
    return texto.replaceAll("\\s+", " ").trim();
  }

  /** Numeral ordinal de artigo/parágrafo: 1º a 9º, depois cardinal (convenção legislativa). */
  private static String ordinal(String numero, String sufixo) {
    String base = Integer.parseInt(numero) < 10 ? numero + "º" : numero;
    return sufixo == null ? base : base + sufixo.replaceAll("\\s", "");
  }

  /** Estrutura em que o fatiador está: artigo, parágrafo, inciso, alínea (e o mesmo para a citação). */
  private static final class Quadro {
    String artigo;
    String caput;
    String paragrafo;
    String textoParagrafo;
    String inciso;
    String textoInciso;

    void novoArtigo(String rotulo, String texto) {
      artigo = rotulo;
      caput = texto;
      paragrafo = null;
      textoParagrafo = null;
      inciso = null;
      textoInciso = null;
    }

    void novoParagrafo(String rotulo, String texto) {
      paragrafo = rotulo;
      textoParagrafo = texto;
      inciso = null;
      textoInciso = null;
    }

    void novoInciso(String rotulo, String texto) {
      inciso = rotulo;
      textoInciso = texto;
    }

    String caminho(String... finais) {
      StringBuilder sb = new StringBuilder(artigo);
      for (String parte : new String[] {paragrafo, inciso}) {
        if (parte != null) {
          sb.append(", ").append(parte);
        }
      }
      for (String parte : finais) {
        sb.append(", ").append(parte);
      }
      return sb.toString();
    }

    String contexto() {
      StringBuilder sb = new StringBuilder(caput);
      if (textoParagrafo != null) {
        sb.append(' ').append(textoParagrafo);
      }
      if (textoInciso != null) {
        sb.append(' ').append(textoInciso);
      }
      return sb.toString();
    }
  }

  private static final class Estado {
    final List<DispositivoLegal> resultado = new ArrayList<>();
    final Map<String, Integer> ocorrencias = new HashMap<>();
    final Quadro norma = new Quadro();
    final Quadro citado = new Quadro();
    boolean citando;
    /** Passou da assinatura: o resto (até os anexos) não é texto articulado. */
    boolean encerrado;
    /** Número do último artigo da própria norma (0 antes do primeiro). */
    int ultimoArtigo;

    void processar(String linha) {
      if (encerrado || linha.isEmpty() || SO_PONTOS.matcher(linha).matches()) {
        return;
      }
      if (!citando && norma.artigo != null && ASSINATURA.matcher(linha).matches()) {
        encerrado = true;
        return;
      }
      boolean abre = ABRE_ASPAS.indexOf(linha.charAt(0)) >= 0;
      // So parenteses DEPOIS das aspas saem: "... 60% (sessenta por cento)." e texto de lei.
      String semMarca = MARCA_ALTERACAO.matcher(linha).replaceAll("$1").trim();
      boolean fecha = !semMarca.isEmpty()
          && FECHA_ASPAS.indexOf(semMarca.charAt(semMarca.length() - 1)) >= 0;
      String texto = semMarca;
      if (abre) {
        texto = texto.substring(1).trim();
      }
      if (fecha && !texto.isEmpty()) {
        texto = texto.substring(0, texto.length() - 1).trim();
      }
      if (abre && norma.artigo != null) {
        citando = true;
      }
      if (texto.isEmpty() || TITULO.matcher(texto).matches()) {
        encerrarCitacao(fecha);
        return;
      }
      if (citando) {
        processarCitado(texto);
      } else {
        processarNorma(texto);
      }
      encerrarCitacao(fecha);
    }

    private void encerrarCitacao(boolean fecha) {
      if (fecha && citando) {
        citando = false;
        citado.artigo = null;
      }
    }

    private void processarNorma(String texto) {
      Matcher m = ARTIGO.matcher(texto);
      if (m.matches()) {
        int numero = Integer.parseInt(m.group(1));
        if (norma.artigo != null
            && (numero < ultimoArtigo || numero > ultimoArtigo + SALTO_MAXIMO_ARTIGO)) {
          // Artigo citado sem a aspa de abertura (o Planalto a omite as vezes logo depois de um
          // "(NR)"): os artigos da propria norma so crescem, e de pouco em pouco.
          citando = true;
          processarCitado(texto);
          return;
        }
        ultimoArtigo = numero;
        if (m.group(3).isBlank()) {
          return; // artigo inteiramente revogado (so texto tachado)
        }
        String rotulo = "art. " + ordinal(m.group(1), m.group(2));
        norma.novoArtigo(rotulo, texto);
        emitir(rotulo, texto);
        return;
      }
      if (norma.artigo == null) {
        return; // ementa, preambulo
      }
      if (!emitirSubdivisao(norma, texto, null)) {
        anexarAoUltimo(texto);
      }
    }

    private void processarCitado(String texto) {
      Matcher m = ARTIGO.matcher(texto);
      if (m.matches()) {
        if (m.group(3).isBlank()) {
          return;
        }
        citado.novoArtigo("art. " + ordinal(m.group(1), m.group(2)), texto);
        emitir(rotuloCitado(citado.caminho()), norma.caput + " " + texto);
        return;
      }
      if (citado.artigo == null) {
        // Texto citado sem artigo proprio (ex.: nova redacao de um paragrafo avulso): fica no artigo
        // da norma que o introduz.
        anexarAoUltimo(texto);
        return;
      }
      if (!emitirSubdivisao(citado, texto, norma.caput)) {
        anexarAoUltimo(texto);
      }
    }

    /**
     * Parágrafo, inciso ou alínea do quadro; {@code false} se a linha não é nenhum deles. Um
     * dispositivo sem texto próprio (inteiramente revogado, só tachado) é consumido sem virar
     * trecho — senão a redação vigente logo abaixo ganharia um rótulo duplicado.
     */
    private boolean emitirSubdivisao(Quadro quadro, String texto, String prefixo) {
      if (semTextoProprio(texto)) {
        return true;
      }
      Matcher m = PARAGRAFO_UNICO.matcher(texto);
      String rotulo;
      if (m.matches()) {
        quadro.novoParagrafo("parágrafo único", texto);
        rotulo = quadro.caminho();
      } else if ((m = PARAGRAFO.matcher(texto)).matches()) {
        quadro.novoParagrafo("§ " + ordinal(m.group(1), m.group(2)), texto);
        rotulo = quadro.caminho();
      } else if ((m = INCISO.matcher(texto)).matches()) {
        String numero = m.group(2) == null ? m.group(1) : m.group(1) + m.group(2);
        quadro.novoInciso("inciso " + numero, texto);
        rotulo = quadro.caminho();
      } else if ((m = ALINEA.matcher(texto)).matches() && quadro.inciso != null) {
        rotulo = quadro.caminho("alínea " + m.group(1));
        emitir(quadro == citado ? rotuloCitado(rotulo) : rotulo,
            juntar(prefixo, quadro.contexto() + " " + texto));
        return true;
      } else {
        return false;
      }
      String contexto = quadro.contexto();
      emitir(quadro == citado ? rotuloCitado(rotulo) : rotulo, juntar(prefixo, contexto));
      return true;
    }

    private static boolean semTextoProprio(String texto) {
      Matcher m;
      if ((m = PARAGRAFO_UNICO.matcher(texto)).matches()) {
        return m.group(1).isBlank();
      }
      if ((m = PARAGRAFO.matcher(texto)).matches() || (m = INCISO.matcher(texto)).matches()) {
        return m.group(3).isBlank();
      }
      if ((m = ALINEA.matcher(texto)).matches()) {
        return m.group(2).isBlank();
      }
      return false;
    }

    private String rotuloCitado(String caminhoCitado) {
      return norma.artigo + " (redação do " + caminhoCitado + ")";
    }

    private static String juntar(String prefixo, String texto) {
      return prefixo == null ? texto : prefixo + " " + texto;
    }

    void emitir(String rotulo, String texto) {
      int vez = ocorrencias.merge(rotulo, 1, Integer::sum);
      // Rotulo repetido no mesmo texto (ex.: o mesmo dispositivo citado em duas partes da emenda):
      // cada trecho precisa de referencia propria para a vigencia e a citacao nao se confundirem.
      String unico = vez == 1 ? rotulo : rotulo + " [" + vez + "]";
      resultado.add(new DispositivoLegal(unico, texto));
    }

    void anexarAoUltimo(String texto) {
      if (resultado.isEmpty()) {
        return;
      }
      DispositivoLegal ultimo = resultado.remove(resultado.size() - 1);
      resultado.add(new DispositivoLegal(ultimo.dispositivo(), ultimo.texto() + " " + texto));
    }
  }
}
