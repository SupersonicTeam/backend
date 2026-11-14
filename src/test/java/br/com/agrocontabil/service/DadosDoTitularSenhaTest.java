package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoEscritorio;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.PapelUsuario;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DadosDoTitularSenha — o que a senha nova nao pode conter, pela credencial (#521)")
class DadosDoTitularSenhaTest {

  private static final String EMAIL = "titular@exemplo.com.br";
  private static final String CPF = "52998224725";

  @Mock private ProdutorRepository produtorRepository;
  @Mock private ContadorRepository contadorRepository;
  @Mock private EscritorioRepository escritorioRepository;

  private DadosDoTitularSenha dados;

  @BeforeEach
  void setUp() {
    dados = new DadosDoTitularSenha(produtorRepository, contadorRepository, escritorioRepository);
  }

  private static CredencialUsuario credencial(PapelUsuario papel, UUID produtorId,
      UUID contadorId) {
    return CredencialUsuario.builder().email(EMAIL).cpfCnpj(CPF).papel(papel)
        .produtorId(produtorId).contadorId(contadorId).senhaHash("{bcrypt}x").build();
  }

  @Test
  @DisplayName("produtor: e-mail, CPF e o telefone gravado")
  void produtor() {
    UUID id = UUID.randomUUID();
    when(produtorRepository.findById(id)).thenReturn(Optional.of(
        Produtor.builder().id(id).telefone("(65) 99999-0001").build()));

    PoliticaSenha.DadosDoTitular titular =
        dados.de(credencial(PapelUsuario.PRODUTOR, id, null));

    assertThat(titular.email()).isEqualTo(EMAIL);
    assertThat(titular.documentos()).containsExactly(CPF);
    assertThat(titular.telefones()).containsExactly("(65) 99999-0001");
    verifyNoInteractions(contadorRepository, escritorioRepository);
  }

  @Test
  @DisplayName("produtor sem telefone ou ja apagado: so o e-mail e o documento, sem erro")
  void produtorSemTelefoneOuApagado() {
    UUID semTelefone = UUID.randomUUID();
    UUID apagado = UUID.randomUUID();
    when(produtorRepository.findById(semTelefone))
        .thenReturn(Optional.of(Produtor.builder().id(semTelefone).build()));
    when(produtorRepository.findById(apagado)).thenReturn(Optional.empty());

    assertThat(dados.de(credencial(PapelUsuario.PRODUTOR, semTelefone, null)).telefones())
        .isEmpty();
    assertThat(dados.de(credencial(PapelUsuario.PRODUTOR, apagado, null)))
        .satisfies(t -> {
          assertThat(t.documentos()).containsExactly(CPF);
          assertThat(t.telefones()).isEmpty();
        });
  }

  @Test
  @DisplayName("contador empresarial: o telefone dele e o CNPJ e o telefone do escritorio atual")
  void contadorEmpresarial() {
    UUID contadorId = UUID.randomUUID();
    UUID escritorioId = UUID.randomUUID();
    when(contadorRepository.findById(contadorId)).thenReturn(Optional.of(Contador.builder()
        .id(contadorId).telefone("45999990000").escritorioId(escritorioId).build()));
    when(escritorioRepository.findById(escritorioId)).thenReturn(Optional.of(Escritorio.builder()
        .id(escritorioId).tipo(TipoEscritorio.EMPRESARIAL).nome("Campo Verde")
        .cnpj("11444777000161").telefone("4533330000").build()));

    PoliticaSenha.DadosDoTitular titular =
        dados.de(credencial(PapelUsuario.CONTADOR, null, contadorId));

    assertThat(titular.documentos()).containsExactly(CPF, "11444777000161");
    assertThat(titular.telefones()).containsExactly("45999990000", "4533330000");
    verifyNoInteractions(produtorRepository);
  }

  @Test
  @DisplayName("contador pessoal (sem CNPJ) e contador fora da equipe (sem escritorio): sem erro")
  void contadorSemEscritorioOuPessoal() {
    UUID pessoal = UUID.randomUUID();
    UUID escritorioPessoal = UUID.randomUUID();
    UUID foraDaEquipe = UUID.randomUUID();
    when(contadorRepository.findById(pessoal)).thenReturn(Optional.of(Contador.builder()
        .id(pessoal).escritorioId(escritorioPessoal).build()));
    when(escritorioRepository.findById(escritorioPessoal)).thenReturn(Optional.of(
        Escritorio.builder().id(escritorioPessoal).nome("Patricia").build()));
    when(contadorRepository.findById(foraDaEquipe))
        .thenReturn(Optional.of(Contador.builder().id(foraDaEquipe).build()));

    assertThat(dados.de(credencial(PapelUsuario.CONTADOR, null, pessoal)))
        .satisfies(t -> {
          assertThat(t.documentos()).containsExactly(CPF);
          assertThat(t.telefones()).isEmpty();
        });
    assertThat(dados.de(credencial(PapelUsuario.CONTADOR, null, foraDaEquipe)).documentos())
        .containsExactly(CPF);
  }
}
