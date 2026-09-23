package br.com.agrocontabil.config;

import br.com.agrocontabil.model.EnderecoProdutor;
import br.com.agrocontabil.model.PorteEmpresa;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Semeia os produtores usados no desenvolvimento e na homologacao.
 *
 * <p>Sao <strong>dois</strong> produtores desde a issue #91: com um so, o anti-IDOR aplicado em toda
 * a API ({@code findByIdAndProdutorId} → 404) nao tem como ser exercido — nao existe "o outro
 * produtor" cujo recurso deva ser negado. O produtor A e tambem o <em>semente</em>: e ele que o
 * {@link MockProdutorFilter} injeta quando a requisicao nao declara {@code X-Usuario-Id}.
 *
 * <p>Ambos carregam inscricao estadual e endereco completos: desde a issue #88 isso e pre-condicao
 * da emissao, e um produtor sem esses dados geraria XML com placeholder. Os municipios sao
 * diferentes de proposito — assim uma troca acidental de produtor aparece no XML em vez de passar
 * despercebida.
 */
@Component
@Profile("dev")
public class DataLoader implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(DataLoader.class);

  /** Produtor A — o semente, injetado quando nao ha {@code X-Usuario-Id} na requisicao. */
  private static final String CPF_PRODUTOR_A = "123.456.789-00";

  /** Produtor B — existe para dar um "outro dono" aos cenarios de ownership. */
  private static final String CPF_PRODUTOR_B = "987.654.321-00";

  private final ProdutorRepository produtorRepository;

  public DataLoader(ProdutorRepository produtorRepository) {
    this.produtorRepository = produtorRepository;
  }

  @Override
  public void run(String... args) {
    semear(produtorA());
    semear(produtorB());
  }

  /** Persiste o produtor se ainda nao existir, e loga o id para uso no header {@code X-Usuario-Id}. */
  private void semear(Produtor produtor) {
    if (produtorRepository.existsByCpfCnpj(produtor.getCpfCnpj())) {
      return;
    }

    Produtor salvo = produtorRepository.save(produtor);
    // O id nao e segredo — ja viaja na URL de varios endpoints — e e o valor que o
    // desenvolvedor precisa ter em maos para simular este usuario via header.
    log.info(
        "Produtor semeado: {} (X-Usuario-Id: {})", salvo.getNomeCompleto(), salvo.getId());
  }

  private Produtor produtorA() {
    return Produtor.builder()
        .nomeCompleto("João da Silva Produtor")
        .cpfCnpj(CPF_PRODUTOR_A)
        .tipoProdutor(TipoProdutor.PESSOA_FISICA)
        .regimeTributario(RegimeTributario.SIMPLES)
        .porteEmpresa(PorteEmpresa.ME)
        .culturas(new String[] {"soja", "milho", "algodao"})
        .faturamentoAnual(new BigDecimal("2500000.00"))
        .estado("MT")
        // Endereco e IE reais sao pre-condicao da emissao desde a issue #88: sem eles o
        // seed geraria XML com placeholders, rejeitado pela SEFAZ. Cuiaba/MT (IBGE 5103403).
        .inscricaoEstadual("1234567890")
        .endereco(
            EnderecoProdutor.builder()
                .logradouro("Avenida Historiador Rubens de Mendonca")
                .numero("1894")
                .bairro("Bosque da Saude")
                .codigoMunicipioIbge("5103403")
                .municipio("Cuiaba")
                .cep("78050000")
                .build())
        .email("joao.produtor@example.com")
        .telefone("(65) 99999-0001")
        .build();
  }

  private Produtor produtorB() {
    return Produtor.builder()
        .nomeCompleto("Maria Agricultora")
        .cpfCnpj(CPF_PRODUTOR_B)
        .tipoProdutor(TipoProdutor.PESSOA_FISICA)
        .regimeTributario(RegimeTributario.SIMPLES)
        .porteEmpresa(PorteEmpresa.ME)
        .culturas(new String[] {"soja", "girassol"})
        .faturamentoAnual(new BigDecimal("1800000.00"))
        .estado("MT")
        // Rondonopolis/MT (IBGE 5107602) — municipio e IE distintos dos do produtor A.
        .inscricaoEstadual("0987654321")
        .endereco(
            EnderecoProdutor.builder()
                .logradouro("Avenida Bandeirantes")
                .numero("742")
                .bairro("Vila Aurora")
                .codigoMunicipioIbge("5107602")
                .municipio("Rondonopolis")
                .cep("78710000")
                .build())
        .email("maria.agricultora@example.com")
        .telefone("(66) 99999-0002")
        .build();
  }
}
