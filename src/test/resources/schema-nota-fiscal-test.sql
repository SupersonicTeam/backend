-- Schema H2-compatible para NotaFiscalRepositoryTest.
-- Usado via @Sql porque o Hibernate create-drop falha no H2 para as entidades
-- existentes que possuem columnDefinition PostgreSQL-especifico (text[], jsonb).

CREATE TABLE IF NOT EXISTS produtores (
    id                  UUID          NOT NULL,
    nome_completo       VARCHAR(255)  NOT NULL,
    cpf_cnpj            VARCHAR(20),
    tipo_produtor       VARCHAR(20),
    regime_tributario   VARCHAR(20),
    porte_empresa       VARCHAR(20),
    culturas            VARCHAR(4096),
    faturamento_anual   NUMERIC(15,2),
    estado              VARCHAR(2),
    email               VARCHAR(255),
    telefone            VARCHAR(20),
    created_at          TIMESTAMP WITH TIME ZONE,
    updated_at          TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS nota_fiscal (
    id                          UUID           NOT NULL,
    chave_acesso                VARCHAR(44),
    numero                      BIGINT         NOT NULL,
    serie                       INTEGER        NOT NULL DEFAULT 1,
    modelo                      VARCHAR(2)     NOT NULL DEFAULT '55',
    natureza_operacao           VARCHAR(255)   NOT NULL,
    data_emissao                TIMESTAMP WITH TIME ZONE NOT NULL,
    data_saida                  TIMESTAMP WITH TIME ZONE,
    produtor_id                 UUID           NOT NULL,
    destinatario_nome           VARCHAR(255)   NOT NULL,
    destinatario_cpf_cnpj       VARCHAR(14)    NOT NULL,
    destinatario_ie             VARCHAR(20),
    dest_logradouro             VARCHAR(255),
    dest_numero                 VARCHAR(20),
    dest_municipio              VARCHAR(255),
    dest_codigo_municipio_ibge  VARCHAR(7),
    dest_uf                     VARCHAR(2),
    dest_cep                    VARCHAR(8),
    valor_total_produtos        NUMERIC(15,2)  NOT NULL,
    valor_total_nota            NUMERIC(15,2)  NOT NULL,
    -- IBS/CBS totais — Sprint 2 Sessão 4 (EC 132/2023, LC 214/2025)
    valor_total_ibs             NUMERIC(15,2),
    valor_total_cbs             NUMERIC(15,2),
    status                      VARCHAR(20)    NOT NULL DEFAULT 'RASCUNHO',
    -- XML gerado — Sprint 2 Sessão 6
    xml_gerado                  TEXT,
    versao_layout_nfe           VARCHAR(10),
    data_geracao_xml            TIMESTAMP WITH TIME ZONE,
    -- Retorno da SEFAZ — Sprint 3 issue #15
    protocolo_autorizacao       VARCHAR(20),
    data_autorizacao            TIMESTAMP WITH TIME ZONE,
    sefaz_cstat                 VARCHAR(10),
    sefaz_motivo                VARCHAR(255),
    sefaz_ambiente              VARCHAR(20),
    created_at                  TIMESTAMP WITH TIME ZONE,
    updated_at                  TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS nota_fiscal_item (
    id              UUID           NOT NULL,
    nota_fiscal_id  UUID           NOT NULL,
    numero_item     INTEGER        NOT NULL,
    codigo_produto  VARCHAR(60),
    descricao       VARCHAR(255)   NOT NULL,
    ncm             VARCHAR(8)     NOT NULL,
    cfop            VARCHAR(4)     NOT NULL,
    unidade         VARCHAR(6)     NOT NULL,
    quantidade              NUMERIC(15,4)  NOT NULL,
    valor_unitario          NUMERIC(15,4)  NOT NULL,
    valor_total             NUMERIC(15,2)  NOT NULL,
    -- IBS/CBS por item — Sprint 2 Sessão 4 (EC 132/2023, LC 214/2025)
    cst_ibs_cbs             VARCHAR(3),
    c_class_trib            VARCHAR(6),
    base_calculo_ibs_cbs    NUMERIC(15,2),
    aliquota_ibs            NUMERIC(7,4),
    valor_ibs               NUMERIC(15,2),
    aliquota_cbs            NUMERIC(7,4),
    valor_cbs               NUMERIC(15,2),
    percentual_reducao      NUMERIC(5,2)   DEFAULT 0,
    created_at              TIMESTAMP WITH TIME ZONE,
    updated_at              TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
