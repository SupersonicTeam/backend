package br.com.agrocontabil.service;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.exception.SenhaFracaException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Política de senha do cadastro e das trocas de senha (issue #205), no espírito da NIST SP
 * 800-63B §5.1.1.2: tamanho mínimo de verdade, sem regra de composição ("uma maiúscula, um
 * símbolo"), e recusa do que é fácil de adivinhar.
 *
 * <p>Regras:
 *
 * <ul>
 *   <li><strong>mínimo de {@value #MINIMO_CARACTERES} caracteres</strong> (contados por code point,
 *       então "ção" conta 3);
 *   <li><strong>máximo de {@value #MAXIMO_BYTES} bytes em UTF-8</strong>: é o limite real do BCrypt,
 *       que ignora em silêncio o que passa disso. Sem este teto, duas senhas longas com o mesmo
 *       começo seriam a mesma senha — e o usuário nunca saberia;
 *   <li>não pode ser um único caractere repetido;
 *   <li>não pode conter o e-mail (nem a parte antes da arroba) nem os outros dados de contexto do
 *       titular ({@link DadosDoTitular}): o CPF/CNPJ, o telefone (com e sem DDD) e, no contador, o
 *       CNPJ e o telefone do escritório (#521). Documentos e telefones são comparados sem pontuação
 *       nem espaço, dos dois lados: "529.982.247-25" e "(45) 99999-0000" dentro da senha contam
 *       como o CPF e o telefone;
 *   <li>não pode conter uma sequência de {@value #MINIMO_SEQUENCIA} ou mais caracteres seguidos
 *       crescente ou decrescente de dígitos ou de letras ("12345678901", "abcdefghij"), uma fileira
 *       do teclado ("qwerty", "asdfgh", "1q2w3e") nem ser um mesmo trecho repetido ("asdfasdfasdf")
 *       — NIST 800-63B pede barrar "sequential or repetitive characters" (#252);
 *   <li>não pode estar na lista de senhas comuns ({@code seguranca/senhas-comuns.txt}).
 * </ul>
 *
 * <p>Todas as violações são devolvidas de uma vez, para o usuário corrigir numa tentativa só. Os
 * itens de erro <strong>nunca</strong> levam a senha no {@code rejectedValue}.
 */
@Component
public class PoliticaSenha {

  /** Tamanho mínimo, em caracteres. */
  public static final int MINIMO_CARACTERES = 10;

  /** Tamanho máximo, em bytes UTF-8 — o que o BCrypt de fato considera. */
  public static final int MAXIMO_BYTES = 72;

  /** Abaixo disto a parte local do e-mail é curta demais para a regra "contém o e-mail" valer. */
  private static final int MINIMO_PARTE_LOCAL_EMAIL = 4;

  /**
   * Documento ou telefone com menos caracteres que isto (sem pontuação) não entra na regra "contém
   * o dado do titular": o número local sem DDD tem 8 dígitos, e um valor mais curto daria falso
   * positivo em senha legítima (#521).
   */
  static final int MINIMO_CARACTERES_DADO = 8;

  /** Tamanho do DDD, tirado do telefone para recusar também o número local (#521). */
  private static final int TAMANHO_DDD = 2;

  /** Código do Brasil, tirado do telefone gravado com ele (12 ou 13 dígitos). */
  private static final String CODIGO_PAIS = "55";

  /** Tudo o que não é letra nem dígito: a máscara de CPF/CNPJ e de telefone (#521). */
  private static final Pattern SEPARADORES = Pattern.compile("[^\\p{L}\\p{N}]");

  /** Tudo o que não é dígito ASCII. */
  private static final Pattern NAO_DIGITO = Pattern.compile("\\D");

  /** Mensagem da regra dos dados do titular: nunca diz qual dado nem o repete. */
  static final String MENSAGEM_DADO_DO_TITULAR = "A senha não pode conter seus dados de cadastro:"
      + " e-mail, CPF/CNPJ, telefone nem o CNPJ ou o telefone do escritório.";

  /** Tamanho da menor sequência (ou fileira de teclado) recusada. */
  public static final int MINIMO_SEQUENCIA = 6;

  /** Fileiras e diagonais de teclado (QWERTY, comparadas em minúsculas, nos dois sentidos). */
  private static final List<String> TECLADO = Stream.of(
          "qwertyuiop", "asdfghjkl", "zxcvbnm", "1q2w3e4r5t6y7u8i9o0p", "qazwsxedcrfvtgbyhnujmikolp",
          "1qaz2wsx3edc4rfv5tgb6yhn", "zaq12wsxcde34rfvbgt56yhn")
      .flatMap(f -> Stream.of(f, new StringBuilder(f).reverse().toString()))
      .toList();

  private static final String LISTA_SENHAS_COMUNS = "seguranca/senhas-comuns.txt";

  private final Set<String> senhasComuns;

  /** Carrega a lista de senhas comuns do classpath. */
  public PoliticaSenha() {
    this.senhasComuns = carregarSenhasComuns();
  }

  /**
   * Normaliza a senha antes de validar e de gerar o hash (NIST SP 800-63B §5.1.1.2).
   *
   * <p>NFKC faz "é" composto e "e" + acento combinante virarem a mesma sequência: sem isso, a mesma
   * senha digitada em teclados diferentes geraria hashes diferentes e o login falharia. Quem gera o
   * hash e quem confere a senha no login precisam chamar este método.
   *
   * @param senha senha como digitada; pode ser {@code null}
   * @return a senha normalizada, ou {@code null} se a entrada for nula
   */
  public static String normalizar(String senha) {
    return senha == null ? null : Normalizer.normalize(senha, Normalizer.Form.NFKC);
  }

  /**
   * Dados de contexto do titular que a senha não pode conter (NIST SP 800-63B §5.1.1.2,
   * "context-specific words"; #521).
   *
   * <p>Valores nulos ou em branco nas listas são ignorados: o contador pessoal não tem CNPJ de
   * escritório, e o telefone é opcional. O {@code toString} não mostra nenhum dado (LGPD).
   *
   * @param email      e-mail do titular, já normalizado; pode ser nulo
   * @param documentos CPF/CNPJ do titular e, no contador, o CNPJ do escritório; com ou sem máscara
   * @param telefones  telefone do titular e, no contador, o do escritório; com ou sem máscara
   */
  public record DadosDoTitular(String email, List<String> documentos, List<String> telefones) {

    /** Copia as listas sem os valores nulos ou em branco. */
    public DadosDoTitular {
      documentos = informados(documentos);
      telefones = informados(telefones);
    }

    private static List<String> informados(List<String> valores) {
      return valores == null ? List.of() : valores.stream()
          .filter(v -> v != null && !v.isBlank())
          .toList();
    }

    @Override
    public String toString() {
      return "DadosDoTitular[documentos=" + documentos.size() + ", telefones=" + telefones.size()
          + "]";
    }
  }

  /**
   * Valida a senha só com o e-mail e o documento do titular — o seed de dev e o da demonstração,
   * que não têm telefone nem escritório a conferir.
   *
   * @param senha   senha já {@linkplain #normalizar normalizada}
   * @param email   e-mail do titular, já normalizado
   * @param cpfCnpj documento do titular, com ou sem máscara
   * @param campo   nome do campo no request, para o {@code field} do erro
   * @throws SenhaFracaException com uma entrada por regra violada
   */
  public void validar(String senha, String email, String cpfCnpj, String campo) {
    validar(senha, new DadosDoTitular(email, Collections.singletonList(cpfCnpj), null), campo);
  }

  /**
   * Valida a senha contra a política.
   *
   * @param senha   senha já {@linkplain #normalizar normalizada}
   * @param titular dados de contexto do titular que a senha não pode conter
   * @param campo   nome do campo no request, para o {@code field} do erro
   * @throws SenhaFracaException com uma entrada por regra violada
   */
  public void validar(String senha, DadosDoTitular titular, String campo) {
    Objects.requireNonNull(titular, "titular");
    List<ErrorDetail> violacoes = new ArrayList<>();
    if (senha == null || senha.isEmpty()) {
      violacoes.add(violacao(campo, "Informe a senha."));
      throw new SenhaFracaException(violacoes);
    }

    if (senha.codePointCount(0, senha.length()) < MINIMO_CARACTERES) {
      violacoes.add(violacao(campo,
          "A senha precisa ter pelo menos " + MINIMO_CARACTERES + " caracteres."));
    }
    if (senha.getBytes(StandardCharsets.UTF_8).length > MAXIMO_BYTES) {
      violacoes.add(violacao(campo,
          "A senha pode ter no máximo " + MAXIMO_BYTES
              + " bytes (cerca de 72 letras sem acento; letras acentuadas ocupam 2)."));
    }
    if (senha.codePoints().distinct().count() == 1) {
      violacoes.add(violacao(campo, "A senha não pode ser um único caractere repetido."));
    }

    String minuscula = senha.toLowerCase(Locale.ROOT);
    if (contemDadoDoTitular(minuscula, titular)) {
      violacoes.add(violacao(campo, MENSAGEM_DADO_DO_TITULAR));
    }
    if (temSequenciaOuPadrao(minuscula)) {
      violacoes.add(violacao(campo,
          "A senha não pode ter sequências como 123456 ou abcdef, teclas em fila como qwerty "
              + "nem um mesmo trecho repetido.",
          "Troque por palavras sem relação entre si, por exemplo quatro palavras soltas."));
    }
    if (senhasComuns.contains(minuscula)) {
      violacoes.add(violacao(campo,
          "Esta senha está entre as mais usadas e é fácil de adivinhar. Escolha outra."));
    }

    if (!violacoes.isEmpty()) {
      throw new SenhaFracaException(violacoes);
    }
  }

  /**
   * Sequência crescente ou decrescente (dígitos com a volta 9-0, letras de a a z), fileira de
   * teclado ou trecho repetido. Recebe a senha já em minúsculas.
   */
  static boolean temSequenciaOuPadrao(String senha) {
    int[] pontos = senha.codePoints().toArray();
    return temSequencia(pontos) || temFileiraDeTeclado(senha) || ehTrechoRepetido(pontos);
  }

  private static boolean temSequencia(int[] pontos) {
    int crescente = 1;
    int decrescente = 1;
    for (int i = 1; i < pontos.length; i++) {
      crescente = passo(pontos[i - 1], pontos[i], 1) ? crescente + 1 : 1;
      decrescente = passo(pontos[i - 1], pontos[i], -1) ? decrescente + 1 : 1;
      if (crescente >= MINIMO_SEQUENCIA || decrescente >= MINIMO_SEQUENCIA) {
        return true;
      }
    }
    return false;
  }

  /** Verdadeiro se {@code atual} vem {@code delta} depois de {@code anterior}, na mesma classe. */
  private static boolean passo(int anterior, int atual, int delta) {
    if (anterior >= '0' && anterior <= '9' && atual >= '0' && atual <= '9') {
      return Math.floorMod(anterior - '0' + delta, 10) == atual - '0';
    }
    return anterior >= 'a' && anterior <= 'z' && atual >= 'a' && atual <= 'z'
        && anterior + delta == atual;
  }

  private static boolean temFileiraDeTeclado(String senha) {
    for (String fileira : TECLADO) {
      for (int i = 0; i + MINIMO_SEQUENCIA <= fileira.length(); i++) {
        if (senha.contains(fileira.substring(i, i + MINIMO_SEQUENCIA))) {
          return true;
        }
      }
    }
    return false;
  }

  /** A senha inteira é um trecho de 2+ caracteres distintos repetido ("asdfasdfasdf"). */
  private static boolean ehTrechoRepetido(int[] pontos) {
    int n = pontos.length;
    for (int tamanho = 2; tamanho <= n / 2; tamanho++) {
      if (n % tamanho != 0) {
        continue;
      }
      boolean repete = true;
      for (int i = tamanho; i < n && repete; i++) {
        repete = pontos[i] == pontos[i - tamanho];
      }
      if (repete && Arrays.stream(pontos, 0, tamanho).distinct().count() > 1) {
        return true;
      }
    }
    return false;
  }

  private static boolean contemDadoDoTitular(String senhaMinuscula, DadosDoTitular titular) {
    String email = titular.email();
    if (email != null && !email.isBlank()) {
      if (senhaMinuscula.contains(email)) {
        return true;
      }
      int arroba = email.indexOf('@');
      String parteLocal = arroba > 0 ? email.substring(0, arroba) : email;
      if (parteLocal.length() >= MINIMO_PARTE_LOCAL_EMAIL && senhaMinuscula.contains(parteLocal)) {
        return true;
      }
    }
    // A senha sem pontuacao e sem espacos: "529.982.247-25" e "(45) 99999-0000" dentro dela viram
    // os digitos gravados.
    String senhaSemSeparadores = semSeparadores(senhaMinuscula);
    return titular.documentos().stream()
        .map(documento -> semSeparadores(documento.toLowerCase(Locale.ROOT)))
        .anyMatch(documento -> contemDado(senhaSemSeparadores, documento))
        || titular.telefones().stream()
            .flatMap(telefone -> formasDoTelefone(telefone).stream())
            .anyMatch(telefone -> contemDado(senhaSemSeparadores, telefone));
  }

  private static boolean contemDado(String senhaSemSeparadores, String dado) {
    return dado.length() >= MINIMO_CARACTERES_DADO && senhaSemSeparadores.contains(dado);
  }

  /**
   * O telefone só com dígitos, sem o código do país, e o número local sem o DDD: "45999990000" dá
   * "45999990000" e "999990000"; "(65) 3333-0001" dá "6533330001" e "33330001".
   */
  static List<String> formasDoTelefone(String telefone) {
    String digitos = NAO_DIGITO.matcher(telefone).replaceAll("");
    if ((digitos.length() == 12 || digitos.length() == 13) && digitos.startsWith(CODIGO_PAIS)) {
      digitos = digitos.substring(CODIGO_PAIS.length());
    }
    if (digitos.length() == 10 || digitos.length() == 11) {
      return List.of(digitos, digitos.substring(TAMANHO_DDD));
    }
    return List.of(digitos);
  }

  private static String semSeparadores(String texto) {
    return SEPARADORES.matcher(texto).replaceAll("");
  }

  private static ErrorDetail violacao(String campo, String mensagem) {
    return violacao(campo, mensagem,
        "Use uma frase longa e fácil de lembrar, por exemplo quatro palavras soltas.");
  }

  private static ErrorDetail violacao(String campo, String mensagem, String sugestao) {
    return new ErrorDetail(ErrorCode.SENHA_FRACA.code(), campo, mensagem, sugestao, null, null);
  }

  private static Set<String> carregarSenhasComuns() {
    Set<String> senhas = new HashSet<>();
    try (InputStream entrada = new ClassPathResource(LISTA_SENHAS_COMUNS).getInputStream();
        BufferedReader leitor =
            new BufferedReader(new InputStreamReader(entrada, StandardCharsets.UTF_8))) {
      String linha;
      while ((linha = leitor.readLine()) != null) {
        String senha = linha.strip();
        if (!senha.isEmpty() && !senha.startsWith("#")) {
          senhas.add(normalizar(senha).toLowerCase(Locale.ROOT));
        }
      }
    } catch (IOException e) {
      // Sem a lista a politica ficaria mais fraca em silencio: melhor nao subir.
      throw new UncheckedIOException("Lista de senhas comuns ausente: " + LISTA_SENHAS_COMUNS, e);
    }
    return Set.copyOf(senhas);
  }
}
