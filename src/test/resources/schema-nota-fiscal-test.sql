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
    -- Endereco completo + IE do emitente — Onda 2 issue #88 (migration 0021).
    -- Todas nullable: obrigatoriedade e regra de emissao (FiscalRulesValidator).
    endereco_logradouro            VARCHAR(60),
    endereco_numero                VARCHAR(60),
    endereco_complemento           VARCHAR(60),
    endereco_bairro                VARCHAR(60),
    endereco_codigo_municipio_ibge VARCHAR(7),
    endereco_municipio             VARCHAR(60),
    endereco_cep                   VARCHAR(8),
    inscricao_estadual             VARCHAR(14),
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
    -- Discriminador NFE/NFA — Sprint 2 issue #18 (migration 0008)
    tipo_documento              VARCHAR(10)    NOT NULL DEFAULT 'NFE',
    -- Importacao de XML — Sprint 2 issue #19 (migration 0009).
    -- origem: PROPRIA (emitida pelo produtor) | IMPORTADA (nota de entrada de terceiro).
    -- emitente_*: preenchidos apenas em notas importadas.
    -- Obs.: o indice unico PARCIAL (WHERE origem='PROPRIA') do PostgreSQL nao e portavel
    -- para o H2 usado nos testes; aqui garantimos as colunas. A dedup de importadas por
    -- chave_acesso e coberta pelo unique de chave_acesso ja existente na entidade/migration.
    origem                      VARCHAR(10)    NOT NULL DEFAULT 'PROPRIA',
    emitente_nome               VARCHAR(255),
    emitente_cpf_cnpj           VARCHAR(14),
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
    -- Cancelamento da NF-e — Sprint 3 issue #21 (migration 0010)
    data_cancelamento           TIMESTAMP WITH TIME ZONE,
    motivo_cancelamento         VARCHAR(255),
    protocolo_cancelamento      VARCHAR(20),
    -- Crédito tributário de entrada — Sprint 4 issue Agrocontabil#30 (migration 0011)
    valor_total_credito_ibs     NUMERIC(15,2),
    valor_total_credito_cbs     NUMERIC(15,2),
    -- Lock otimista — Onda 3 issue #95 (migration 0022)
    version                     BIGINT         NOT NULL DEFAULT 0,
    created_at                  TIMESTAMP WITH TIME ZONE,
    updated_at                  TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id),
    -- chave_acesso e globalmente unica (changeset 0002, nunca removida). Espelha producao e
    -- cobre a dedup de notas (inclusive importadas) por chave.
    CONSTRAINT uk_nota_fiscal_chave_acesso UNIQUE (chave_acesso)
);
-- Nota: a unicidade de (produtor, numero, serie, modelo) restrita a origem='PROPRIA' e um
-- INDICE UNICO PARCIAL no PostgreSQL (changeset 0009; a unique total de 0002 foi REMOVIDA la
-- para permitir notas IMPORTADAS de terceiros com numero/serie colidentes). O H2 NAO suporta
-- indice parcial (WHERE em CREATE INDEX), entao essa regra fica so no Liquibase e nao e
-- replicada aqui — decisao consciente do projeto.

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
    -- Crédito tributário de entrada — Sprint 4 issue Agrocontabil#30 (migration 0011)
    valor_credito_ibs       NUMERIC(15,2),
    valor_credito_cbs       NUMERIC(15,2),
    -- Natureza tributária por operação (DEBITO/CREDITO) — Sprint 4 issue #30 (migration 0012)
    natureza_operacao       VARCHAR(10),
    -- Parcelas estadual/municipal reais de IBS — Sprint 4 issue #31 (migration 0013)
    aliquota_ibs_uf         NUMERIC(7,4),
    aliquota_ibs_mun        NUMERIC(7,4),
    valor_ibs_uf            NUMERIC(15,2),
    valor_ibs_mun           NUMERIC(15,2),
    created_at              TIMESTAMP WITH TIME ZONE,
    updated_at              TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id),
    -- Espelha o changeset 0002-nota-fiscal-item-unique-numero-item (migration 0002):
    -- um numero_item nao pode se repetir dentro da mesma nota fiscal.
    CONSTRAINT uk_nota_fiscal_item_nota_numero UNIQUE (nota_fiscal_id, numero_item)
);

