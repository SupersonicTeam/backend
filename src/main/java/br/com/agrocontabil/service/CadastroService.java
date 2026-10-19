package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.exception.CadastroConflitoException;
import br.com.agrocontabil.mapper.CredencialMapper;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.PapelUsuario;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cadastro de conta: cria o produtor ou o contador e a credencial de acesso (issue #205).
 *
 * <p><strong>Nunca reivindica identidade existente.</strong> Se o CPF/CNPJ já pertence a um
 * produtor ou contador — com ou sem credencial —, o cadastro é recusado com o mesmo 409 genérico
 * do e-mail repetido. Vincular a credencial ao registro existente entregaria a conta (e os dados
 * fiscais) a qualquer um que soubesse o CPF do titular.
 *
 * <p><strong>Hash fora da transação.</strong> O BCrypt de custo 12 leva centenas de milissegundos
 * de CPU; calculado dentro da transação, cada cadastro seguraria uma conexão do pool esse tempo
 * todo — o mesmo problema que a Onda 3 tirou da emissão. Por isso o método não é
 * {@code @Transactional}: valida e gera o hash antes, e só a checagem de duplicidade e as duas
 * gravações rodam num {@link TransactionTemplate} curto.
 *
 * <p>A checagem de duplicidade antes de gravar dá a resposta certa no caso comum; a corrida entre
 * dois cadastros simultâneos do mesmo e-mail ou documento é barrada pelas restrições únicas da
 * {@code credencial_usuario} e vira o mesmo 409.
 */
@Service
public class CadastroService {

  private static final Logger log = LoggerFactory.getLogger(CadastroService.class);

  /** Nome do campo da senha no request, para o {@code field} dos erros da política. */
  static final String CAMPO_SENHA = "senha";

  /** Restrições únicas que dois cadastros simultâneos podem disputar. */
  private static final List<String> RESTRICOES_UNICAS_DO_CADASTRO = List.of(
      "uk_credencial_email", "uk_credencial_papel_cpf_cnpj", "uk_credencial_produtor",
      "uk_credencial_contador", "uk_contador_cpf_cnpj");

  private final ProdutorRepository produtorRepository;
  private final ContadorRepository contadorRepository;
  private final CredencialUsuarioRepository credencialRepository;
  private final PasswordEncoder passwordEncoder;
  private final PoliticaSenha politicaSenha;
  private final CredencialMapper credencialMapper;
  private final TransactionTemplate transacao;

  public CadastroService(
      ProdutorRepository produtorRepository,
      ContadorRepository contadorRepository,
      CredencialUsuarioRepository credencialRepository,
      PasswordEncoder passwordEncoder,
      PoliticaSenha politicaSenha,
      CredencialMapper credencialMapper,
      PlatformTransactionManager transactionManager) {
    this.produtorRepository = produtorRepository;
    this.contadorRepository = contadorRepository;
    this.credencialRepository = credencialRepository;
    this.passwordEncoder = passwordEncoder;
    this.politicaSenha = politicaSenha;
    this.credencialMapper = credencialMapper;
    this.transacao = new TransactionTemplate(transactionManager);
  }

  /**
   * Cria a conta.
   *
   * @param request dados do cadastro, já validados por Bean Validation
   * @return id da identidade criada, papel e e-mail
   * @throws br.com.agrocontabil.exception.SenhaFracaException se a senha não atender a política
   * @throws CadastroConflitoException se o e-mail ou o CPF/CNPJ já estiver cadastrado
   */
  public CadastroResponse cadastrar(CadastroRequest request) {
    String email = normalizarEmail(request.email());
    String cpfCnpj = request.cpfCnpj();
    String senha = PoliticaSenha.normalizar(request.senha());

    politicaSenha.validar(senha, email, cpfCnpj, CAMPO_SENHA);
    String senhaHash = passwordEncoder.encode(senha);

    CredencialUsuario credencial;
    try {
      credencial = transacao.execute(status -> gravar(request, email, cpfCnpj, senhaHash));
    } catch (DataIntegrityViolationException e) {
      if (!violouUnicidadeDeCadastro(e)) {
        // Outra restricao (coluna nova esquecida, FK): e bug, nao duplicidade — nao mascarar.
        throw e;
      }
      // Corrida entre dois cadastros do mesmo e-mail/documento: a restricao unica decidiu.
      log.info("Cadastro recusado pela restricao unica (corrida de cadastro simultaneo).");
      throw new CadastroConflitoException();
    }

    log.info("Conta criada: papel={}, usuarioId={}", credencial.getPapel(), credencial.usuarioId());
    return credencialMapper.toCadastroResponse(credencial);
  }

  private CredencialUsuario gravar(
      CadastroRequest request, String email, String cpfCnpj, String senhaHash) {
    PapelUsuario papel = request.papel();
    if (credencialRepository.existsByEmail(email) || documentoJaCadastrado(papel, cpfCnpj)) {
      throw new CadastroConflitoException();
    }

    CredencialUsuario.CredencialUsuarioBuilder credencial = CredencialUsuario.builder()
        .email(email)
        .senhaHash(senhaHash)
        .papel(papel)
        .cpfCnpj(cpfCnpj);

    UUID usuarioId;
    if (papel == PapelUsuario.CONTADOR) {
      usuarioId = contadorRepository.saveAndFlush(Contador.builder()
          .nomeCompleto(request.nomeCompleto().strip())
          .cpfCnpj(cpfCnpj)
          .email(email)
          .build()).getId();
      credencial.contadorId(usuarioId);
    } else {
      usuarioId = produtorRepository.saveAndFlush(Produtor.builder()
          .nomeCompleto(request.nomeCompleto().strip())
          .cpfCnpj(cpfCnpj)
          .tipoProdutor(cpfCnpj.length() == 11
              ? TipoProdutor.PESSOA_FISICA : TipoProdutor.PESSOA_JURIDICA)
          .email(email)
          .build()).getId();
      credencial.produtorId(usuarioId);
    }

    return credencialRepository.saveAndFlush(credencial.build());
  }

  /**
   * O documento já pertence a alguém neste papel — na tabela da identidade (em qualquer grafia)
   * ou numa credencial.
   */
  private boolean documentoJaCadastrado(PapelUsuario papel, String cpfCnpj) {
    List<String> grafias = List.of(cpfCnpj, comMascara(cpfCnpj));
    boolean naIdentidade = papel == PapelUsuario.CONTADOR
        ? contadorRepository.existsByCpfCnpjIn(grafias)
        : produtorRepository.existsByCpfCnpjIn(grafias);
    return naIdentidade || credencialRepository.existsByPapelAndCpfCnpj(papel, cpfCnpj);
  }

  /**
   * Normaliza o e-mail de login: NFKC, sem espaços nas pontas e em minúsculas.
   *
   * <p>A parte local de um e-mail é tecnicamente sensível a maiúsculas (RFC 5321), mas nenhum
   * provedor real a trata assim; aceitar {@code Joao@} e {@code joao@} como contas diferentes
   * abriria espaço para uma conta-sombra com o e-mail de outra pessoa. Pelo mesmo motivo o NFKC:
   * {@code josé@} com o acento composto e com o acento combinante são o mesmo endereço na tela e
   * precisam ser a mesma conta.
   *
   * @param email e-mail como digitado
   * @return e-mail normalizado
   */
  public static String normalizarEmail(String email) {
    return email == null
        ? null
        : Normalizer.normalize(email, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
  }

  /**
   * A violação veio de uma das restrições únicas que o cadastro disputa (changeSets 0024/0031)?
   *
   * <p>Pelo nome da restrição, que o Hibernate extrai da mensagem do driver; o PostgreSQL o
   * devolve em minúsculas e o H2 em maiúsculas, daí a comparação sem caixa.
   */
  public static boolean violouUnicidadeDeCadastro(DataIntegrityViolationException e) {
    Throwable causa = e;
    while (causa != null) {
      if (causa instanceof ConstraintViolationException violacao
          && violacao.getConstraintName() != null) {
        String nome = violacao.getConstraintName().toLowerCase(Locale.ROOT);
        return RESTRICOES_UNICAS_DO_CADASTRO.stream().anyMatch(nome::contains);
      }
      causa = causa.getCause();
    }
    return false;
  }

  /** CPF {@code 000.000.000-00} ou CNPJ {@code 00.000.000/0000-00}, a grafia do seed. */
  static String comMascara(String digitos) {
    if (digitos.length() == 11) {
      return "%s.%s.%s-%s".formatted(digitos.substring(0, 3), digitos.substring(3, 6),
          digitos.substring(6, 9), digitos.substring(9));
    }
    return "%s.%s.%s/%s-%s".formatted(digitos.substring(0, 2), digitos.substring(2, 5),
        digitos.substring(5, 8), digitos.substring(8, 12), digitos.substring(12));
  }
}
