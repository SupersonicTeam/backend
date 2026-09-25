-- Tabela do lock distribuido dos jobs (issue #87), para o H2 de teste.
-- O perfil de teste desabilita o Liquibase e a tabela nao e entidade JPA, entao o create-drop do
-- Hibernate nao a cria. Usado via @Sql pelos testes de lock.
-- Manter sincronizado com db/changelog/changes/0023-shedlock.xml.
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
