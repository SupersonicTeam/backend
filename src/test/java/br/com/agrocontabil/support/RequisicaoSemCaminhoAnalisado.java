package br.com.agrocontabil.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.Set;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.UrlPathHelper;

/**
 * Requisição que falha se alguém tocar no caminho analisado que o {@code DispatcherServlet}
 * guarda em atributo (issue #345): ler, gravar ou remover {@code ServletRequestPathUtils.PATH_ATTRIBUTE}
 * ou {@code UrlPathHelper.PATH_ATTRIBUTE} lança {@link AssertionError}.
 *
 * <p>É a prova determinística de que um matcher é sem estado: o escritor da CSP roda na thread
 * que faz o commit da resposta, e o {@code finally} do {@code DispatcherServlet} remove esse
 * atributo ao mesmo tempo (#335). Matcher que o lê, como o {@code PathPatternRequestMatcher},
 * falha aqui sem depender de corrida.
 */
public class RequisicaoSemCaminhoAnalisado extends HttpServletRequestWrapper {

  private static final Set<String> PROIBIDOS =
      Set.of(ServletRequestPathUtils.PATH_ATTRIBUTE, UrlPathHelper.PATH_ATTRIBUTE);

  public RequisicaoSemCaminhoAnalisado(HttpServletRequest request) {
    super(request);
  }

  @Override
  public Object getAttribute(String name) {
    proibir(name, "leu");
    return super.getAttribute(name);
  }

  @Override
  public void setAttribute(String name, Object o) {
    proibir(name, "gravou");
    super.setAttribute(name, o);
  }

  @Override
  public void removeAttribute(String name) {
    proibir(name, "removeu");
    super.removeAttribute(name);
  }

  private static void proibir(String name, String acao) {
    if (PROIBIDOS.contains(name)) {
      throw new AssertionError("O matcher " + acao + " o caminho analisado (" + name + ")");
    }
  }
}
