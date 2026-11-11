package br.com.agrocontabil.mapper;

import br.com.agrocontabil.dto.EnderecoPostalRequest;
import br.com.agrocontabil.dto.EnderecoPostalResponse;
import br.com.agrocontabil.dto.EscritorioResponse;
import br.com.agrocontabil.model.EnderecoPostal;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.FuncaoEscritorio;
import java.time.Instant;

/**
 * Converte o {@link Escritorio} no {@link EscritorioResponse} (issues #228, #464 e #519) e o
 * endereço postal do request na entidade.
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
        minhaFuncao, membros, escritorio.getSlug(), enderecoHost, enderecoAlteravelEm,
        escritorio.getTipo(), escritorio.getTelefone(), paraResponse(escritorio.getEndereco()));
  }

  /**
   * Endereço postal do request na entidade, sem espaços nas pontas; complemento vazio = sem
   * complemento.
   *
   * @param request endereço validado
   * @return o endereço, ou nulo sem ele
   */
  public static EnderecoPostal paraEndereco(EnderecoPostalRequest request) {
    if (request == null) {
      return null;
    }
    String complemento = request.complemento();
    return EnderecoPostal.builder()
        .cep(request.cep())
        .logradouro(request.logradouro().strip())
        .numero(request.numero().strip())
        .complemento(complemento == null || complemento.isBlank() ? null : complemento.strip())
        .bairro(request.bairro().strip())
        .codigoMunicipioIbge(request.codigoMunicipioIbge())
        .municipio(request.municipio().strip())
        .uf(request.uf())
        .build();
  }

  private static EnderecoPostalResponse paraResponse(EnderecoPostal endereco) {
    if (endereco == null) {
      return null;
    }
    return new EnderecoPostalResponse(endereco.getCep(), endereco.getLogradouro(),
        endereco.getNumero(), endereco.getComplemento(), endereco.getBairro(),
        endereco.getCodigoMunicipioIbge(), endereco.getMunicipio(), endereco.getUf());
  }
}
