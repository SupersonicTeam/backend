package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.common.UfIbge;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DataLoader — seed dos produtores de dev/homolog")
class DataLoaderTest {

  private static final String CPF_A = DataLoader.CPF_PRODUTOR_SEMENTE;
  private static final String CPF_B = "987.654.321-00";

  @Mock ProdutorRepository produtorRepository;

  @InjectMocks DataLoader dataLoader;

  /**
   * Devolve a propria entidade com id, como o JPA faz — o loader usa o retorno para logar o id.
   *
   * <p>Chamado so pelos testes que chegam a salvar: com strict stubs, deixar isto num
   * {@code @BeforeEach} quebraria o cenario em que nada e semeado.
   */
  private void stubarSave() {
    when(produtorRepository.save(any(Produtor.class)))
        .thenAnswer(
            invocacao -> {
              Produtor produtor = invocacao.getArgument(0);
              produtor.setId(UUID.randomUUID());
              return produtor;
            });
  }

  private List<Produtor> capturarSemeados(int quantidade) {
    ArgumentCaptor<Produtor> captor = ArgumentCaptor.forClass(Produtor.class);
    verify(produtorRepository, times(quantidade)).save(captor.capture());
    return captor.getAllValues();
  }

  @Test
  @DisplayName("semeia dois produtores distintos quando nenhum existe (#91)")
  void semeiaDoisProdutores() {
    stubarSave();
    when(produtorRepository.existsByCpfCnpj(CPF_A)).thenReturn(false);
    when(produtorRepository.existsByCpfCnpj(CPF_B)).thenReturn(false);

    dataLoader.run();

    assertThat(capturarSemeados(2))
        .extracting(Produtor::getCpfCnpj)
        .containsExactly(CPF_A, CPF_B);
  }

  @Test
  @DisplayName("semeia apenas o produtor que ainda nao existe (idempotencia por CPF)")
  void semeiaApenasOFaltante() {
    stubarSave();
    when(produtorRepository.existsByCpfCnpj(CPF_A)).thenReturn(true);
    when(produtorRepository.existsByCpfCnpj(CPF_B)).thenReturn(false);

    dataLoader.run();

    assertThat(capturarSemeados(1)).singleElement().extracting(Produtor::getCpfCnpj).isEqualTo(CPF_B);
  }

  @Test
  @DisplayName("produtores semeados tem dados fiscais distintos — troca de produtor aparece no XML")
  void produtoresTemDadosFiscaisDistintos() {
    stubarSave();
    when(produtorRepository.existsByCpfCnpj(CPF_A)).thenReturn(false);
    when(produtorRepository.existsByCpfCnpj(CPF_B)).thenReturn(false);

    dataLoader.run();

    List<Produtor> semeados = capturarSemeados(2);
    assertThat(semeados).extracting(Produtor::getInscricaoEstadual).doesNotHaveDuplicates();
    assertThat(semeados)
        .extracting(produtor -> produtor.getEndereco().getCodigoMunicipioIbge())
        .doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("todo produtor semeado passa na validacao de emitente do FiscalRulesValidator (#88)")
  void produtoresSemeadosSaoValidosParaEmissao() {
    stubarSave();
    when(produtorRepository.existsByCpfCnpj(CPF_A)).thenReturn(false);
    when(produtorRepository.existsByCpfCnpj(CPF_B)).thenReturn(false);

    dataLoader.run();

    // O seed precisa gerar XML aceitavel pela SEFAZ: IE e endereco completos e coerentes.
    assertThat(capturarSemeados(2))
        .allSatisfy(
            semeado -> {
              assertThat(semeado.getInscricaoEstadual()).isNotBlank();
              assertThat(semeado.getEndereco()).isNotNull();
              assertThat(semeado.getEndereco().getLogradouro()).isNotBlank();
              assertThat(semeado.getEndereco().getNumero()).isNotBlank();
              assertThat(semeado.getEndereco().getBairro()).isNotBlank();
              assertThat(semeado.getEndereco().getMunicipio()).isNotBlank();
              assertThat(semeado.getEndereco().getCep()).hasSize(8);
              assertThat(
                      UfIbge.prefixoCoerente(
                          semeado.getEndereco().getCodigoMunicipioIbge(), semeado.getEstado()))
                  .isTrue();
            });
  }

  @Test
  @DisplayName("nao semeia nada quando os dois produtores ja existem")
  void naoSemeiaQuandoAmbosExistem() {
    when(produtorRepository.existsByCpfCnpj(CPF_A)).thenReturn(true);
    when(produtorRepository.existsByCpfCnpj(CPF_B)).thenReturn(true);

    dataLoader.run();

    verify(produtorRepository, never()).save(any());
  }
}