-- Inconsistencias de validacao da NF-e — Sprint 3 issue #22 (migration 0006)
CREATE TABLE IF NOT EXISTS nota_fiscal_validacao (
    id              UUID           NOT NULL,
    nota_fiscal_id  UUID           NOT NULL,
    lote_validacao  UUID           NOT NULL,
    code            VARCHAR(60)    NOT NULL,
    field           VARCHAR(120),
    message         VARCHAR(500)   NOT NULL,
    suggestion      VARCHAR(500),
    rejected_value  VARCHAR(500),
    expected_value  VARCHAR(500),
    validado_em     TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id)
);

-- ============================================================================
-- Apuracao mensal de IBS/CBS — Sprint 4 issue Agrocontabil#41 (migration 0014).
-- O perfil de teste desabilita o Liquibase; replicamos a tabela para que os testes de
-- integracao que usam este script via @Sql tenham o schema da apuracao.
-- Manter sincronizado com 0014-apuracao-mensal.xml.
CREATE TABLE IF NOT EXISTS apuracao_mensal (
    id               UUID           NOT NULL,
    produtor_id      UUID           NOT NULL,
    periodo          VARCHAR(7)     NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    debito_ibs       NUMERIC(15,2)  NOT NULL,
    debito_cbs       NUMERIC(15,2)  NOT NULL,
    credito_ibs      NUMERIC(15,2)  NOT NULL,
    credito_cbs      NUMERIC(15,2)  NOT NULL,
    saldo_ibs        NUMERIC(15,2)  NOT NULL,
    saldo_cbs        NUMERIC(15,2)  NOT NULL,
    total_notas      BIGINT         NOT NULL DEFAULT 0,
    data_fechamento  TIMESTAMP WITH TIME ZONE,
    -- Auditoria de reabertura — Sprint 4 issue Agrocontabil#42 (migration 0015).
    reaberto_por     VARCHAR(64),
    data_reabertura  TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE,
    updated_at       TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id),
    CONSTRAINT uk_apuracao_produtor_periodo UNIQUE (produtor_id, periodo)
);

-- ============================================================================
-- Guias fiscais do Contador — issue #74 (migration 0018).
-- Manter sincronizado com 0018-guias-fiscais.xml.
-- ============================================================================
CREATE TABLE IF NOT EXISTS guias_fiscais (
    id               UUID           NOT NULL,
    produtor_id      UUID           NOT NULL,
    tipo_guia        VARCHAR(20)    NOT NULL,
    data_vencimento  DATE           NOT NULL,
    valor            NUMERIC(15,2)  NOT NULL,
    observacoes      VARCHAR(255),
    status           VARCHAR(20)    NOT NULL,
    data_pagamento   TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE,
    updated_at       TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_guia_fiscal_produtor_id ON guias_fiscais (produtor_id);

-- Contador e vinculo N:N com produtor — Onda 7 issue #93 (migration 0024).
-- Manter sincronizado com 0024-contador-vinculo.xml.
CREATE TABLE IF NOT EXISTS contadores (
    id             UUID          NOT NULL,
    nome_completo  VARCHAR(255)  NOT NULL,
    cpf_cnpj       VARCHAR(20),
    crc            VARCHAR(20),
    email          VARCHAR(255),
    telefone       VARCHAR(20),
    created_at     TIMESTAMP WITH TIME ZONE,
    updated_at     TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id),
    CONSTRAINT uk_contador_cpf_cnpj UNIQUE (cpf_cnpj)
);

CREATE TABLE IF NOT EXISTS contador_produtor (
    contador_id  UUID  NOT NULL,
    produtor_id  UUID  NOT NULL,
    PRIMARY KEY (contador_id, produtor_id)
);

CREATE INDEX IF NOT EXISTS idx_contador_produtor_produtor_id
    ON contador_produtor (produtor_id);

