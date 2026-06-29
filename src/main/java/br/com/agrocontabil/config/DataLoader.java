package br.com.agrocontabil.config;

import br.com.agrocontabil.model.PorteEmpresa;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.math.BigDecimal;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class DataLoader implements CommandLineRunner {

  private static final String MOCK_CPF = "123.456.789-00";

  private final ProdutorRepository produtorRepository;

  public DataLoader(ProdutorRepository produtorRepository) {
    this.produtorRepository = produtorRepository;
  }

  @Override
  public void run(String... args) {
    if (produtorRepository.existsByCpfCnpj(MOCK_CPF)) {
      return;
    }

    Produtor mockProdutor =
        Produtor.builder()
            .nomeCompleto("João da Silva Produtor")
            .cpfCnpj(MOCK_CPF)
            .tipoProdutor(TipoProdutor.PESSOA_FISICA)
            .regimeTributario(RegimeTributario.SIMPLES)
            .porteEmpresa(PorteEmpresa.ME)
            .culturas(new String[]{"soja", "milho", "algodao"})
            .faturamentoAnual(new BigDecimal("2500000.00"))
            .estado("MT")
            .email("joao.produtor@example.com")
            .telefone("(65) 99999-0001")
            .build();

    produtorRepository.save(mockProdutor);
  }
}
