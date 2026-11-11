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
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.PapelUsuario;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
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
  @Mock private EscritorioRepository escritorioRepository;
  @Mock private CredencialUsuarioRepository credencialRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private PlatformTransactionManager transactionManager;

  private CadastroService service;

  @BeforeEach
  void setUp() {
    service = new CadastroService(produtorRepository, contadorRepository, escritorioRepository,
        credencialRepository,
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

  /** Stubs do caminho feliz do contador; devolve o id que o escritório recebe. */
  private UUID comContadorGravavel(UUID contadorId) {
    comTransacao();
    UUID escritorioId = UUID.randomUUID();
    when(escritorioRepository.save(any(Escritorio.class))).thenAnswer(inv -> {
      Escritorio e = inv.getArgument(0);
      e.setId(escritorioId);
      return e;
    });
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(contadorRepository.saveAndFlush(any(Contador.class))).thenAnswer(inv -> {
      Contador c = inv.getArgument(0);
      c.setId(contadorId);
      return c;
    });
    when(credencialRepository.saveAndFlush(any(CredencialUsuario.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    return escritorioId;
  }

  private CadastroRequest pedidoDeContador(CadastroRequest.EscritorioDoCadastro escritorio,
      String crc, String telefone) {
    return new CadastroRequest(PapelUsuario.CONTADOR, " Patricia Moreira ", CPF,
        "patricia@exemplo.com.br", SENHA, escritorio, crc, telefone);
  }

  @Test
  @DisplayName("contador sem escritorio: unipessoal com o nome dele, sem CNPJ, como administrador")
  void cadastraContador() {
    UUID contadorId = UUID.randomUUID();
    UUID escritorioId = comContadorGravavel(contadorId);

    CadastroResponse resposta = service.cadastrar(pedido(PapelUsuario.CONTADOR, CPF));

    assertThat(resposta.usuarioId()).isEqualTo(contadorId);
    ArgumentCaptor<Contador> contador = ArgumentCaptor.forClass(Contador.class);
    verify(contadorRepository).saveAndFlush(contador.capture());
    assertThat(contador.getValue().getEscritorioId()).isEqualTo(escritorioId);
    assertThat(contador.getValue().getFuncao()).isEqualTo(FuncaoEscritorio.ADMINISTRADOR);
    assertThat(contador.getValue().getCrc()).isNull();
    assertThat(contador.getValue().getTelefone()).isNull();
    ArgumentCaptor<Escritorio> escritorio = ArgumentCaptor.forClass(Escritorio.class);
    verify(escritorioRepository).save(escritorio.capture());
    assertThat(escritorio.getValue().getNome()).isEqualTo("Fazenda Boa Vista");
    assertThat(escritorio.getValue().getCnpj()).isNull();
    assertThat(resposta.papel()).isEqualTo(PapelUsuario.CONTADOR);
    verify(contadorRepository).existsByCpfCnpjIn(List.of(CPF, "529.982.247-25"));
    verify(produtorRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("contador com escritorio (#519): nome e CNPJ no escritorio; CRC e telefone nele")
  void cadastraContadorComEscritorio() {
    UUID contadorId = UUID.randomUUID();
    UUID escritorioId = comContadorGravavel(contadorId);

    service.cadastrar(pedidoDeContador(new CadastroRequest.EscritorioDoCadastro(
        "  Contabilidade Rural Oeste ", "48315276000195"), " PR-012345/O ", "45999990000"));

    ArgumentCaptor<Escritorio> escritorio = ArgumentCaptor.forClass(Escritorio.class);
    verify(escritorioRepository).save(escritorio.capture());
    assertThat(escritorio.getValue().getNome()).isEqualTo("Contabilidade Rural Oeste");
    assertThat(escritorio.getValue().getCnpj()).isEqualTo("48315276000195");
    ArgumentCaptor<Contador> contador = ArgumentCaptor.forClass(Contador.class);
    verify(contadorRepository).saveAndFlush(contador.capture());
    assertThat(contador.getValue().getNomeCompleto()).isEqualTo("Patricia Moreira");
    assertThat(contador.getValue().getCpfCnpj()).isEqualTo(CPF);
    assertThat(contador.getValue().getCrc()).isEqualTo("PR-012345/O");
    assertThat(contador.getValue().getTelefone()).isEqualTo("45999990000");
    assertThat(contador.getValue().getEscritorioId()).isEqualTo(escritorioId);
    assertThat(contador.getValue().getFuncao()).isEqualTo(FuncaoEscritorio.ADMINISTRADOR);
  }

  @Test
  @DisplayName("contador (#519): CNPJ do escritorio, CRC e telefone vazios = nao informados")
  void camposVaziosNaoSaoGravados() {
    comContadorGravavel(UUID.randomUUID());

    service.cadastrar(pedidoDeContador(
        new CadastroRequest.EscritorioDoCadastro("Patricia Moreira", ""), "   ", ""));

    ArgumentCaptor<Escritorio> escritorio = ArgumentCaptor.forClass(Escritorio.class);
    verify(escritorioRepository).save(escritorio.capture());
    assertThat(escritorio.getValue().getNome()).isEqualTo("Patricia Moreira");
    assertThat(escritorio.getValue().getCnpj()).isNull();
    ArgumentCaptor<Contador> contador = ArgumentCaptor.forClass(Contador.class);
    verify(contadorRepository).saveAndFlush(contador.capture());
    assertThat(contador.getValue().getCrc()).isNull();
    assertThat(contador.getValue().getTelefone()).isNull();
  }

  @Test
  @DisplayName("contador pelo convite (#228): o escritorio do request e ignorado, vale o ingresso")
  void ingressoIgnoraEscritorioDoRequest() {
    comTransacao();
    UUID escritorioDaEquipe = UUID.randomUUID();
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(contadorRepository.saveAndFlush(any(Contador.class))).thenAnswer(inv -> {
      Contador c = inv.getArgument(0);
      c.setId(UUID.randomUUID());
      return c;
    });
    when(credencialRepository.saveAndFlush(any(CredencialUsuario.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.cadastrar(pedidoDeContador(
        new CadastroRequest.EscritorioDoCadastro("Outro", "48315276000195"), null, null),
        contador -> {
          contador.setEscritorioId(escritorioDaEquipe);
          contador.setFuncao(FuncaoEscritorio.ASSISTENTE);
        });

    verifyNoInteractions(escritorioRepository);
    ArgumentCaptor<Contador> contador = ArgumentCaptor.forClass(Contador.class);
    verify(contadorRepository).saveAndFlush(contador.capture());
    assertThat(contador.getValue().getEscritorioId()).isEqualTo(escritorioDaEquipe);
    assertThat(contador.getValue().getFuncao()).isEqualTo(FuncaoEscritorio.ASSISTENTE);
  }

  @Test
  @DisplayName("produtor (#519): telefone informado vai para o produtor")
  void produtorComTelefone() {
    comTransacao();
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(produtorRepository.saveAndFlush(any(Produtor.class))).thenAnswer(inv -> {
      Produtor p = inv.getArgument(0);
      p.setId(UUID.randomUUID());
      return p;
    });
    when(credencialRepository.saveAndFlush(any(CredencialUsuario.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.cadastrar(new CadastroRequest(PapelUsuario.PRODUTOR, "Fazenda", CPF,
        "joaquim@fazenda.com.br", SENHA, null, null, "6533330001"));

    ArgumentCaptor<Produtor> produtor = ArgumentCaptor.forClass(Produtor.class);
    verify(produtorRepository).saveAndFlush(produtor.capture());
    assertThat(produtor.getValue().getTelefone()).isEqualTo("6533330001");
    verifyNoInteractions(contadorRepository, escritorioRepository);
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
    // H2 devolve o nome da restricao em maiusculas; o PostgreSQL, em minusculas.
    when(credencialRepository.saveAndFlush(any(CredencialUsuario.class)))
        .thenThrow(violacao("UK_CREDENCIAL_EMAIL"));

    assertThatThrownBy(() -> service.cadastrar(pedido(PapelUsuario.PRODUTOR, CPF)))
        .isInstanceOf(CadastroConflitoException.class);
  }

  @Test
  @DisplayName("violacao que nao e de unicidade do cadastro sobe como esta (nao vira 'ja cadastrado')")
  void outraViolacaoNaoEhMascarada() {
    comTransacao();
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(produtorRepository.saveAndFlush(any(Produtor.class)))
        .thenThrow(violacao("fk_qualquer_coisa"));

    assertThatThrownBy(() -> service.cadastrar(pedido(PapelUsuario.PRODUTOR, CPF)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .isNotInstanceOf(CadastroConflitoException.class);
    assertThat(CadastroService.violouUnicidadeDeCadastro(
        new DataIntegrityViolationException("sem causa"))).isFalse();
  }

  @Test
  @DisplayName("e-mail normalizado por NFKC: acento composto e combinante sao a mesma conta")
  void emailNfkc() {
    assertThat(CadastroService.normalizarEmail(" José@Fazenda.com "))
        .isEqualTo(CadastroService.normalizarEmail("josé@fazenda.com"))
        .isEqualTo("josé@fazenda.com");
    assertThat(CadastroService.normalizarEmail(null)).isNull();
  }

  private static DataIntegrityViolationException violacao(String restricao) {
    return new DataIntegrityViolationException("violacao", new ConstraintViolationException(
        "violacao", new SQLException("dup"), restricao));
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
  @DisplayName("toString do request sem senha, e-mail, documento, CNPJ, CRC nem telefone (#519)")
  void toStringSemSenha() {
    assertThat(pedido(PapelUsuario.PRODUTOR, CPF).toString())
        .doesNotContain(SENHA, CPF, "Joaquim", "joaquim");

    CadastroRequest.EscritorioDoCadastro escritorio =
        new CadastroRequest.EscritorioDoCadastro("Contabilidade Rural Oeste", "48315276000195");
    String contador = pedidoDeContador(escritorio, "PR-012345/O", "45999990000").toString();
    assertThat(contador).isEqualTo("CadastroRequest[papel=CONTADOR]")
        .doesNotContain(CPF, "48315276000195", "PR-012345", "45999990000", "Rural Oeste",
            "Patricia", "patricia");
    assertThat(escritorio.toString()).doesNotContain("48315276000195", "Rural Oeste");
  }
}
