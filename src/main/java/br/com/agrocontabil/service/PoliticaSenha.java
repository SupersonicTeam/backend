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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 *   <li>não pode conter o e-mail (nem a parte antes da arroba) nem o CPF/CNPJ do titular;
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
   * Valida a senha contra a política.
   *
   * @param senha   senha já {@linkplain #normalizar normalizada}
   * @param email   e-mail do titular, já normalizado
   * @param cpfCnpj documento do titular, só dígitos
   * @param campo   nome do campo no request, para o {@code field} do erro
   * @throws SenhaFracaException com uma entrada por regra violada
   */
  public void validar(String senha, String email, String cpfCnpj, String campo) {
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
          "A senha pode ter no maximo " + MAXIMO_BYTES
              + " bytes (cerca de 72 letras sem acento; letras acentuadas ocupam 2)."));
    }
    if (senha.codePoints().distinct().count() == 1) {
      violacoes.add(violacao(campo, "A senha nao pode ser um unico caractere repetido."));
    }

    String minuscula = senha.toLowerCase(Locale.ROOT);
    if (contemDadoDoTitular(minuscula, email, cpfCnpj)) {
      violacoes.add(violacao(campo, "A senha nao pode conter seu e-mail nem seu CPF/CNPJ."));
    }
    if (senhasComuns.contains(minuscula)) {
      violacoes.add(violacao(campo,
          "Esta senha esta entre as mais usadas e e facil de adivinhar. Escolha outra."));
    }

    if (!violacoes.isEmpty()) {
      throw new SenhaFracaException(violacoes);
    }
  }

  private static boolean contemDadoDoTitular(String senhaMinuscula, String email, String cpfCnpj) {
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
    return cpfCnpj != null && !cpfCnpj.isBlank() && senhaMinuscula.contains(cpfCnpj);
  }

  private static ErrorDetail violacao(String campo, String mensagem) {
    return new ErrorDetail(ErrorCode.SENHA_FRACA.code(), campo, mensagem,
        "Use uma frase longa e facil de lembrar, por exemplo quatro palavras soltas.", null, null);
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
