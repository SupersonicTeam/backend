package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.ClassificacaoTributariaAnexoItem;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório somente leitura dos itens de anexo da LC 214/2025 (issue #272). A Ceres (#283) os
 * lê pelo {@code TratamentoProdutoService} para achar o item pelo nome do produto ("adubo").
 */
public interface ClassificacaoTributariaAnexoItemRepository
    extends JpaRepository<ClassificacaoTributariaAnexoItem, Integer> {
}
