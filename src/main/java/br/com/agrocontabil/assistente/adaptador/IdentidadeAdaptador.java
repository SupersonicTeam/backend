package br.com.agrocontabil.assistente.adaptador;

import br.com.agrocontabil.assistente.porta.IdentidadePorta;
import br.com.agrocontabil.service.AuthenticatedProdutorProvider;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implementa a {@link IdentidadePorta} sobre o {@link AuthenticatedProdutorProvider} (issue #141):
 * 401 sem identidade, 403 para contador — as mesmas respostas dos demais endpoints do produtor.
 */
@Component
public class IdentidadeAdaptador implements IdentidadePorta {

  private final AuthenticatedProdutorProvider produtorProvider;

  public IdentidadeAdaptador(AuthenticatedProdutorProvider produtorProvider) {
    this.produtorProvider = produtorProvider;
  }

  @Override
  public UUID produtorAutenticado() {
    return produtorProvider.get().getId();
  }
}
