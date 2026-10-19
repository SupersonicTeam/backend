package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.exception.CadastroConflitoException;
import br.com.agrocontabil.exception.SenhaFracaException;
import br.com.agrocontabil.mapper.CredencialMapper;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.PapelUsuario;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("CadastroService — conta com credencial (#205)")
class CadastroServiceTest {

  private static final String CPF = "52998224725";
  private static final String SENHA = "cavalo bateria grampo correto";

  @Mock private ProdutorRepository produtorRepository;
  @Mock private ContadorRepository contadorRepository;
  @Mock private CredencialUsuarioRepository credencialRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private PlatformTransactionManager transactionManager;

  private CadastroService service;

  @BeforeEach
  void setUp() {
    service = new CadastroService(produtorRepository, contadorRepository, credencialRepository,
        passwordEncoder, new PoliticaSenha(), new CredencialMapper(), transactionManager);
  }

  private void comTransacao() {
    TransactionStatus status = new SimpleTransactionStatus();
    when(transactionManager.getTransaction(any())).thenReturn(status);
  }

  private CadastroRequest pedido(PapelUsuario papel, String documento) {
    return new CadastroRequest(papel, "  Fazenda Boa Vista ", documento,
        "  Joaquim@Fazenda.COM.br ", SENHA);
  }

  @Test
  @DisplayName("produtor: grava produtor PF e credencial com hash e e-mail normalizado")
  void cadastraProdutor() {
    comTransacao();
    UUID produtorId = UUID.randomUUID();
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(produtorRepository.saveAndFlush(any(Produtor.class))).thenAnswer(inv -> {
      Produtor p = inv.getArgument(0);
      p.setId(produtorId);
      return p;
    });
    when(credencialRepository.saveAndFlush(any(CredencialUsuario.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    CadastroResponse resposta = service.cadastrar(pedido(PapelUsuario.PRODUTOR, CPF));

    assertThat(resposta.usuarioId()).isEqualTo(produtorId);
    assertThat(resposta.papel()).isEqualTo(PapelUsuario.PRODUTOR);
    assertThat(resposta.email()).isEqualTo("joaquim@fazenda.com.br");

    ArgumentCaptor<Produtor> produtor = ArgumentCaptor.forClass(Produtor.class);
    verify(produtorRepository).saveAndFlush(produtor.capture());
    assertThat(produtor.getValue().getNomeCompleto()).isEqualTo("Fazenda Boa Vista");
    assertThat(produtor.getValue().getTipoProdutor()).isEqualTo(TipoProdutor.PESSOA_FISICA);
    assertThat(produtor.getValue().getCpfCnpj()).isEqualTo(CPF);

    ArgumentCaptor<CredencialUsuario> credencial = ArgumentCaptor.forClass(CredencialUsuario.class);
    verify(credencialRepository).saveAndFlush(credencial.capture());
    assertThat(credencial.getValue().getSenhaHash()).isEqualTo("{bcrypt}hash");
    assertThat(credencial.getValue().getProdutorId()).isEqualTo(produtorId);
    assertThat(credencial.getValue().getContadorId()).isNull();
    assertThat(credencial.getValue().isAtivo()).isTrue();
    verifyNoInteractions(contadorRepository);
  }

  @Test
  @DisplayName("contador: grava contador e credencial com papel CONTADOR")
  void cadastraContador() {
    comTransacao();
    UUID contadorId = UUID.randomUUID();
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(contadorRepository.saveAndFlush(any(Contador.class))).thenAnswer(inv -> {
      Contador c = inv.getArgument(0);
      c.setId(contadorId);
      return c;
    });
    when(credencialRepository.saveAndFlush(any(CredencialUsuario.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    CadastroResponse resposta = service.cadastrar(pedido(PapelUsuario.CONTADOR, "11222333000181"));

    assertThat(resposta.usuarioId()).isEqualTo(contadorId);
    assertThat(resposta.papel()).isEqualTo(PapelUsuario.CONTADOR);
    verify(contadorRepository).existsByCpfCnpjIn(List.of("11222333000181", "11.222.333/0001-81"));
    verify(produtorRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("CPF de produtor ja existente (mesmo com mascara, sem credencial) nunca e reivindicado")
  void documentoExistenteNaoEhReivindicado() {
    comTransacao();
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(produtorRepository.existsByCpfCnpjIn(List.of(CPF, "529.982.247-25"))).thenReturn(true);

    assertThatThrownBy(() -> service.cadastrar(pedido(PapelUsuario.PRODUTOR, CPF)))
        .isInstanceOf(CadastroConflitoException.class);
    verify(produtorRepository, never()).saveAndFlush(any());
    verify(credencialRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("e-mail ja cadastrado responde o mesmo conflito do documento")
  void emailRepetido() {
    comTransacao();
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(credencialRepository.existsByEmail("joaquim@fazenda.com.br")).thenReturn(true);

    assertThatThrownBy(() -> service.cadastrar(pedido(PapelUsuario.PRODUTOR, CPF)))
        .isInstanceOf(CadastroConflitoException.class);
    verify(produtorRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("corrida barrada pela restricao unica vira o mesmo 409")
  void corridaViraConflito() {
    comTransacao();
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(produtorRepository.saveAndFlush(any(Produtor.class))).thenAnswer(inv -> {
      Produtor p = inv.getArgument(0);
      p.setId(UUID.randomUUID());
      return p;
    });
    when(credencialRepository.saveAndFlush(any(CredencialUsuario.class)))
        .thenThrow(new DataIntegrityViolationException("uk_credencial_email"));

    assertThatThrownBy(() -> service.cadastrar(pedido(PapelUsuario.PRODUTOR, CPF)))
        .isInstanceOf(CadastroConflitoException.class);
  }

  @Test
  @DisplayName("senha fraca para antes do hash e de qualquer acesso ao banco")
  void senhaFracaNaoTocaBanco() {
    CadastroRequest fraca = new CadastroRequest(PapelUsuario.PRODUTOR, "Fazenda", CPF,
        "joaquim@fazenda.com.br", "curta");

    assertThatThrownBy(() -> service.cadastrar(fraca)).isInstanceOf(SenhaFracaException.class);
    verify(passwordEncoder, never()).encode(anyString());
    verifyNoInteractions(transactionManager, credencialRepository, produtorRepository);
  }

  @Test
  @DisplayName("mascara do documento segue a grafia do seed")
  void mascara() {
    assertThat(CadastroService.comMascara("12345678900")).isEqualTo("123.456.789-00");
    assertThat(CadastroService.comMascara("12345678000195")).isEqualTo("12.345.678/0001-95");
  }

  @Test
  @DisplayName("toString do request nao carrega a senha")
  void toStringSemSenha() {
    assertThat(pedido(PapelUsuario.PRODUTOR, CPF).toString()).doesNotContain(SENHA, CPF);
  }
}
