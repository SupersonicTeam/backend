package br.com.agrocontabil.config;

import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.repository.ProducerRepository;
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
public class MockProducerFilter extends OncePerRequestFilter {

  private static final String MOCK_CPF = "123.456.789-00";

  private final ProducerRepository produtorRepository;
  private volatile Producer cachedProducer;

  public MockProducerFilter(ProducerRepository produtorRepository) {
    this.produtorRepository = produtorRepository;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    if (SecurityContextHolder.getContext().getAuthentication() == null) {
      Producer producer = getOrLoadMockProducer();
      if (producer != null) {
        UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(producer, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
      }
    }

    filterChain.doFilter(request, response);
  }

  private Producer getOrLoadMockProducer() {
    if (cachedProducer == null) {
      synchronized (this) {
        if (cachedProducer == null) {
          cachedProducer = produtorRepository.findByTaxId(MOCK_CPF).orElse(null);
        }
      }
    }
    return cachedProducer;
  }
}
