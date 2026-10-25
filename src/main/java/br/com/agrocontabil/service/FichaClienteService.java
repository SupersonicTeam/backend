package br.com.agrocontabil.service;

import br.com.agrocontabil.common.NormalizacaoBusca;
import br.com.agrocontabil.dto.EtiquetaResponse;
import br.com.agrocontabil.dto.FichaClienteResponse;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.EtiquetaCarteira;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.VinculoCarteira;
import br.com.agrocontabil.repository.EtiquetaCarteiraRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.security.MembroEscritorioProvider;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
import br.com.agrocontabil.security.UsuarioAutenticado;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ficha do cliente e catálogo de etiquetas do escritório (issue #177).
 *
 * <p>A ficha (observações internas e etiquetas) mora no vínculo e só é alcançável com o vínculo
 * {@code ATIVO}, pelo {@link ProdutorEmContextoResolver}: suspenso, ela fica guardada mas fora do
 * alcance, como o resto dos dados do produtor. Observações nunca vão ao produtor nem ao log.
 *
 * <p>Sem auditoria: é anotação interna do escritório, não operação fiscal nem de fluxo.
 */
@Service
public class FichaClienteService {

  private final MembroEscritorioProvider membroProvider;
  private final ProdutorEmContextoResolver produtorEmContexto;
  private final VinculoCarteiraRepository vinculoRepository;
  private final EtiquetaCarteiraRepository etiquetaRepository;

  public FichaClienteService(
      MembroEscritorioProvider membroProvider,
      ProdutorEmContextoResolver produtorEmContexto,
      VinculoCarteiraRepository vinculoRepository,
      EtiquetaCarteiraRepository etiquetaRepository) {
    this.membroProvider = membroProvider;
    this.produtorEmContexto = produtorEmContexto;
    this.vinculoRepository = vinculoRepository;
    this.etiquetaRepository = etiquetaRepository;
  }

  /**
   * Ficha do cliente.
   *
   * @param produtorId cliente
   * @return a ficha
   * @throws PapelNaoAutorizadoException se quem pede não for contador (403)
   * @throws ResourceNotFoundException fora da carteira ativa (404)
   */
  @Transactional(readOnly = true)
  public FichaClienteResponse ficha(UUID produtorId) {
    return resposta(vinculoAtivo(produtorId));
  }

  /**
   * Troca as observações internas do cliente.
   *
   * @param produtorId  cliente
   * @param observacoes texto novo; vazio ou nulo apaga
   * @return a ficha
   */
  @Transactional
  public FichaClienteResponse atualizarObservacoes(UUID produtorId, String observacoes) {
    VinculoCarteira vinculo = vinculoAtivo(produtorId);
    vinculo.setObservacoes(observacoes == null || observacoes.isBlank() ? null
        : observacoes.strip());
    return resposta(vinculo);
  }

  /**
   * Substitui as etiquetas do cliente; nome novo entra no catálogo do escritório.
   *
   * @param produtorId cliente
   * @param nomes      nomes (até 10; repetidos sem acento nem caixa contam uma vez)
   * @return a ficha
   */
  @Transactional
  public FichaClienteResponse definirEtiquetas(UUID produtorId, List<String> nomes) {
    VinculoCarteira vinculo = vinculoAtivo(produtorId);
    UUID escritorioId = vinculo.getEscritorioId();

    Map<String, String> pedidas = new LinkedHashMap<>();
    for (String nome : nomes) {
      String limpo = nome.strip().replaceAll("\\s+", " ");
      pedidas.putIfAbsent(NormalizacaoBusca.normalizar(limpo), limpo);
    }
    Map<String, EtiquetaCarteira> existentes = etiquetaRepository
        .findByEscritorioIdAndNomeNormalizadoIn(escritorioId, pedidas.keySet()).stream()
        .collect(Collectors.toMap(EtiquetaCarteira::getNomeNormalizado, Function.identity()));

    Set<EtiquetaCarteira> etiquetas = new LinkedHashSet<>();
    pedidas.forEach((normalizado, nome) -> etiquetas.add(existentes.computeIfAbsent(normalizado,
        n -> etiquetaRepository.save(EtiquetaCarteira.builder()
            .escritorioId(escritorioId)
            .nome(nome)
            .nomeNormalizado(n)
            .build()))));
    vinculo.getEtiquetas().clear();
    vinculo.getEtiquetas().addAll(etiquetas);
    return resposta(vinculo);
  }

  /**
   * Catálogo de etiquetas do escritório do contador autenticado (qualquer membro).
   *
   * @return etiquetas em ordem alfabética
   */
  @Transactional(readOnly = true)
  public List<EtiquetaResponse> catalogo() {
    return etiquetaRepository
        .findByEscritorioIdOrderByNomeNormalizadoAsc(membroProvider.membro().getEscritorioId())
        .stream()
        .map(e -> new EtiquetaResponse(e.getId(), e.getNome()))
        .toList();
  }

  /**
   * Apaga a etiqueta do catálogo e de todos os clientes (só administrador: vale para a equipe).
   *
   * @param etiquetaId etiqueta
   * @throws ResourceNotFoundException etiqueta de outro escritório ou inexistente (404)
   */
  @Transactional
  public void excluirEtiqueta(UUID etiquetaId) {
    EtiquetaCarteira etiqueta = etiquetaRepository
        .findByIdAndEscritorioId(etiquetaId,
            membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR).getEscritorioId())
        .orElseThrow(() -> new ResourceNotFoundException("Etiqueta", etiquetaId));
    etiquetaRepository.desaplicar(etiqueta.getId());
    etiquetaRepository.delete(etiqueta);
  }

  private VinculoCarteira vinculoAtivo(UUID produtorId) {
    UsuarioAutenticado contador = produtorEmContexto.exigirContadorComAcesso(produtorId);
    return vinculoRepository
        .findFicha(contador.contador().getEscritorioId(), produtorId, SituacaoVinculo.ATIVO)
        .orElseThrow(() -> new ResourceNotFoundException("Produtor", produtorId));
  }

  private static FichaClienteResponse resposta(VinculoCarteira vinculo) {
    Produtor produtor = vinculo.getProdutor();
    return new FichaClienteResponse(
        produtor.getId(),
        produtor.getNomeCompleto(),
        produtor.getCpfCnpj(),
        produtor.getTipoProdutor(),
        produtor.getRegimeTributario(),
        produtor.getEstado(),
        produtor.getInscricaoEstadual(),
        vinculo.getSituacao(),
        vinculo.getInicio(),
        vinculo.getObservacoes(),
        vinculo.getEtiquetas().stream().map(EtiquetaCarteira::getNome)
            .sorted(Comparator.comparing(NormalizacaoBusca::normalizar)).toList());
  }
}
