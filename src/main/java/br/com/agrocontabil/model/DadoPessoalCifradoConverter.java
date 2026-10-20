package br.com.agrocontabil.model;

import br.com.agrocontabil.config.CriptografiaProperties;
import br.com.agrocontabil.security.CifraDeterministica;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Cifra e decifra, na camada JPA, as colunas de dado pessoal (issue #147, RNF004): o service e o
 * resto da aplicação veem o valor em claro; o banco, só {@code v1:...}.
 *
 * <p>Aplicado com {@code @Convert} campo a campo (nunca {@code autoApply}). O Hibernate cifra com
 * ele também os parâmetros das consultas sobre esses campos — como a cifra é determinística, o
 * {@code findByCpfCnpj} e a unicidade seguem funcionando.
 *
 * <p>Criado pelo Hibernate através do contêiner de beans do Spring, com o {@link Environment}
 * injetado: a cifra é montada da configuração aqui mesmo, e não de outro bean, para valer também
 * nos testes de fatia JPA ({@code @DataJpaTest}), que não carregam componentes. Chave inválida
 * impede o {@code EntityManagerFactory} de subir.
 */
@Converter
public class DadoPessoalCifradoConverter implements AttributeConverter<String, String> {

  private final CifraDeterministica cifra;

  public DadoPessoalCifradoConverter(Environment ambiente) {
    CriptografiaProperties propriedades = Binder.get(ambiente)
        .bind("app.seguranca.cripto", CriptografiaProperties.class)
        .orElseGet(() -> new CriptografiaProperties(null, false, true));
    this.cifra = CifraDeterministica.de(propriedades);
  }

  @Override
  public String convertToDatabaseColumn(String valor) {
    return cifra.cifrar(valor);
  }

  @Override
  public String convertToEntityAttribute(String valor) {
    return cifra.decifrar(valor);
  }
}
