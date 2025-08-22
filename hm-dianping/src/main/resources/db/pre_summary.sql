-- 预摘要表（被在线检索直接命中）
CREATE TABLE IF NOT EXISTS pre_summary (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  blog_id       BIGINT NOT NULL,
  chunk_id      INT DEFAULT 0,
  source_hash   CHAR(40) NOT NULL,
  summary       TEXT NOT NULL,
  keywords      VARCHAR(512) NOT NULL,
  token_cost    INT DEFAULT 0,
  model         VARCHAR(64) NOT NULL,
  quality_score TINYINT DEFAULT 0,
  updated_at    DATETIME NOT NULL,
  expires_at    DATETIME NULL,
  UNIQUE KEY uk_blog_chunk (blog_id, chunk_id),
  KEY idx_updated_at (updated_at),
  KEY idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 生成/更新任务表（用于增量与定时刷新）
CREATE TABLE IF NOT EXISTS pre_summary_jobs (
  job_id      BIGINT PRIMARY KEY AUTO_INCREMENT,
  blog_id     BIGINT NOT NULL,
  chunk_id    INT DEFAULT 0,
  source_hash CHAR(40) NOT NULL,
  priority    TINYINT DEFAULT 5,
  status      ENUM('PENDING','RUNNING','DONE','FAILED','GAVEUP') DEFAULT 'PENDING',
  attempts    INT DEFAULT 0,
  last_error  VARCHAR(1024) NULL,
  created_at  DATETIME NOT NULL,
  updated_at  DATETIME NOT NULL,
  UNIQUE KEY uk_job (blog_id, chunk_id, source_hash),
  KEY idx_status_priority (status, priority),
  KEY idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


