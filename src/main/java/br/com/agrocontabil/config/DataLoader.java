package br.com.agrocontabil.config;

import br.com.agrocontabil.model.CompanySize;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.TaxRegime;
import br.com.agrocontabil.model.ProducerType;
import br.com.agrocontabil.repository.ProducerRepository;
import java.math.BigDecimal;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class DataLoader implements CommandLineRunner {

  private static final String MOCK_CPF = "123.456.789-00";

  private final ProducerRepository produtorRepository;

  public DataLoader(ProducerRepository produtorRepository) {
    this.produtorRepository = produtorRepository;
  }

  @Override
  public void run(String... args) {
    if (produtorRepository.existsByTaxId(MOCK_CPF)) {
      return;
    }

    Producer mockProdutor =
        Producer.builder()
            .fullName("João da Silva Produtor")
            .taxId(MOCK_CPF)
            .producerType(ProducerType.PESSOA_FISICA)
            .taxRegime(TaxRegime.SIMPLES)
            .companySize(CompanySize.ME)
            .crops(new String[]{"soja", "milho", "algodao"})
            .annualRevenue(new BigDecimal("2500000.00"))
            .state("MT")
            .email("joao.produtor@example.com")
            .phone("(65) 99999-0001")
            .build();

    produtorRepository.save(mockProdutor);
  }
}
