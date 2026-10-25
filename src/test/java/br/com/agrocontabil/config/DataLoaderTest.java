package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.common.UfIbge;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.VinculoCarteira;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import java.util.List;
import java.util.Optional;
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

  private static final String CNPJ_CONTADOR = "12.345.678/0001-95";

  @Mock ProdutorRepository produtorRepository;
  @Mock ContadorRepository contadorRepository;
  @Mock VinculoCarteiraRepository vinculoRepository;

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

  /**
   * Devolve o contador com id, como o JPA faz — o loader loga o retorno do save.
   *
   * <p>Separado de {@link #stubarSave()} por causa dos strict stubs: o cenario em que o contador ja
   * existe nao chega a salvar, e um stub nao usado falharia o teste.
   */
  private void stubarSaveContador() {
    when(contadorRepository.save(any(Contador.class)))
        .thenAnswer(
            invocacao -> {
              Contador contador = invocacao.getArgument(0);
              contador.setId(UUID.randomUUID());
              return contador;
            });
  }

  /** Produtor como o banco devolveria: mesmo CPF e com id, para o vinculo do contador. */
  private Produtor produtorExistente(String cpfCnpj) {
    return Produtor.builder().id(UUID.randomUUID()).cpfCnpj(cpfCnpj).build();
  }

  private VinculoCarteira capturarVinculoSemeado() {
    ArgumentCaptor<VinculoCarteira> captor = ArgumentCaptor.forClass(VinculoCarteira.class);
    verify(vinculoRepository).save(captor.capture());
    return captor.getValue();
  }

  private Contador capturarContadorSemeado() {
    ArgumentCaptor<Contador> captor = ArgumentCaptor.forClass(Contador.class);
    verify(contadorRepository).save(captor.capture());
    return captor.getValue();
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
    stubarSaveContador();
    when(produtorRepository.findByCpfCnpj(CPF_A)).thenReturn(Optional.empty());
    when(produtorRepository.findByCpfCnpj(CPF_B)).thenReturn(Optional.empty());

    dataLoader.run();

    assertThat(capturarSemeados(2))
        .extracting(Produtor::getCpfCnpj)
        .containsExactly(CPF_A, CPF_B);
  }

  @Test
  @DisplayName("semeia apenas o produtor que ainda nao existe (idempotencia por CPF)")
  void semeiaApenasOFaltante() {
    stubarSave();
    stubarSaveContador();
    when(produtorRepository.findByCpfCnpj(CPF_A)).thenReturn(Optional.of(produtorExistente(CPF_A)));
    when(produtorRepository.findByCpfCnpj(CPF_B)).thenReturn(Optional.empty());

    dataLoader.run();

    assertThat(capturarSemeados(1)).singleElement().extracting(Produtor::getCpfCnpj).isEqualTo(CPF_B);
  }

  @Test
  @DisplayName("produtores semeados tem dados fiscais distintos — troca de produtor aparece no XML")
  void produtoresTemDadosFiscaisDistintos() {
    stubarSave();
    stubarSaveContador();
    when(produtorRepository.findByCpfCnpj(CPF_A)).thenReturn(Optional.empty());
    when(produtorRepository.findByCpfCnpj(CPF_B)).thenReturn(Optional.empty());

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
    stubarSaveContador();
    when(produtorRepository.findByCpfCnpj(CPF_A)).thenReturn(Optional.empty());
    when(produtorRepository.findByCpfCnpj(CPF_B)).thenReturn(Optional.empty());

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
    when(produtorRepository.findByCpfCnpj(CPF_A)).thenReturn(Optional.of(produtorExistente(CPF_A)));
    when(produtorRepository.findByCpfCnpj(CPF_B)).thenReturn(Optional.of(produtorExistente(CPF_B)));
    when(contadorRepository.findByCpfCnpj(CNPJ_CONTADOR))
        .thenReturn(Optional.of(Contador.builder().id(UUID.randomUUID()).build()));

    dataLoader.run();

    verify(produtorRepository, never()).save(any(Produtor.class));
    verify(contadorRepository, never()).save(any(Contador.class));
  }

  @Test
  @DisplayName("semeia um contador vinculado apenas ao produtor A (#93)")
  void semeiaContadorVinculadoAoProdutorA() {
    stubarSave();
    stubarSaveContador();
    when(produtorRepository.findByCpfCnpj(CPF_A)).thenReturn(Optional.empty());
    when(produtorRepository.findByCpfCnpj(CPF_B)).thenReturn(Optional.empty());

    dataLoader.run();

    Produtor produtorA = capturarSemeados(2).get(0);
    Contador contador = capturarContadorSemeado();
    assertThat(contador.getCpfCnpj()).isEqualTo(CNPJ_CONTADOR);
    assertThat(contador.getCrc()).isNotBlank();
    // Vinculo parcial de proposito: o produtor B fica de fora para dar um "produtor que este
    // contador nao atende" aos cenarios de acesso negado.
    VinculoCarteira vinculo = capturarVinculoSemeado();
    assertThat(vinculo.getContadorId()).isEqualTo(contador.getId());
    assertThat(vinculo.getProdutorId()).isEqualTo(produtorA.getId());
    assertThat(vinculo.getSituacao()).isEqualTo(SituacaoVinculo.ATIVO);
  }

  @Test
  @DisplayName("vincula o contador ao produtor A que ja estava no banco, sem duplicar o produtor")
  void vinculaContadorAoProdutorAJaExistente() {
    Produtor produtorAExistente = produtorExistente(CPF_A);
    when(produtorRepository.findByCpfCnpj(CPF_A)).thenReturn(Optional.of(produtorAExistente));
    when(produtorRepository.findByCpfCnpj(CPF_B)).thenReturn(Optional.of(produtorExistente(CPF_B)));
    stubarSaveContador();

    dataLoader.run();

    verify(produtorRepository, never()).save(any(Produtor.class));
    assertThat(capturarVinculoSemeado().getProdutorId()).isEqualTo(produtorAExistente.getId());
  }

  @Test
  @DisplayName("nao ressemeia o contador existente — vinculos criados a mao sobrevivem")
  void naoRessemeiaContadorExistente() {
    stubarSave();
    when(produtorRepository.findByCpfCnpj(CPF_A)).thenReturn(Optional.empty());
    when(produtorRepository.findByCpfCnpj(CPF_B)).thenReturn(Optional.empty());
    when(contadorRepository.findByCpfCnpj(CNPJ_CONTADOR))
        .thenReturn(Optional.of(Contador.builder().id(UUID.randomUUID()).build()));

    dataLoader.run();

    verify(contadorRepository, never()).save(any(Contador.class));
  }
}