-- Auditoria das interacoes com a Ceres — Sprint 6 issue #144 (migration 0025).
-- Sem o texto da pergunta nem da resposta. Manter sincronizado com 0025-assistente-interacao.xml.
CREATE TABLE IF NOT EXISTS assistente_interacao (
    id                UUID                      NOT NULL,
    produtor_id       UUID                      NOT NULL,
    rota              VARCHAR(20)               NOT NULL,
    desfecho          VARCHAR(40)               NOT NULL,
    funcoes           TEXT,
    chamadas_funcao   INTEGER                   NOT NULL,
    trechos_citados   TEXT,
    modelo            VARCHAR(120),
    latencia_ms       BIGINT                    NOT NULL,
    tokens_entrada    INTEGER,
    tokens_saida      INTEGER,
    tamanho_pergunta  INTEGER                   NOT NULL,
    criado_em         TIMESTAMP WITH TIME ZONE  NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_assistente_interacao_produtor_criado
    ON assistente_interacao (produtor_id, criado_em);

-- Marca de tokens estimados — Sprint 7 issue #166 (migration 0029). Idempotente: nos testes com
-- create-drop o Hibernate ja cria a coluna a partir da entidade.
ALTER TABLE assistente_interacao ADD COLUMN IF NOT EXISTS
    tokens_estimados BOOLEAN DEFAULT FALSE NOT NULL;

-- Desfecho por parte do MISTO — Sprint 7 (migration 0030). Idempotente, como o bloco acima.
ALTER TABLE assistente_interacao ADD COLUMN IF NOT EXISTS desfecho_dados VARCHAR(40);
ALTER TABLE assistente_interacao ADD COLUMN IF NOT EXISTS desfecho_legislacao VARCHAR(40);
ALTER TABLE assistente_interacao ADD COLUMN IF NOT EXISTS pergunta_lei_reescrita BOOLEAN;

-- Cota semanal de creditos da Ceres — Sprint 7 issue #166 (migration 0029). Manter sincronizado
-- com 0029-assistente-uso-semanal.xml.
CREATE TABLE IF NOT EXISTS assistente_uso_semanal (
    titular_tipo         VARCHAR(20)               NOT NULL,
    titular_id           UUID                      NOT NULL,
    semana_inicio        DATE                      NOT NULL,
    creditos_reservados  BIGINT                    DEFAULT 0 NOT NULL,
    creditos_consumidos  BIGINT                    DEFAULT 0 NOT NULL,
    tokens_entrada       BIGINT                    DEFAULT 0 NOT NULL,
    tokens_saida         BIGINT                    DEFAULT 0 NOT NULL,
    perguntas            INTEGER                   DEFAULT 0 NOT NULL,
    atualizado_em        TIMESTAMP WITH TIME ZONE  NOT NULL,
    CONSTRAINT pk_assistente_uso_semanal PRIMARY KEY (titular_tipo, titular_id, semana_inicio),
    CONSTRAINT ck_assistente_uso_nao_negativo CHECK (creditos_reservados >= 0
        AND creditos_consumidos >= 0 AND tokens_entrada >= 0 AND tokens_saida >= 0
        AND perguntas >= 0)
);

-- Alertas tributarios — Sprint 7 issue #140 (migration 0028). Manter sincronizado com
-- 0028-alerta-tributario.xml (FK com CASCADE mais abaixo, junto das demais).
CREATE TABLE IF NOT EXISTS alerta_tributario (
    id             UUID                      NOT NULL,
    produtor_id    UUID                      NOT NULL,
    tipo           VARCHAR(30)               NOT NULL,
    referencia_id  UUID                      NOT NULL,
    data_limite    DATE                      NOT NULL,
    descricao      VARCHAR(160)              NOT NULL,
    status         VARCHAR(20)               NOT NULL,
    criado_em      TIMESTAMP WITH TIME ZONE  NOT NULL,
    resolvido_em   TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id),
    CONSTRAINT uk_alerta_tributario_referencia UNIQUE (tipo, referencia_id, data_limite)
);

CREATE INDEX IF NOT EXISTS idx_alerta_tributario_produtor
    ON alerta_tributario (produtor_id, status, data_limite);

-- Indice composto (status, periodo) — Sprint 4 issue #57 (migration 0016).
-- Cobre a varredura do refechamento de periodos reabertos (status = ? AND periodo range).
CREATE INDEX IF NOT EXISTS idx_apuracao_status_periodo ON apuracao_mensal (status, periodo);

