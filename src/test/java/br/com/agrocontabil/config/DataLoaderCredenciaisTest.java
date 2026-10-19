package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.PoliticaSenha;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@DisplayName("DataLoader — credenciais das contas semeadas (#206)")
class DataLoaderCredenciaisTest {

  @Mock private ProdutorRepository produtorRepository;
  @Mock private ContadorRepository contadorRepository;
  @Mock private CredencialUsuarioRepository credencialRepository;
  @Mock private PasswordEncoder passwordEncoder;

  private DataLoader loader(String senha) {
    return new DataLoader(produtorRepository, contadorRepository, credencialRepository,
        passwordEncoder, new PoliticaSenha(), senha);
  }

  private static Produtor produtor(String cpf, String email) {
    return Produtor.builder().id(UUID.randomUUID()).cpfCnpj(cpf).email(email).build();
  }

  private static Contador contador() {
    return Contador.builder().id(UUID.randomUUID()).cpfCnpj("12.345.678/0001-95")
        .email("contato@campocerto.example.com").build();
  }

  @Test
  @DisplayName("SEED_SENHA vazia: nenhuma credencial (o X-Usuario-Id segue valendo)")
  void semSenhaNaoSemeia() {
    loader("").semearCredenciais(produtor("123.456.789-00", "a@x.com"),
        produtor("987.654.321-00", "b@x.com"), contador());
    loader(null).semearCredenciais(produtor("123.456.789-00", "a@x.com"),
        produtor("987.654.321-00", "b@x.com"), contador());

    verifyNoInteractions(credencialRepository, passwordEncoder);
  }

  @Test
  @DisplayName("SEED_SENHA fraca: recusada pela mesma politica do cadastro")
  void senhaFracaNaoSemeia() {
    loader("senha123456").semearCredenciais(produtor("123.456.789-00", "a@x.com"),
        produtor("987.654.321-00", "b@x.com"), contador());

    verifyNoInteractions(credencialRepository, passwordEncoder);
  }

  @Test
  @DisplayName("SEED_SENHA forte: tres credenciais, documento so com digitos, um hash so")
  void semeiaTres() {
    when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}seed");

    loader("cavalo bateria grampo correto").semearCredenciais(
        produtor("123.456.789-00", "Joao.Produtor@example.com"),
        produtor("987.654.321-00", "maria@example.com"), contador());

    ArgumentCaptor<CredencialUsuario> salvas = ArgumentCaptor.forClass(CredencialUsuario.class);
    verify(credencialRepository, times(3)).save(salvas.capture());
    List<CredencialUsuario> credenciais = salvas.getAllValues();
    assertThat(credenciais).extracting(CredencialUsuario::getPapel)
        .containsExactly(PapelUsuario.PRODUTOR, PapelUsuario.PRODUTOR, PapelUsuario.CONTADOR);
    assertThat(credenciais).extracting(CredencialUsuario::getCpfCnpj)
        .containsExactly("12345678900", "98765432100", "12345678000195");
    assertThat(credenciais.get(0).getEmail()).isEqualTo("joao.produtor@example.com");
    assertThat(credenciais).allSatisfy(c -> assertThat(c.getSenhaHash()).isEqualTo("{bcrypt}seed"));
    verify(passwordEncoder, times(1)).encode(anyString());
  }

  @Test
  @DisplayName("credencial ja existente nao e tocada (a senha pode ter sido trocada em dev)")
  void idempotente() {
    when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}seed");
    when(credencialRepository.existsByEmail(anyString())).thenReturn(true);

    loader("cavalo bateria grampo correto").semearCredenciais(produtor("123.456.789-00", "a@x.com"),
        produtor("987.654.321-00", "b@x.com"), contador());

    verify(credencialRepository, never()).save(any());
  }
}
