package br.com.agrocontabil.assistente;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Confere que todo número da resposta da Ceres veio de uma fonte conhecida (issue #142).
 *
 * <p>"Nenhum número é gerado pelo modelo" é a promessa central da camada de Tool Calling. O prompt
 * pede isso; este componente <strong>verifica</strong>. Um número da resposta só é aceito se
 * aparece numa das fontes: o resultado das funções, a pergunta do usuário ou a instrução de
 * sistema. Um valor arredondado, somado ou inventado pelo modelo não passa.
 *
 * <p>Como compara:
 * <ul>
 *   <li><strong>datas</strong> ({@code 31/07/2026}, {@code 2026-07-31}) e <strong>meses</strong>
 *       ({@code 07/2026}, {@code 2026-07}) são comparados como data, não como três números soltos;
 *       mês e ano de uma data conhecida também valem sozinhos ("julho de 2026");</li>
 *   <li><strong>números</strong> são comparados pelo valor, aceitando as grafias pt-BR e en
 *       ({@code 1.234,56} = {@code 1234.56}); marcadores de lista ({@code "1. "}) são ignorados.</li>
 * </ul>
 */
@Component
public class ValidadorNumeros {

  private static final Pattern DATA_BR =
      Pattern.compile("(?<![\\d/])(\\d{1,2})/(\\d{1,2})/(\\d{4})(?![\\d/])");
  private static final Pattern DATA_ISO =
      Pattern.compile("(?<![\\d-])(\\d{4})-(\\d{1,2})-(\\d{1,2})(?![\\d-])");
  private static final Pattern MES_BR = Pattern.compile("(?<![\\d/])(\\d{1,2})/(\\d{4})(?![\\d/])");
  private static final Pattern MES_ISO = Pattern.compile("(?<![\\d-])(\\d{4})-(\\d{1,2})(?![\\d-])");
  private static final Pattern MARCADOR_LISTA = Pattern.compile("(?m)^\\s*\\d{1,2}[.)]\\s");
  private static final Pattern NUMERO = Pattern.compile("\\d+(?:[.,]\\d+)*");
  private static final Pattern MILHAR_PONTO = Pattern.compile("\\d{1,3}(?:\\.\\d{3})+");
  private static final Pattern MILHAR_VIRGULA = Pattern.compile("\\d{1,3}(?:,\\d{3})+");

  /**
   * Resultado da verificação.
   *
   * @param rastreavel            {@code true} se todo número da resposta tem fonte
   * @param quantidadeSemFonte    números/datas sem fonte (para log; os valores não são expostos)
   */
  public record Resultado(boolean rastreavel, int quantidadeSemFonte) {}

  /**
   * Verifica a resposta contra as fontes.
   *
   * @param resposta texto final do modelo
   * @param fontes   resultados das funções, pergunta do usuário e instrução de sistema
   * @return se todos os números têm fonte e quantos não têm
   */
  public Resultado verificar(String resposta, Collection<String> fontes) {
    return verificar(resposta, fontes, List.of());
  }

  /**
   * Verifica a resposta contra as fontes e datas de referência.
   *
   * @param resposta       texto final do modelo
   * @param fontes         resultados das funções e pergunta do usuário — de onde números podem vir
   * @param datasConhecidas datas que a resposta pode citar sem estarem nas fontes (ex.: hoje). Só
   *                       valem como data (e o ano); dia e mês não viram números "com fonte" —
   *                       senão "hoje é 29/09" liberaria um "29" inventado
   * @return se todos os números têm fonte e quantos não têm
   */
  public Resultado verificar(
      String resposta, Collection<String> fontes, Collection<LocalDate> datasConhecidas) {
    Conhecidos conhecidos = new Conhecidos();
    for (String fonte : fontes) {
      if (fonte != null) {
        conhecidos.aprender(fonte);
      }
    }
    datasConhecidas.forEach(conhecidos::aprenderData);
    int semFonte = conhecidos.contarSemFonte(resposta == null ? "" : resposta);
    return new Resultado(semFonte == 0, semFonte);
  }

  /**
   * Valores que um texto menciona, lidos como na verificação: todas as leituras de cada número e
   * as partes de cada data. Usado pela avaliação com gabarito (issue #158) para conferir se a
   * resposta traz o valor calculado pelo backend.
   *
   * @param texto texto livre
   * @return valores normalizados (sem zeros à direita)
   */
  public static Set<BigDecimal> valoresMencionados(String texto) {
    Conhecidos conhecidos = new Conhecidos();
    conhecidos.aprender(texto == null ? "" : texto);
    return Set.copyOf(conhecidos.numeros);
  }

  /**
   * Leituras possíveis de um valor escrito, ex.: {@code "R$ 12.500,00"} → {@code 12500}.
   *
   * @param valor texto com um número (prefixos como {@code R$} são ignorados)
   * @return as leituras normalizadas; vazio se o texto não tem número
   */
  public static List<BigDecimal> leiturasDoValor(String valor) {
    Matcher m = NUMERO.matcher(valor == null ? "" : valor);
    return m.find() ? candidatos(m.group()) : List.of();
  }

  /** Datas, meses e números que as fontes contêm. */
  private static final class Conhecidos {

    private final Set<LocalDate> datas = new HashSet<>();
    private final Set<YearMonth> meses = new HashSet<>();
    private final Set<BigDecimal> numeros = new HashSet<>();

    void aprender(String texto) {
      String resto = texto;
      for (Pattern padrao : List.of(DATA_BR, DATA_ISO)) {
        Matcher m = padrao.matcher(resto);
        while (m.find()) {
          LocalDate data = data(padrao, m);
          if (data != null) {
            datas.add(data);
            meses.add(YearMonth.from(data));
            numeros.add(normalizar(new BigDecimal(data.getYear())));
            numeros.add(normalizar(new BigDecimal(data.getMonthValue())));
            numeros.add(normalizar(new BigDecimal(data.getDayOfMonth())));
          }
        }
        resto = padrao.matcher(resto).replaceAll(" ");
      }
      for (Pattern padrao : List.of(MES_BR, MES_ISO)) {
        Matcher m = padrao.matcher(resto);
        while (m.find()) {
          YearMonth mes = mes(padrao, m);
          if (mes != null) {
            meses.add(mes);
            numeros.add(normalizar(new BigDecimal(mes.getYear())));
            numeros.add(normalizar(new BigDecimal(mes.getMonthValue())));
          }
        }
        resto = padrao.matcher(resto).replaceAll(" ");
      }
      Matcher m = NUMERO.matcher(resto);
      while (m.find()) {
        numeros.addAll(candidatos(m.group()));
      }
    }

    void aprenderData(LocalDate data) {
      datas.add(data);
      meses.add(YearMonth.from(data));
      numeros.add(normalizar(new BigDecimal(data.getYear())));
    }

    int contarSemFonte(String resposta) {
      int semFonte = 0;
      String resto = MARCADOR_LISTA.matcher(resposta).replaceAll(" ");
      for (Pattern padrao : List.of(DATA_BR, DATA_ISO)) {
        Matcher m = padrao.matcher(resto);
        while (m.find()) {
          LocalDate data = data(padrao, m);
          if (data == null || !datas.contains(data)) {
            semFonte++;
          }
        }
        resto = padrao.matcher(resto).replaceAll(" ");
      }
      for (Pattern padrao : List.of(MES_BR, MES_ISO)) {
        Matcher m = padrao.matcher(resto);
        while (m.find()) {
          YearMonth mes = mes(padrao, m);
          if (mes == null || !meses.contains(mes)) {
            semFonte++;
          }
        }
        resto = padrao.matcher(resto).replaceAll(" ");
      }
      Matcher m = NUMERO.matcher(resto);
      while (m.find()) {
        if (candidatos(m.group()).stream().noneMatch(numeros::contains)) {
          semFonte++;
        }
      }
      return semFonte;
    }
  }

  private static LocalDate data(Pattern padrao, Matcher m) {
    try {
      return padrao == DATA_BR
          ? LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)),
              Integer.parseInt(m.group(1)))
          : LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
              Integer.parseInt(m.group(3)));
    } catch (DateTimeException e) {
      return null;
    }
  }

  private static YearMonth mes(Pattern padrao, Matcher m) {
    try {
      return padrao == MES_BR
          ? YearMonth.of(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)))
          : YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    } catch (DateTimeException e) {
      return null;
    }
  }

  /**
   * Leituras possíveis de um número escrito: {@code 1.234,56} (pt-BR), {@code 1,234.56} (en),
   * {@code 1.500} (mil e quinhentos ou um e meio), {@code 3808.93.24} (NCM com pontos).
   */
  static List<BigDecimal> candidatos(String token) {
    List<String> leituras = new ArrayList<>();
    boolean temPonto = token.indexOf('.') >= 0;
    boolean temVirgula = token.indexOf(',') >= 0;
    if (temPonto && temVirgula) {
      boolean virgulaDecimal = token.lastIndexOf(',') > token.lastIndexOf('.');
      leituras.add(virgulaDecimal
          ? token.replace(".", "").replace(',', '.')
          : token.replace(",", ""));
    } else if (temVirgula) {
      if (MILHAR_VIRGULA.matcher(token).matches()) {
        leituras.add(token.replace(",", ""));
      }
      if (token.chars().filter(c -> c == ',').count() == 1) {
        leituras.add(token.replace(',', '.'));
      }
    } else if (temPonto) {
      if (MILHAR_PONTO.matcher(token).matches()) {
        leituras.add(token.replace(".", ""));
      }
      if (token.chars().filter(c -> c == '.').count() == 1) {
        leituras.add(token);
      } else {
        leituras.add(token.replace(".", ""));
      }
    } else {
      leituras.add(token);
    }
    List<BigDecimal> valores = new ArrayList<>();
    for (String leitura : leituras) {
      try {
        valores.add(normalizar(new BigDecimal(leitura)));
      } catch (NumberFormatException e) {
        // leitura impossivel: ignorada
      }
    }
    return valores;
  }

  private static BigDecimal normalizar(BigDecimal valor) {
    return valor.stripTrailingZeros();
  }
}