-- Indices compostos de escalabilidade — Onda 1 issue #102 (migration 0019).
-- A ORDEM das colunas segue o predicado real (igualdades antes do range/ordenacao) e e
-- verificada por SchemaTestIndicesTest. Manter sincronizado com
-- 0019-indices-compostos-escalabilidade.xml.
CREATE INDEX IF NOT EXISTS idx_nota_fiscal_produtor_status_emissao
    ON nota_fiscal (produtor_id, status, data_emissao);
CREATE INDEX IF NOT EXISTS idx_guia_fiscal_status_vencimento
    ON guias_fiscais (status, data_vencimento);
CREATE INDEX IF NOT EXISTS idx_guia_fiscal_produtor_vencimento
    ON guias_fiscais (produtor_id, data_vencimento);

-- ============================================================================
-- Foreign keys — issue #90. Replicam as FKs do Liquibase com as MESMAS acoes ON DELETE,
-- para que RESTRICT/CASCADE/filho orfao se comportem nos testes como em producao.
-- Manter sincronizado com 0002-nota-fiscal.xml, 0014-apuracao-mensal.xml e
-- 0018-guias-fiscais.xml.
--
-- Por que ALTER TABLE e nao dentro dos CREATE TABLE acima: a maioria dos testes
-- @SpringBootTest roda com ddl-auto=create-drop — o Hibernate cria as tabelas (sem FK, pois as
-- entidades usam @ForeignKey(NO_CONSTRAINT)) ANTES deste @Sql, e os CREATE TABLE IF NOT EXISTS
-- viram no-op. O ALTER TABLE ... ADD CONSTRAINT IF NOT EXISTS e idempotente e vale nos dois
-- caminhos (@DataJpaTest com ddl-auto=none e @SpringBootTest com create-drop).
--
-- nota_fiscal_validacao NAO recebe FK: o changeset 0006 nao define nenhuma em producao.
-- ============================================================================
ALTER TABLE nota_fiscal ADD CONSTRAINT IF NOT EXISTS fk_nota_fiscal_produtor
    FOREIGN KEY (produtor_id) REFERENCES produtores (id) ON DELETE RESTRICT;

ALTER TABLE nota_fiscal_item ADD CONSTRAINT IF NOT EXISTS fk_nota_fiscal_item_nota
    FOREIGN KEY (nota_fiscal_id) REFERENCES nota_fiscal (id) ON DELETE CASCADE;

ALTER TABLE apuracao_mensal ADD CONSTRAINT IF NOT EXISTS fk_apuracao_produtor
    FOREIGN KEY (produtor_id) REFERENCES produtores (id);

ALTER TABLE guias_fiscais ADD CONSTRAINT IF NOT EXISTS fk_guia_fiscal_produtor
    FOREIGN KEY (produtor_id) REFERENCES produtores (id);

-- Vinculo contador_produtor: CASCADE nos dois lados (migration 0024). Sem isso, apagar um
-- produtor semeado num teste falharia por integridade referencial.
ALTER TABLE contador_produtor ADD CONSTRAINT IF NOT EXISTS fk_contador_produtor_contador
    FOREIGN KEY (contador_id) REFERENCES contadores (id) ON DELETE CASCADE;

ALTER TABLE contador_produtor ADD CONSTRAINT IF NOT EXISTS fk_contador_produtor_produtor
    FOREIGN KEY (produtor_id) REFERENCES produtores (id) ON DELETE CASCADE;

-- Auditoria da Ceres: RESTRICT, como nota_fiscal (migration 0025). O TestDatabaseCleaner apaga as
-- interacoes antes dos produtores.
ALTER TABLE assistente_interacao ADD CONSTRAINT IF NOT EXISTS fk_assistente_interacao_produtor
    FOREIGN KEY (produtor_id) REFERENCES produtores (id) ON DELETE RESTRICT;

-- Alertas tributarios: CASCADE, como na migration 0028 (alerta e derivado).
ALTER TABLE alerta_tributario ADD CONSTRAINT IF NOT EXISTS fk_alerta_tributario_produtor
    FOREIGN KEY (produtor_id) REFERENCES produtores (id) ON DELETE CASCADE;

