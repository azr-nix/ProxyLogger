CREATE TABLE IF NOT EXISTS linked_accounts (
  minecraft_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
  minecraft_username VARCHAR(16) NOT NULL,
  account_type ENUM('online','offline') NOT NULL,
  discord_id VARCHAR(20) NOT NULL UNIQUE,
  linked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen TIMESTAMP NULL,
  link_status ENUM('linked','unlinked','restricted') NOT NULL DEFAULT 'linked',
  INDEX idx_username (minecraft_username)
);

CREATE TABLE IF NOT EXISTS link_codes (
  code_hash CHAR(64) NOT NULL PRIMARY KEY,
  minecraft_uuid VARCHAR(36) NOT NULL,
  minecraft_username VARCHAR(16) NOT NULL,
  account_type ENUM('online','offline') NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  INDEX idx_expiry (expires_at),
  INDEX idx_uuid (minecraft_uuid)
);

CREATE TABLE IF NOT EXISTS access_audit (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_type VARCHAR(40) NOT NULL,
  minecraft_uuid VARCHAR(36) NULL,
  minecraft_username VARCHAR(16) NULL,
  discord_id VARCHAR(20) NULL,
  guild_id VARCHAR(20) NULL,
  role_id VARCHAR(20) NULL,
  result VARCHAR(20) NOT NULL,
  reason VARCHAR(255) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_created (created_at),
  INDEX idx_player (minecraft_uuid)
);

CREATE TABLE IF NOT EXISTS access_rules (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  rule_type ENUM('allow','blacklist') NOT NULL,
  guild_id VARCHAR(20) NOT NULL,
  role_id VARCHAR(20) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_rule (rule_type, guild_id, role_id)
);
