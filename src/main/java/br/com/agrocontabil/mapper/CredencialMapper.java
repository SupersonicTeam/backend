package br.com.agrocontabil.mapper;

import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.model.CredencialUsuario;
import org.springframework.stereotype.Component;

/** Credencial → DTO (issue #205). Nunca expõe hash nem documento. */
@Component
public class CredencialMapper {

  /**
   * Resposta do cadastro.
   *
   * @param credencial credencial recém-criada
   * @return id da identidade, papel e e-mail
   */
  public CadastroResponse toCadastroResponse(CredencialUsuario credencial) {
    return new CadastroResponse(
        credencial.usuarioId(), credencial.getPapel(), credencial.getEmail());
  }
}