-- ============================================================================
-- Referencia de NCM/CFOP — Sprint 2 issue #20 (migration 0007).
-- O perfil de teste desabilita o Liquibase (liquibase.enabled=false), entao o
-- seed via <insert> do changeset NAO roda nos testes. Replicamos as tabelas e o
-- seed curado aqui para que os validadores @ValidNcm/@ValidCfop tenham dados nos
-- testes de integracao que usam este script via @Sql.
-- Manter sincronizado com 0007-ncm-cfop-referencia.xml.
-- ============================================================================
CREATE TABLE IF NOT EXISTS ncm_agropecuario (
    codigo                     VARCHAR(8)    NOT NULL,
    descricao                  VARCHAR(255)  NOT NULL,
    -- Redução padrão de base IBS/CBS por NCM — Sprint 4 issue #30 (migration 0012)
    percentual_reducao_padrao  DECIMAL(5,2)  NOT NULL DEFAULT 0,
    PRIMARY KEY (codigo)
);

CREATE TABLE IF NOT EXISTS cfop_referencia (
    codigo                    VARCHAR(4)    NOT NULL,
    descricao                 VARCHAR(255)  NOT NULL,
    tipo_operacao             VARCHAR(20)   NOT NULL,
    ambito                    VARCHAR(20)   NOT NULL,
    aplicavel_produtor_rural  BOOLEAN       NOT NULL DEFAULT TRUE,
    PRIMARY KEY (codigo)
);

-- percentual_reducao_padrao replicado do seed do changeset 0012 (ano-teste, incerteza documentada).
MERGE INTO ncm_agropecuario (codigo, descricao, percentual_reducao_padrao) KEY (codigo) VALUES
    ('12010090', 'Soja, mesmo triturada (exceto para semeadura)', 0),
    ('12010010', 'Soja para semeadura', 0),
    ('10059010', 'Milho em grao (exceto para semeadura)', 60),
    ('10051000', 'Milho para semeadura', 0),
    ('10011900', 'Trigo duro (exceto para semeadura)', 0),
    ('10063021', 'Arroz semibranqueado ou branqueado, polido', 60),
    ('09011110', 'Cafe nao torrado, nao descafeinado, em grao', 0),
    ('17011400', 'Outros acucares de cana, em bruto', 0),
    ('52010020', 'Algodao em bruto, nao cardado nem penteado', 0),
    ('01022110', 'Bovinos reprodutores de raca pura, prenhes ou com cria', 0),
    ('01039000', 'Suinos vivos (exceto reprodutores de raca pura)', 0),
    ('04070090', 'Outros ovos de aves, com casca, frescos', 60),
    ('04012010', 'Leite, teor de gordura entre 1% e 6%, em embalagem ate 1 litro', 60),
    ('08043000', 'Abacaxis (ananases) frescos ou secos', 60),
    ('07020000', 'Tomates frescos ou refrigerados', 60);

-- ============================================================================
-- Aliquota de IBS por UF — Sprint 4 issue #31 (migration 0013) + vigencia
-- temporal — Onda 2 issue #85 (migration 0019).
-- Parcela estadual + municipal por UF, com data_inicio/data_fim (NULL = aberta)
-- e PK composta (uf, data_inicio). Seed de referencia ano-teste replicado dos
-- changesets 0013 e 0019 (Liquibase desabilitado no perfil de teste).
-- Manter sincronizado com 0013-aliquota-ibs-uf.xml e 0019-aliquota-vigencia.xml.
-- ============================================================================
CREATE TABLE IF NOT EXISTS aliquota_ibs_uf (
    uf                 VARCHAR(2)    NOT NULL,
    data_inicio        DATE          NOT NULL,
    data_fim           DATE,
    aliquota_estadual  NUMERIC(7,4)  NOT NULL,
    aliquota_municipal NUMERIC(7,4)  NOT NULL,
    CONSTRAINT pk_aliquota_ibs_uf PRIMARY KEY (uf, data_inicio),
    CONSTRAINT ck_aliquota_ibs_uf_vigencia CHECK (data_fim IS NULL OR data_fim >= data_inicio)
);

