package br.com.agrocontabil.service;

import br.com.agrocontabil.common.FormatoSlug;
import br.com.agrocontabil.config.EnderecoEscritorioProperties;
import br.com.agrocontabil.dto.EnderecoEscritorioRequest;
import br.com.agrocontabil.dto.EscritorioPublicoResponse;
import br.com.agrocontabil.dto.EscritorioResponse;
import br.com.agrocontabil.dto.ResultadoAuditavel;
import br.com.agrocontabil.exception.EnderecoExigeCnpjException;
import br.com.agrocontabil.exception.EnderecoNaoEncontradoException;
import br.com.agrocontabil.exception.EnderecoTrocaRecenteException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.exception.SlugIndisponivelException;
import br.com.agrocontabil.mapper.EscritorioMapper;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.EscritorioSlugAnterior;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.EscritorioSlugAnteriorRepository;
import br.com.agrocontabil.security.MembroEscritorioProvider;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.auditoria.Auditado;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Endereço próprio do escritório, {@code {slug}.agroconia.com.br} (issue #464): definir, trocar e
 * remover o slug, a carência do slug que sai e o que acontece com ele quando o escritório é
 * apagado.
 *
 * <p><strong>Marca e porta de entrada, nunca autorização.</strong> O que cada um vê continua
 * decidido pelo {@link MembroEscritorioProvider} e pelo vínculo {@code ATIVO}; o slug só identifica
 * o escritório.
 *
 * <p><strong>Regras.</strong> Só o {@code ADMINISTRADOR}; só escritório com CNPJ (o unipessoal
 * nasce com o nome do contador, e o endereço fica público); uma gravação a cada intervalo (30
 * dias), mas remover é sempre permitido; o slug que sai fica em carência (90 dias) e ninguém mais o
 * registra; reservado, em uso e em carência recebem a mesma resposta.
 *
 * <p><strong>Dado pessoal.</strong> O slug pode carregar o nome de uma pessoa: nunca vai a log nem
 * à auditoria, que registra só {@code COM_ENDERECO}/{@code SEM_ENDERECO}.
 */
@Service
public class EnderecoEscritorioService {

  private static final Logger log = LoggerFactory.getLogger(EnderecoEscritorioService.class);

  /** Estado auditado com endereço. */
  static final String COM_ENDERECO = "COM_ENDERECO";

  /** Estado auditado sem endereço. */
  static final String SEM_ENDERECO = "SEM_ENDERECO";

  private final MembroEscritorioProvider membroProvider;
  private final EscritorioRepository escritorioRepository;
  private final EscritorioSlugAnteriorRepository anteriorRepository;
  private final ContadorRepository contadorRepository;
  private final SlugsReservados reservados;
  private final EnderecoEscritorioProperties propriedades;
  private final Clock clock;
  private final TransactionTemplate transacao;

  public EnderecoEscritorioService(
      MembroEscritorioProvider membroProvider,
      EscritorioRepository escritorioRepository,
      EscritorioSlugAnteriorRepository anteriorRepository,
      ContadorRepository contadorRepository,
      SlugsReservados reservados,
      EnderecoEscritorioProperties propriedades,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.membroProvider = membroProvider;
    this.escritorioRepository = escritorioRepository;
    this.anteriorRepository = anteriorRepository;
    this.contadorRepository = contadorRepository;
    this.reservados = reservados;
    this.propriedades = propriedades;
    this.clock = clock;
    this.transacao = new TransactionTemplate(transactionManager);
    if (propriedades.tlsAskChaveForaDaForma()) {
      // Nunca impede a subida (a API do www nao cai por um recurso opcional), mas grita no log.
      log.error("app.escritorio.tls-ask-chave fora da forma (32+ caracteres, so letras, digitos, "
          + "hifen e sublinhado; gere com openssl rand -hex 32): o ask do Caddy recusa todos os "
          + "certificados dos enderecos dos escritorios ate a chave ser corrigida.");
    }
  }

  /**
   * Define ou troca o endereço do escritório (só administrador). Reenviar o slug atual não muda
   * nada e não conta como troca.
   *
   * <p>Ordem das recusas: sem CNPJ (422), troca recente (409) e, por último, indisponível (409).
   * Assim quem ainda não pode trocar não descobre se um slug está livre.
   *
   * @param request slug e a ciência da exposição pública (já validados no controller)
   * @return o escritório e o estado do endereço antes e depois, para a auditoria
   * @throws EnderecoExigeCnpjException escritório sem CNPJ (422)
   * @throws EnderecoTrocaRecenteException gravação dentro do intervalo (409)
   * @throws SlugIndisponivelException reservado, em uso ou em carência de outro escritório (409)
   */
  @Auditado(acao = AcaoAuditada.ESCRITORIO_ENDERECO_ALTERADO, recurso = RecursoAuditado.ESCRITORIO)
  public EnderecoAlterado definir(EnderecoEscritorioRequest request) {
    Contador admin = membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR);
    String slug = FormatoSlug.normalizar(request.slug());
    OffsetDateTime agora = OffsetDateTime.now(clock);
    try {
      return transacao.execute(status -> {
        Escritorio escritorio = travado(admin.getEscritorioId());
        String anterior = escritorio.getSlug();
        String antes = anterior == null ? SEM_ENDERECO : COM_ENDERECO;
        if (slug.equals(anterior)) {
          return new EnderecoAlterado(resposta(escritorio, admin), antes, COM_ENDERECO);
        }
        if (escritorio.getCnpj() == null || escritorio.getCnpj().isBlank()) {
          throw new EnderecoExigeCnpjException(
              "Grave o CNPJ do escritório (PATCH /api/escritorio) antes de criar o endereço.");
        }
        exigirIntervalo(escritorio, agora);
        if (reservados.reservado(slug)
            || escritorioRepository.slugIndisponivel(slug, escritorio.getId(), agora)) {
          throw new SlugIndisponivelException();
        }
        // A linha que sobrar do slug (carencia vencida, ou do proprio escritorio, que o recupera)
        // sai antes: a chave da carencia e o slug.
        anteriorRepository.apagarPorSlug(slug);
        if (anterior != null) {
          reservar(anterior, escritorio.getId(), agora);
        }
        escritorio.setSlug(slug);
        escritorio.setSlugAlteradoEm(agora);
        escritorioRepository.saveAndFlush(escritorio);
        log.info("Endereco do escritorio gravado: escritorio={}, antes={}", escritorio.getId(),
            antes);
        return new EnderecoAlterado(resposta(escritorio, admin), antes, COM_ENDERECO);
      });
    } catch (DataIntegrityViolationException e) {
      // Corrida entre dois administradores pelo mesmo slug: o indice unico decide, e quem perde
      // recebe a mesma resposta de um slug em uso.
      throw new SlugIndisponivelException();
    }
  }

  /**
   * Remove o endereço do escritório (só administrador). Sempre permitido, mesmo dentro do
   * intervalo entre trocas: o administrador precisa poder tirar do ar um nome que não devia estar
   * lá. O slug vai para a carência e a remoção conta como alteração. Sem endereço, nada muda.
   *
   * @return o escritório e o estado do endereço antes e depois, para a auditoria
   */
  @Auditado(acao = AcaoAuditada.ESCRITORIO_ENDERECO_ALTERADO, recurso = RecursoAuditado.ESCRITORIO)
  public EnderecoAlterado remover() {
    Contador admin = membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR);
    OffsetDateTime agora = OffsetDateTime.now(clock);
    return transacao.execute(status -> {
      Escritorio escritorio = travado(admin.getEscritorioId());
      String anterior = escritorio.getSlug();
      if (anterior == null) {
        return new EnderecoAlterado(resposta(escritorio, admin), SEM_ENDERECO, SEM_ENDERECO);
      }
      reservar(anterior, escritorio.getId(), agora);
      escritorio.setSlug(null);
      escritorio.setSlugAlteradoEm(agora);
      escritorioRepository.saveAndFlush(escritorio);
      log.info("Endereco do escritorio removido: escritorio={}", escritorio.getId());
      return new EnderecoAlterado(resposta(escritorio, admin), COM_ENDERECO, SEM_ENDERECO);
    });
  }

  /**
   * Escritório público de um endereço (issue #464), para a tela de login com a marca dele. Só o
   * nome: nunca id, CNPJ, membros ou contato.
   *
   * @param slug rótulo do host, como o navegador o mandou (sem normalizar)
   * @return slug, nome e, se o slug pedido está em carência, o atual (para redirecionar)
   * @throws EnderecoNaoEncontradoException a mesma resposta para tudo que não é servido (404)
   */
  public EscritorioPublicoResponse publico(String slug) {
    return servido(slug, OffsetDateTime.now(clock))
        .orElseThrow(EnderecoNaoEncontradoException::new);
  }

  /**
   * O Caddy pode emitir certificado para este domínio? É o "ask" do On-Demand TLS (#464): só com
   * a chave certa e um endereço servido (ativo, ou em carência com redirecionamento).
   *
   * @param chave   segredo compartilhado com o Caddy
   * @param dominio domínio pedido no TLS, como o Caddy o mandou
   * @return {@code true} se pode emitir
   */
  public boolean certificadoPermitido(String chave, String dominio) {
    if (!chaveDoAskConfere(chave) || dominio == null) {
      return false;
    }
    String sufixo = "." + propriedades.dominioBase();
    if (!dominio.endsWith(sufixo) || dominio.length() == sufixo.length()) {
      return false;
    }
    String rotulo = dominio.substring(0, dominio.length() - sufixo.length());
    return servido(rotulo, OffsetDateTime.now(clock)).isPresent();
  }

  /**
   * A credencial é de um contador membro do escritório que tem este endereço ativo (com CNPJ)?
   * Regra única do login e do refresh pelo subdomínio (#464).
   *
   * @param credencial credencial que provou a senha, ou a do refresh
   * @param rotulo     rótulo do host
   * @return {@code true} só para contador membro daquele escritório
   */
  public boolean membroDoEndereco(CredencialUsuario credencial, String rotulo) {
    return credencial.getPapel() == PapelUsuario.CONTADOR
        && credencial.getContadorId() != null
        && servivel(rotulo)
        && escritorioRepository.membroDoEnderecoAtivo(rotulo, credencial.getContadorId());
  }

  /**
   * Escritório do contador montado para a resposta, com o endereço.
   *
   * @param escritorio escritório
   * @param membro     quem pergunta (a função dele vai na resposta)
   * @return a resposta
   */
  public EscritorioResponse resposta(Escritorio escritorio, Contador membro) {
    String slug = escritorio.getSlug();
    return EscritorioMapper.paraResponse(escritorio, membro.getFuncao(),
        contadorRepository.countByEscritorioId(escritorio.getId()),
        slug == null ? null : propriedades.host(slug), alteravelEm(escritorio));
  }

  /**
   * O escritório vai ser apagado porque o contador entrou noutra equipe (aceite do convite): o
   * slug ativo entra em carência e os anteriores seguem nela, todos sem escritório — bloqueados
   * para registro, mas nunca servidos (não há para onde redirecionar). Roda na transação de quem
   * chama, antes de o escritório sair.
   *
   * @param escritorioId escritório que vai ser apagado
   */
  void reservarAoApagar(UUID escritorioId) {
    // Sob o lock do escritorio, como o PUT do endereco: um slug gravado ao mesmo tempo nao sai
    // junto com o escritorio sem passar pela carencia.
    String slug = escritorioRepository.findByIdForUpdate(escritorioId)
        .map(Escritorio::getSlug).orElse(null);
    anteriorRepository.desvincularDoEscritorio(escritorioId);
    if (slug != null) {
      reservar(slug, null, OffsetDateTime.now(clock));
    }
  }

  /**
   * Exclusão de dados do titular (LGPD, #148): os slugs anteriores do escritório saem sem
   * carência; o ativo sai com o próprio escritório. Roda na transação de quem chama, antes de o
   * escritório sair.
   *
   * @param escritorioId escritório que vai ser apagado
   */
  void apagarSemCarencia(UUID escritorioId) {
    anteriorRepository.apagarDoEscritorio(escritorioId);
  }

  private Optional<EscritorioPublicoResponse> servido(String slug, OffsetDateTime agora) {
    if (!servivel(slug)) {
      return Optional.empty();
    }
    Optional<Escritorio> ativo = escritorioRepository.findAtivoPorSlug(slug);
    if (ativo.isPresent()) {
      return Optional.of(new EscritorioPublicoResponse(slug, ativo.get().getNome(), null));
    }
    return escritorioRepository.findDestinoDaCarencia(slug, agora)
        .map(destino -> new EscritorioPublicoResponse(slug, destino.getNome(), destino.getSlug()));
  }

  /**
   * Já na forma canônica (o host chega em minúsculas: maiúscula é recusada, não corrigida), com a
   * forma de slug e fora dos reservados.
   */
  private boolean servivel(String slug) {
    return slug != null && slug.equals(FormatoSlug.normalizar(slug)) && FormatoSlug.valido(slug)
        && !reservados.reservado(slug);
  }

  /** Comparação em tempo constante, pelos hashes (nem o tamanho vaza). Sem chave, nada passa. */
  private boolean chaveDoAskConfere(String chave) {
    if (!propriedades.tlsAskChaveUtilizavel() || chave == null) {
      return false;
    }
    return MessageDigest.isEqual(sha256(chave), sha256(propriedades.tlsAskChave().strip()));
  }

  private static byte[] sha256(String valor) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponivel", e);
    }
  }

  private Escritorio travado(UUID escritorioId) {
    return escritorioRepository.findByIdForUpdate(escritorioId)
        .orElseThrow(() -> new ResourceNotFoundException("Escritorio", escritorioId));
  }

  private void exigirIntervalo(Escritorio escritorio, OffsetDateTime agora) {
    OffsetDateTime alterado = escritorio.getSlugAlteradoEm();
    if (alterado == null) {
      return;
    }
    OffsetDateTime libera = alterado.plus(propriedades.endereco().intervaloTroca());
    if (libera.isAfter(agora)) {
      throw new EnderecoTrocaRecenteException(libera);
    }
  }

  private Instant alteravelEm(Escritorio escritorio) {
    OffsetDateTime alterado = escritorio.getSlugAlteradoEm();
    if (alterado == null) {
      return null;
    }
    Instant libera = alterado.plus(propriedades.endereco().intervaloTroca()).toInstant();
    return libera.isAfter(clock.instant()) ? libera : null;
  }

  /** Põe o slug em carência; uma linha que sobrar dele (vencida) dá lugar à nova. */
  private void reservar(String slug, UUID escritorioId, OffsetDateTime agora) {
    anteriorRepository.apagarPorSlug(slug);
    anteriorRepository.saveAndFlush(EscritorioSlugAnterior.builder()
        .slug(slug)
        .escritorioId(escritorioId)
        .liberadoEm(agora.plus(propriedades.endereco().carencia()))
        .build());
  }

  /**
   * Resultado da gravação do endereço: a resposta ao cliente e, para a auditoria, o estado antes e
   * depois — sem o texto do slug, que pode ser dado pessoal.
   *
   * @param escritorio resposta ao cliente
   * @param antes      {@code COM_ENDERECO} ou {@code SEM_ENDERECO}
   * @param depois     {@code COM_ENDERECO} ou {@code SEM_ENDERECO}
   */
  public record EnderecoAlterado(EscritorioResponse escritorio, String antes, String depois)
      implements ResultadoAuditavel {

    @Override
    public Object status() {
      return depois;
    }

    @Override
    public Object statusAnterior() {
      return antes;
    }

    @Override
    public UUID recursoAuditado() {
      return escritorio.id();
    }
  }
}
