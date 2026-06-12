-- Schema H2-compativel para NcmCfopReferenciaRepositoryTest.
-- O profile test desabilita Liquibase; este script cria as tabelas de referencia.

CREATE TABLE IF NOT EXISTS ncm_agropecuario (
    codigo     VARCHAR(8)    NOT NULL,
    descricao  VARCHAR(255)  NOT NULL,
    PRIMARY KEY (codigo)
);

CREATE TABLE IF NOT EXISTS cfop_produtor_rural (
    codigo         VARCHAR(4)    NOT NULL,
    descricao      VARCHAR(255)  NOT NULL,
    tipo_operacao  VARCHAR(7)    NOT NULL,
    ambito         VARCHAR(15)   NOT NULL,
    PRIMARY KEY (codigo)
);
