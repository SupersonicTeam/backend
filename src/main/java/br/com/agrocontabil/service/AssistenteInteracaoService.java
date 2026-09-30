package br.com.agrocontabil.service;

import br.com.agrocontabil.model.AssistenteInteracao;
import br.com.agrocontabil.repository.AssistenteInteracaoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grava a auditoria das perguntas à Ceres (issue #144) em transação curta e própria.
 *
 * <p>Chamado pelo adaptador da auditoria do módulo {@code assistente}, depois da geração do modelo:
 * nenhuma conexão do pool fica presa enquanto o modelo responde. Não conhece o módulo — recebe a
 * entidade pronta.
 */
@Service
public class AssistenteInteracaoService {

  private final AssistenteInteracaoRepository repository;

  public AssistenteInteracaoService(AssistenteInteracaoRepository repository) {
    this.repository = repository;
  }

  /**
   * Grava a interação.
   *
   * @param interacao registro já montado e sanitizado
   */
  @Transactional
  public void registrar(AssistenteInteracao interacao) {
    repository.save(interacao);
  }
}
