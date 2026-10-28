CREATE TABLE IF NOT EXISTS knowledge_document (
  id UUID PRIMARY KEY,
  scope VARCHAR(10) NOT NULL CHECK (scope IN ('SHARED', 'PRIVATE')),
  owner_id VARCHAR(100),
  title TEXT NOT NULL,
  category TEXT NOT NULL DEFAULT 'uncategorized',
  source_url TEXT,
  stored_path TEXT,
  sha256 VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'READY',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((scope = 'SHARED' AND owner_id IS NULL) OR (scope = 'PRIVATE' AND owner_id IS NOT NULL))
);
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS category TEXT NOT NULL DEFAULT 'uncategorized';
CREATE UNIQUE INDEX IF NOT EXISTS knowledge_document_source ON knowledge_document(scope, COALESCE(owner_id,''), source_url) WHERE source_url IS NOT NULL;
CREATE TABLE IF NOT EXISTS knowledge_chunk (
  document_id UUID NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
  chunk_id TEXT NOT NULL PRIMARY KEY
);
CREATE TABLE IF NOT EXISTS conversation (
  id UUID PRIMARY KEY,
  owner_id VARCHAR(100) NOT NULL,
  scope VARCHAR(10) NOT NULL CHECK (scope IN ('SHARED','PRIVATE')),
  title TEXT NOT NULL DEFAULT '새 대화',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS chat_message (
  id BIGSERIAL PRIMARY KEY,
  conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
  role VARCHAR(10) NOT NULL,
  body TEXT NOT NULL,
  sources JSONB NOT NULL DEFAULT '[]'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS chat_message_conversation ON chat_message(conversation_id, id);
CREATE TABLE IF NOT EXISTS visitor_session (
  id UUID PRIMARY KEY,
  token_hash CHAR(64) NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS visitor_daily_usage (
  usage_day DATE NOT NULL,
  visitor_id UUID NOT NULL REFERENCES visitor_session(id) ON DELETE CASCADE,
  used INT NOT NULL CHECK (used BETWEEN 1 AND 5),
  PRIMARY KEY (usage_day, visitor_id)
);
CREATE TABLE IF NOT EXISTS global_daily_usage (
  usage_day DATE PRIMARY KEY,
  used INT NOT NULL CHECK (used BETWEEN 1 AND 30)
);
