-- Snapshot of the SQLite schema expected by milla.core/init!
-- Safe to run multiple times because of IF NOT EXISTS.

create table if not exists statements (
  id integer primary key,
  kind text not null,
  text text not null,
  created_at text not null,
  source_node text not null
);

create table if not exists chat (
  id integer primary key,
  role text not null,        -- 'user' or 'assistant'
  model text,
  content text not null,
  session text not null default 'default',
  created_at text not null,
  responded_at text,
  source_node text not null
);
