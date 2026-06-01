package br.com.agrocontabil.config;

import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ProdutorRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile("dev")
public class MockProdutorFilter extends OncePerRequestFilter {

  private static final String MOCK_CPF = "123.456.789-00";

  private final ProdutorRepository produtorRepository;
  private volatile Produtor cachedProdutor;

  public MockProdutorFilter(ProdutorRepository produtorRepository) {
    this.produtorRepository = produtorRepository;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    if (SecurityContextHolder.getContext().getAuthentication() == null) {
      Produtor produtor = getOrLoadMockProdutor();
      if (produtor != null) {
        UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(produtor, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
      }
    }

    filterChain.doFilter(request, response);
  }

  private Produtor getOrLoadMockProdutor() {
    if (cachedProdutor == null) {
      synchronized (this) {
        if (cachedProdutor == null) {
          cachedProdutor = produtorRepository.findByCpfCnpj(MOCK_CPF).orElse(null);
        }
      }
    }
    return cachedProdutor;
  }
}
