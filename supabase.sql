-- Run this once in Supabase: SQL Editor -> New query -> paste -> Run.

create table if not exists items (
  id text primary key,
  created_at bigint not null,
  rec text not null
);
create table if not exists claims (
  id text primary key,
  created_at bigint not null,
  rec text not null
);
create table if not exists suggestions (
  id text primary key,
  created_at bigint not null,
  rec text not null
);

-- Lock the tables so only the Java server (using the secret key) can read or write them.
alter table items enable row level security;
alter table claims enable row level security;
alter table suggestions enable row level security;

-- Public bucket for item photos.
insert into storage.buckets (id, name, public)
values ('photos', 'photos', true)
on conflict (id) do nothing;
