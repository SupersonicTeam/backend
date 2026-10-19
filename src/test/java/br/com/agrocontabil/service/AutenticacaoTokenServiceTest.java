package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.JwtService;
import br.com.agrocontabil.security.JwtService.TokenConferido;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.security.UsuarioAutenticado;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AutenticacaoTokenService — o banco decide se o token ainda vale (#206)")
class AutenticacaoTokenServiceTest {

  private static final UUID CREDENCIAL = UUID.randomUUID();
  private static final UUID PRODUTOR = UUID.randomUUID();

  @Mock private JwtService jwtService;
  @Mock private CredencialUsuarioRepository credencialRepository;
  @Mock private ProdutorRepository produtorRepository;
  @Mock private ContadorRepository contadorRepository;

  private AutenticacaoTokenService service;

  @BeforeEach
  void setUp() {
    service = new AutenticacaoTokenService(
        jwtService, credencialRepository, produtorRepository, contadorRepository);
  }

  private CredencialUsuario credencial(boolean ativo, int versao, UUID produtorId) {
    return CredencialUsuario.builder().id(CREDENCIAL).papel(PapelUsuario.PRODUTOR)
        .produtorId(produtorId).ativo(ativo).versaoSessao(versao).build();
  }

  private void tokenValido(int versao) {
    when(jwtService.conferir("t"))
        .thenReturn(Optional.of(new TokenConferido(CREDENCIAL, PapelUsuario.PRODUTOR, versao)));
  }

  @Test
  @DisplayName("credencial ativa, mesmo papel e mesma versao: identidade do produtor")
  void valido() {
    tokenValido(1);
    when(credencialRepository.findById(CREDENCIAL))
        .thenReturn(Optional.of(credencial(true, 1, PRODUTOR)));
    when(produtorRepository.findById(PRODUTOR))
        .thenReturn(Optional.of(Produtor.builder().id(PRODUTOR).nomeCompleto("F").build()));

    assertThat(service.autenticar("t")).map(UsuarioAutenticado::id).contains(PRODUTOR);
  }

  @Test
  @DisplayName("versao de sessao diferente ou credencial desativada: vazio")
  void revogado() {
    tokenValido(1);
    when(credencialRepository.findById(CREDENCIAL))
        .thenReturn(Optional.of(credencial(true, 2, PRODUTOR)))
        .thenReturn(Optional.of(credencial(false, 1, PRODUTOR)));

    assertThat(service.autenticar("t")).isEmpty();
    assertThat(service.autenticar("t")).isEmpty();
    verify(produtorRepository, never()).findById(any());
  }

  @Test
  @DisplayName("credencial inconsistente (papel sem a referencia): vazio, sem excecao")
  void referenciaNula() {
    tokenValido(0);
    when(credencialRepository.findById(CREDENCIAL))
        .thenReturn(Optional.of(credencial(true, 0, null)));

    assertThat(service.autenticar("t")).isEmpty();
    verify(produtorRepository, never()).findById(any());
  }

  @Test
  @DisplayName("token invalido nem chega ao banco")
  void tokenInvalido() {
    when(jwtService.conferir("t")).thenReturn(Optional.empty());

    assertThat(service.autenticar("t")).isEmpty();
    verify(credencialRepository, never()).findById(any());
  }
}
