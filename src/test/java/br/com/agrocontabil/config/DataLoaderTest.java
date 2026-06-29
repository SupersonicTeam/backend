package br.com.agrocontabil.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.repository.ProducerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DataLoaderTest {

  @Mock ProducerRepository produtorRepository;

  @InjectMocks DataLoader dataLoader;

  @Test
  void shouldSeedProdutorWhenNotExists() throws Exception {
    when(produtorRepository.existsByTaxId("123.456.789-00")).thenReturn(false);

    dataLoader.run();

    verify(produtorRepository).save(any(Producer.class));
  }

  @Test
  void shouldSkipSeedWhenAlreadyExists() throws Exception {
    when(produtorRepository.existsByTaxId("123.456.789-00")).thenReturn(true);

    dataLoader.run();

    verify(produtorRepository, never()).save(any());
  }
}
