package br.com.agrocontabil.service;

import br.com.agrocontabil.model.AssistenteInteracao;
import br.com.agrocontabil.model.DesfechoExplicacao;
import br.com.agrocontabil.repository.AssistenteInteracaoRepository;
import java.util.Objects;
import java.util.UUID;
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
   * @return id do registro, para completá-lo com a explicação (#268)
   */
  @Transactional
  public UUID registrar(AssistenteInteracao interacao) {
    return repository.save(interacao).getId();
  }

  /**
   * Completa o registro com o desfecho da explicação depois da resposta de dados (#268), em
   * transação curta e própria, depois da chamada ao modelo.
   *
   * @param interacaoId   registro da pergunta
   * @param desfecho      desfecho da explicação
   * @param latenciaMs    tempo da explicação
   * @param tokensEntrada tokens de entrada da chamada, ou nulo se ela não terminou
   * @param tokensSaida   tokens de saída da chamada, ou nulo se ela não terminou
   * @throws IllegalStateException se o registro não existe ou já tem o desfecho da explicação
   */
  @Transactional
  public void registrarExplicacao(UUID interacaoId, DesfechoExplicacao desfecho, long latenciaMs,
      Integer tokensEntrada, Integer tokensSaida) {
    int linhas = repository.registrarExplicacao(Objects.requireNonNull(interacaoId, "interacaoId"),
        Objects.requireNonNull(desfecho, "desfecho").name(), latenciaMs, tokensEntrada,
        tokensSaida);
    if (linhas != 1) {
      throw new IllegalStateException(
          "Registro da Ceres sem a pergunta ou com a explicacao ja registrada");
    }
  }
}
