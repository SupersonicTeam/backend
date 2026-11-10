package br.com.agrocontabil.mapper;

import br.com.agrocontabil.dto.EscritorioResponse;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.FuncaoEscritorio;
import java.time.Instant;

/**
 * Converte o {@link Escritorio} no {@link EscritorioResponse} (issues #228 e #464).
 *
 * <p>Não é bean: método estático, mesmo padrão do {@link GuiaFiscalMapper}. O que depende do banco
 * ou da configuração (quantos membros, o host, quando a troca libera) vem calculado pelo service.
 */
public final class EscritorioMapper {

  private EscritorioMapper() {
  }

  /**
   * Monta a resposta do escritório.
   *
   * @param escritorio          escritório
   * @param minhaFuncao         função de quem pergunta
   * @param membros             quantos membros a equipe tem
   * @param enderecoHost        host do endereço próprio; nulo sem endereço
   * @param enderecoAlteravelEm quando o endereço pode ser gravado de novo; nulo = já pode
   * @return a resposta
   */
  public static EscritorioResponse paraResponse(Escritorio escritorio,
      FuncaoEscritorio minhaFuncao, long membros, String enderecoHost,
      Instant enderecoAlteravelEm) {
    return new EscritorioResponse(escritorio.getId(), escritorio.getNome(), escritorio.getCnpj(),
        minhaFuncao, membros, escritorio.getSlug(), enderecoHost, enderecoAlteravelEm);
  }
}
