package br.com.agrocontabil.config.avaliacao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;

/**
 * Valor esperado de cada pergunta do gabarito (issue #158), calculado pelo backend: chama a função
 * de referência da pergunta com os parâmetros esperados (o primeiro aceito de cada um) e lê os
 * {@code camposValor} do resultado, já formatados como a Ceres os recebe.
 *
 * <p>Roda antes de qualquer pergunta ao modelo: um gabarito com função, parâmetro ou campo errado
 * para a avaliação aqui, em vez de virar "erro do modelo" no relatório.
 */
public final class ValoresEsperados {

  private final FerramentasDadosProdutor ferramentas;
  private final ParameterNameDiscoverer nomesDeParametro = new DefaultParameterNameDiscoverer();

  /**
   * Calculadora sobre as funções reais da Ceres.
   *
   * @param ferramentas funções de dados da Ceres
   */
  public ValoresEsperados(FerramentasDadosProdutor ferramentas) {
    this.ferramentas = ferramentas;
  }

  /**
   * Valores esperados das perguntas que pedem número.
   *
   * @param gabarito   gabarito
   * @param produtorId produtor avaliado
   * @param hoje       "hoje" da avaliação
   * @return id da pergunta → valores esperados, na ordem de {@code camposValor}
   * @throws IllegalArgumentException se uma função, parâmetro ou campo do gabarito não existe, ou
   *                                  se a função recusa os parâmetros esperados
   */
  public Map<String, List<String>> calcular(Gabarito gabarito, UUID produtorId, LocalDate hoje) {
    Map<String, List<String>> esperados = new LinkedHashMap<>();
    for (PerguntaGabarito pergunta : gabarito.perguntas()) {
      validar(pergunta);
      if (pergunta.camposValor().isEmpty()) {
        continue;
      }
      Object resultado = chamar(pergunta, new ContextoAssistente(produtorId, hoje));
      List<String> valores = new ArrayList<>();
      for (String campo : pergunta.camposValor()) {
        valores.add(lerCampo(resultado, campo, pergunta.id()));
      }
      esperados.put(pergunta.id(), List.copyOf(valores));
    }
    return esperados;
  }

  /**
   * Toda função esperada existe e todo parâmetro esperado é um parâmetro da função de referência.
   * Os nomes que as funções registram como fonte são os dos próprios parâmetros.
   */
  void validar(PerguntaGabarito pergunta) {
    pergunta.funcoesEsperadas().forEach(funcao -> metodo(funcao, pergunta.id()));
    if (pergunta.parametrosEsperados().isEmpty()) {
      return;
    }
    if (pergunta.funcaoReferencia() == null) {
      throw new IllegalArgumentException(
          pergunta.id() + ": parametrosEsperados exige uma funcao em funcoesEsperadas");
    }
    List<String> nomes = Arrays.asList(nomes(metodo(pergunta.funcaoReferencia(), pergunta.id())));
    for (String parametro : pergunta.parametrosEsperados().keySet()) {
      if (!nomes.contains(parametro)) {
        throw new IllegalArgumentException(pergunta.id() + ": parametro inexistente em "
            + pergunta.funcaoReferencia() + ": " + parametro);
      }
    }
  }

  private String[] nomes(Method metodo) {
    String[] nomes = nomesDeParametro.getParameterNames(metodo);
    if (nomes == null) {
      throw new IllegalStateException("Compile com -parameters: nomes de parametro ausentes");
    }
    return nomes;
  }

  private Object chamar(PerguntaGabarito pergunta, ContextoAssistente contexto) {
    Method metodo = metodo(pergunta.funcaoReferencia(), pergunta.id());
    String[] nomes = nomes(metodo);
    Class<?>[] tipos = metodo.getParameterTypes();
    Object[] argumentos = new Object[tipos.length];
    for (int i = 0; i < tipos.length; i++) {
      if (tipos[i] == ToolContext.class) {
        argumentos[i] = new ToolContext(contexto.comoToolContext());
        continue;
      }
      List<String> aceitos = pergunta.parametrosEsperados().get(nomes[i]);
      String valor = aceitos == null || aceitos.isEmpty() ? null : aceitos.get(0);
      argumentos[i] = valor == null || tipos[i] == String.class ? valor : Integer.valueOf(valor);
    }
    try {
      return metodo.invoke(ferramentas, argumentos);
    } catch (InvocationTargetException e) {
      throw new IllegalArgumentException(pergunta.id() + ": a funcao de referencia "
          + metodo.getName() + " recusou os parametros esperados: " + e.getCause().getMessage(),
          e.getCause());
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Método público da função, pelo nome; as funções da Ceres não têm sobrecarga. */
  Method metodo(String nome, String id) {
    return Arrays.stream(AopUtils.getTargetClass(ferramentas).getMethods())
        .filter(m -> m.getName().equals(nome)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException(id + ": funcao inexistente: " + nome));
  }

  private static String lerCampo(Object resultado, String campo, String id) {
    RecordComponent componente = Arrays.stream(resultado.getClass().getRecordComponents())
        .filter(c -> c.getName().equals(campo)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException(
            id + ": campo inexistente no resultado da funcao: " + campo));
    try {
      return String.valueOf(componente.getAccessor().invoke(resultado));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
