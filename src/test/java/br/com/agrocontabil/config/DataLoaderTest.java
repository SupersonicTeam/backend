package br.com.agrocontabil.config;

import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.DisplayName;
import br.com.agrocontabil.common.UfIbge;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ProdutorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DataLoaderTest {

  @Mock ProdutorRepository produtorRepository;

  @InjectMocks DataLoader dataLoader;

  @Test
  void shouldSeedProdutorWhenNotExists() throws Exception {
    when(produtorRepository.existsByCpfCnpj("123.456.789-00")).thenReturn(false);

    dataLoader.run();

    verify(produtorRepository).save(any(Produtor.class));
  }

  @Test
  void shouldSkipSeedWhenAlreadyExists() throws Exception {
    when(produtorRepository.existsByCpfCnpj("123.456.789-00")).thenReturn(true);

    dataLoader.run();

    verify(produtorRepository, never()).save(any());
  }

  @Test
  @DisplayName("produtor semeado passa na validacao de emitente do FiscalRulesValidator (#88)")
  void produtorSemeadoEValidoParaEmissao() throws Exception {
    when(produtorRepository.existsByCpfCnpj("123.456.789-00")).thenReturn(false);
    ArgumentCaptor<Produtor> captor = ArgumentCaptor.forClass(Produtor.class);

    dataLoader.run();

    verify(produtorRepository).save(captor.capture());
    Produtor semeado = captor.getValue();
    // O seed precisa gerar XML aceitavel pela SEFAZ: IE e endereco completos e coerentes.
    assertThat(semeado.getInscricaoEstadual()).isNotBlank();
    assertThat(semeado.getEndereco()).isNotNull();
    assertThat(semeado.getEndereco().getLogradouro()).isNotBlank();
    assertThat(semeado.getEndereco().getNumero()).isNotBlank();
    assertThat(semeado.getEndereco().getBairro()).isNotBlank();
    assertThat(semeado.getEndereco().getMunicipio()).isNotBlank();
    assertThat(semeado.getEndereco().getCep()).hasSize(8);
    assertThat(UfIbge.prefixoCoerente(
        semeado.getEndereco().getCodigoMunicipioIbge(), semeado.getEstado())).isTrue();
  }
}