MERGE INTO aliquota_ibs_uf (uf, data_inicio, data_fim, aliquota_estadual, aliquota_municipal)
    KEY (uf, data_inicio) VALUES
    ('AC', DATE '2026-01-01', NULL, 0.0005, 0.0005), ('AL', DATE '2026-01-01', NULL, 0.0005, 0.0005),
    ('AM', DATE '2026-01-01', NULL, 0.0005, 0.0005), ('AP', DATE '2026-01-01', NULL, 0.0005, 0.0005),
    ('BA', DATE '2026-01-01', NULL, 0.0005, 0.0005), ('CE', DATE '2026-01-01', NULL, 0.0005, 0.0005),
    ('DF', DATE '2026-01-01', NULL, 0.0005, 0.0005), ('ES', DATE '2026-01-01', NULL, 0.0005, 0.0005),
    ('GO', DATE '2026-01-01', NULL, 0.0005, 0.0005), ('MA', DATE '2026-01-01', NULL, 0.0005, 0.0005),
    ('MG', DATE '2026-01-01', NULL, 0.0005, 0.0005), ('MS', DATE '2026-01-01', NULL, 0.0005, 0.0005),
    ('MT', DATE '2026-01-01', NULL, 0.0005, 0.0005), ('PA', DATE '2026-01-01', NULL, 0.0005, 0.0005),
    ('PB', DATE '2026-01-01', NULL, 0.0005, 0.0005), ('PE', DATE '2026-01-01', NULL, 0.0005, 0.0005),
    ('PI', DATE '2026-01-01', NULL, 0.0005, 0.0005), ('PR', DATE '2026-01-01', NULL, 0.0005, 0.0005),
    ('RJ', DATE '2026-01-01', NULL, 0.0005, 0.0005), ('RN', DATE '2026-01-01', NULL, 0.0005, 0.0005),
    ('RO', DATE '2026-01-01', NULL, 0.0005, 0.0005), ('RR', DATE '2026-01-01', NULL, 0.0005, 0.0005),
    ('RS', DATE '2026-01-01', NULL, 0.0005, 0.0005), ('SC', DATE '2026-01-01', NULL, 0.0005, 0.0005),
    ('SE', DATE '2026-01-01', NULL, 0.0005, 0.0005), ('SP', DATE '2026-01-01', NULL, 0.0005, 0.0005),
    ('TO', DATE '2026-01-01', NULL, 0.0005, 0.0005);

-- ============================================================================
-- Aliquota de CBS por vigencia — Onda 2 issue #85 (migration 0019).
-- Vigencia aberta de 2026 (0,90%, ano-teste). Manter sincronizado com
-- 0019-aliquota-vigencia.xml.
-- ============================================================================
CREATE TABLE IF NOT EXISTS aliquota_cbs (
    data_inicio DATE         NOT NULL,
    data_fim    DATE,
    aliquota    NUMERIC(7,4) NOT NULL,
    CONSTRAINT pk_aliquota_cbs PRIMARY KEY (data_inicio),
    CONSTRAINT ck_aliquota_cbs_vigencia CHECK (data_fim IS NULL OR data_fim >= data_inicio)
);

MERGE INTO aliquota_cbs (data_inicio, data_fim, aliquota) KEY (data_inicio) VALUES
    (DATE '2026-01-01', NULL, 0.0090);

MERGE INTO cfop_referencia (codigo, descricao, tipo_operacao, ambito, aplicavel_produtor_rural) KEY (codigo) VALUES
    ('5101', 'Venda de producao do estabelecimento (operacao interna)', 'SAIDA', 'INTERNO', TRUE),
    ('5102', 'Venda de mercadoria adquirida de terceiros (operacao interna)', 'SAIDA', 'INTERNO', TRUE),
    ('5901', 'Remessa para industrializacao por encomenda (interna)', 'SAIDA', 'INTERNO', TRUE),
    ('5949', 'Outra saida de mercadoria ou prestacao de servico nao especificada (interna)', 'SAIDA', 'INTERNO', TRUE),
    ('6101', 'Venda de producao do estabelecimento (operacao interestadual)', 'SAIDA', 'INTERESTADUAL', TRUE),
    ('6102', 'Venda de mercadoria adquirida de terceiros (operacao interestadual)', 'SAIDA', 'INTERESTADUAL', TRUE),
    ('6949', 'Outra saida de mercadoria ou prestacao de servico nao especificada (interestadual)', 'SAIDA', 'INTERESTADUAL', TRUE),
    ('7101', 'Venda de producao do estabelecimento (exportacao)', 'SAIDA', 'EXTERIOR', TRUE),
    ('1556', 'Compra de material para uso ou consumo (operacao interna)', 'ENTRADA', 'INTERNO', FALSE);
