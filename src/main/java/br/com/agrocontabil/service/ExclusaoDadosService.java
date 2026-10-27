package br.com.agrocontabil.service;

import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.repository.AlertaTributarioRepository;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ConviteCarteiraRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.NotificacaoRepository;
import br.com.agrocontabil.repository.PendenciaAnexoRepository;
import br.com.agrocontabil.repository.PendenciaRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.RedefinicaoSenhaRepository;
import br.com.agrocontabil.repository.SessaoRefreshRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.security.UsuarioAutenticado;
import br.com.agrocontabil.security.UsuarioAutenticadoProvider;
import br.com.agrocontabil.service.auditoria.Auditado;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Direito ao esquecimento (LGPD art. 18, VI — issue #148): o titular elimina os próprios dados
 * pessoais.
 *
 * <p><strong>Anonimização, não apagamento.</strong> As notas fiscais, guias e apurações do
 * produtor ficam — a guarda de documentos fiscais por 5 anos é obrigação legal (CTN art. 173; LGPD
 * art. 16, I autoriza conservar para cumprir obrigação legal). Por isso o registro do produtor
 * continua existindo, com o que identifica o contribuinte nesses documentos (CPF/CNPJ, inscrição
 * estadual, UF, tipo e regime), e perde o que é só pessoal: nome, e-mail, telefone, endereço,
 * culturas e faturamento. O nome que já está dentro do XML de uma NF-e autorizada não muda — o
 * documento fiscal é imutável.
 *
 * <p>Do contador, que não é parte dos documentos fiscais, saem também o CPF/CNPJ e o CRC.
 *
 * <p>Junto: a credencial é apagada (com ela, sessões e links de redefinição — a conta deixa de
 * existir e todo token emitido morre na próxima requisição), os alertas são apagados, e os
 * vínculos produtor×escritório do produtor são apagados (dado de relacionamento, não fiscal) —
 * com eles a ficha do cliente (observações e etiquetas), os convites da carteira (#177) e os
 * pedidos de documento com os anexos (#180). O
 * contador sai da equipe; se era o único membro, o escritório sai inteiro (#228), e o último
 * administrador de uma equipe precisa antes promover outro (409).
 *
 * <p><strong>Só o próprio titular, com a senha.</strong> O {@code id} do caminho precisa ser o de
 * quem está autenticado (404 para outro, como o resto do anti-IDOR), e a senha atual é conferida
 * com a reserva antitentativa do login ({@code SenhaService.confirmarSenhaAtual}): é irreversível,
 * e um access token roubado sozinho não pode dispará-la.
 */
@Service
@ConditionalOnWebApplication
public class ExclusaoDadosService {

  private static final Logger log = LoggerFactory.getLogger(ExclusaoDadosService.class);

  /** Nome que fica no lugar do nome do titular. */
  static final String NOME_ANONIMIZADO = "Titular excluido (LGPD)";

  private final UsuarioAutenticadoProvider usuarioProvider;
  private final SenhaService senhaService;
  private final ProdutorRepository produtorRepository;
  private final ContadorRepository contadorRepository;
  private final CredencialUsuarioRepository credencialRepository;
  private final SessaoRefreshRepository sessaoRepository;
  private final RedefinicaoSenhaRepository redefinicaoRepository;
  private final AlertaTributarioRepository alertaRepository;
  private final VinculoCarteiraRepository vinculoRepository;
  private final ConviteCarteiraRepository conviteRepository;
  private final PendenciaRepository pendenciaRepository;
  private final PendenciaAnexoRepository anexoRepository;
  private final NotificacaoRepository notificacaoRepository;
  private final EquipeEscritorioService equipeService;
  private final Clock clock;
  private final TransactionTemplate transacao;

  public ExclusaoDadosService(
      UsuarioAutenticadoProvider usuarioProvider,
      SenhaService senhaService,
      ProdutorRepository produtorRepository,
      ContadorRepository contadorRepository,
      CredencialUsuarioRepository credencialRepository,
      SessaoRefreshRepository sessaoRepository,
      RedefinicaoSenhaRepository redefinicaoRepository,
      AlertaTributarioRepository alertaRepository,
      VinculoCarteiraRepository vinculoRepository,
      ConviteCarteiraRepository conviteRepository,
      PendenciaRepository pendenciaRepository,
      PendenciaAnexoRepository anexoRepository,
      NotificacaoRepository notificacaoRepository,
      EquipeEscritorioService equipeService,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.usuarioProvider = usuarioProvider;
    this.senhaService = senhaService;
    this.produtorRepository = produtorRepository;
    this.contadorRepository = contadorRepository;
    this.credencialRepository = credencialRepository;
    this.sessaoRepository = sessaoRepository;
    this.redefinicaoRepository = redefinicaoRepository;
    this.alertaRepository = alertaRepository;
    this.vinculoRepository = vinculoRepository;
    this.conviteRepository = conviteRepository;
    this.pendenciaRepository = pendenciaRepository;
    this.anexoRepository = anexoRepository;
    this.notificacaoRepository = notificacaoRepository;
    this.equipeService = equipeService;
    this.clock = clock;
    this.transacao = new TransactionTemplate(transactionManager);
  }

  /**
   * Elimina os dados pessoais de quem está autenticado.
   *
   * @param id    id do produtor ou do contador, que precisa ser o de quem está autenticado
   * @param senha senha atual
   * @throws ResourceNotFoundException se o id não for o de quem está autenticado
   * @throws br.com.agrocontabil.exception.SenhaAtualIncorretaException se a senha não conferir
   */
  @Auditado(acao = AcaoAuditada.DADOS_PESSOAIS_EXCLUIDOS, recurso = RecursoAuditado.CONTA)
  public void excluir(UUID id, String senha) {
    UsuarioAutenticado usuario = usuarioProvider.get();
    if (!usuario.id().equals(id)) {
      // 404, nao 403: nao confirma que o id existe.
      throw new ResourceNotFoundException("Usuario", id);
    }
    CredencialUsuario credencial = senhaService.confirmarSenhaAtual(senha);

    transacao.executeWithoutResult(status -> {
      if (usuario.papel() == PapelUsuario.CONTADOR) {
        anonimizarContador(id);
      } else {
        anonimizarProdutor(id, credencial.getEmail());
      }
      // Sem esperar o CASCADE do banco: os filhos vao explicitamente, na mesma transacao.
      sessaoRepository.revogarDaCredencial(credencial.getId(), OffsetDateTime.now(clock));
      redefinicaoRepository.apagarDaCredencial(credencial.getId());
      credencialRepository.deleteById(credencial.getId());
    });
    log.info("Dados pessoais excluidos (anonimizados) a pedido do titular: papel={}, id={}",
        usuario.papel(), id);
  }

  private void anonimizarProdutor(UUID id, String email) {
    produtorRepository.findById(id).ifPresent(produtor -> {
      produtor.setNomeCompleto(NOME_ANONIMIZADO);
      produtor.setEmail(null);
      produtor.setTelefone(null);
      produtor.setEndereco(null);
      produtor.setCulturas(null);
      produtor.setFaturamentoAnual(null);
    });
    // O contador da carteira deixa de alcancar a conta encerrada (guias, calendario); a ficha
    // (observacoes, etiquetas) mora no vinculo e vai junto.
    apagarVinculos(vinculoRepository.findIdsDoProdutor(id));
    conviteRepository.apagarDoProdutor(id, email);
    // Pedidos de documento e anexos (#180): documento enviado ao escritorio, nao fiscal emitido.
    anexoRepository.apagarDoProdutor(id);
    pendenciaRepository.apagarDoProdutor(id);
    notificacaoRepository.apagarDoProdutor(id);
    alertaRepository.apagarDoProdutor(id);
  }

  private void anonimizarContador(UUID id) {
    Contador membro = contadorRepository.findById(id).orElse(null);
    if (membro == null) {
      return;
    }
    // A equipe primeiro (#228): sozinho, o escritorio inteiro sai (carteira, convites, catalogo);
    // com equipe, so ele, e o ultimo administrador de uma equipe nao sai (409, nada e apagado).
    equipeService.sairPorExclusao(membro);
    contadorRepository.findById(id).ifPresent(contador -> {
      contador.setNomeCompleto(NOME_ANONIMIZADO);
      contador.setEmail(null);
      contador.setTelefone(null);
      // O contador nao e parte dos documentos fiscais: sem guarda legal para o documento e o
      // CRC, que juntos reidentificariam a pessoa no cadastro publico do conselho.
      contador.setCpfCnpj(null);
      contador.setCrc(null);
    });
  }

  private void apagarVinculos(List<UUID> vinculos) {
    if (vinculos.isEmpty()) {
      return;
    }
    vinculoRepository.apagarEtiquetasDosVinculos(vinculos);
    vinculoRepository.apagarPorIds(vinculos);
  }
}
