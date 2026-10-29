package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.ClassificacaoTributaria;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório somente leitura da tabela oficial de cClassTrib (issue #272). Consultado pelo
 * {@code ClassificacaoTributariaService}.
 */
public interface ClassificacaoTributariaRepository
    extends JpaRepository<ClassificacaoTributaria, String> {
}
