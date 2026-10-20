CREATE TABLE rag_conversations (
 id VARCHAR(36) PRIMARY KEY,
 created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE rag_conversation_exchanges (
 sequence_id BIGINT AUTO_INCREMENT PRIMARY KEY,
 conversation_id VARCHAR(36) NOT NULL,
 request_id VARCHAR(36) NOT NULL,
 question TEXT NOT NULL,
 response_json MEDIUMTEXT,
 created_at TIMESTAMP(6),
 CONSTRAINT fk_rag_exchange_conversation FOREIGN KEY (conversation_id) REFERENCES rag_conversations(id),
 CONSTRAINT uq_rag_exchange_request UNIQUE(conversation_id, request_id)
);
CREATE INDEX ix_rag_exchange_order ON rag_conversation_exchanges(conversation_id, created_at, sequence_id);
