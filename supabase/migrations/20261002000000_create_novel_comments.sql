-- Additive migration for community novel comments, ratings, and comment reports.
-- Does not alter existing auth, profiles, discover, or library_items schema.

create table if not exists public.novel_comments (
    id uuid primary key default gen_random_uuid(),
    provider_name text not null check (
        char_length(btrim(provider_name)) > 0
        and char_length(provider_name) <= 100
    ),
    novel_url text not null check (
        char_length(btrim(novel_url)) > 0
        and char_length(novel_url) <= 1000
    ),
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    author_name text not null default 'Anonymous' check (
        char_length(btrim(author_name)) > 0
        and char_length(author_name) <= 100
    ),
    rating smallint not null check (rating between 1 and 5),
    comment text not null check (
        char_length(btrim(comment)) >= 1
        and char_length(comment) <= 2000
    ),
    hidden boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint novel_comments_provider_novel_user_key unique (provider_name, novel_url, user_id)
);

create index if not exists novel_comments_lookup_idx
    on public.novel_comments (provider_name, novel_url, created_at desc)
    where not hidden;

create index if not exists novel_comments_user_created_idx
    on public.novel_comments (user_id, created_at desc);

-- Enforce session identity (auth.uid()), populate author_name from public.profiles.username,
-- enforce the 20 comments per 24h per user rate limit, and manage timestamps server-side.
create or replace function public.handle_novel_comment_write()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_uid uuid := (select auth.uid());
    v_username text;
    v_recent_count integer;
begin
    if v_uid is null then
        raise exception 'Authentication required';
    end if;

    new.user_id := v_uid;
    new.provider_name := pg_catalog.btrim(new.provider_name);
    new.novel_url := pg_catalog.btrim(new.novel_url);
    new.comment := pg_catalog.btrim(new.comment);

    select pg_catalog.nullif(pg_catalog.btrim(p.username), '')
      into v_username
      from public.profiles p
     where p.id = v_uid;

    new.author_name := pg_catalog.coalesce(v_username, 'Anonymous');

    if tg_op = 'INSERT' then
        select count(*)
          into v_recent_count
          from public.novel_comments c
         where c.user_id = v_uid
           and c.created_at >= (pg_catalog.now() - interval '24 hours')
           and not (c.provider_name = new.provider_name and c.novel_url = new.novel_url);

        if v_recent_count >= 20 then
            raise exception 'Daily comment limit reached';
        end if;

        new.hidden := false;
        new.created_at := pg_catalog.coalesce(new.created_at, pg_catalog.now());
        new.updated_at := pg_catalog.now();
    elsif tg_op = 'UPDATE' then
        new.id := old.id;
        new.hidden := old.hidden;
        new.created_at := old.created_at;
        new.updated_at := pg_catalog.now();
    end if;

    return new;
end;
$$;

revoke execute on function public.handle_novel_comment_write() from public, anon, authenticated;

drop trigger if exists novel_comments_before_write on public.novel_comments;
create trigger novel_comments_before_write
    before insert or update on public.novel_comments
    for each row
    execute function public.handle_novel_comment_write();

alter table public.novel_comments enable row level security;

drop policy if exists "novel_comments_select_public" on public.novel_comments;
create policy "novel_comments_select_public"
    on public.novel_comments
    for select
    to anon, authenticated
    using (not hidden);

drop policy if exists "novel_comments_insert_own" on public.novel_comments;
create policy "novel_comments_insert_own"
    on public.novel_comments
    for insert
    to authenticated
    with check ((select auth.uid()) is not null and user_id = (select auth.uid()) and not hidden);

drop policy if exists "novel_comments_update_own" on public.novel_comments;
create policy "novel_comments_update_own"
    on public.novel_comments
    for update
    to authenticated
    using ((select auth.uid()) is not null and user_id = (select auth.uid()) and not hidden)
    with check ((select auth.uid()) is not null and user_id = (select auth.uid()) and not hidden);

drop policy if exists "novel_comments_delete_own" on public.novel_comments;
create policy "novel_comments_delete_own"
    on public.novel_comments
    for delete
    to authenticated
    using ((select auth.uid()) is not null and user_id = (select auth.uid()));

grant select on public.novel_comments to anon, authenticated;
grant insert, update, delete on public.novel_comments to authenticated;

-- Reports table for community novel comments.
create table if not exists public.novel_comment_reports (
    id uuid primary key default gen_random_uuid(),
    comment_id uuid not null references public.novel_comments(id) on delete cascade,
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    reason text not null default '' check (char_length(reason) <= 200),
    created_at timestamptz not null default now(),
    constraint novel_comment_reports_comment_user_key unique (comment_id, user_id)
);

create index if not exists novel_comment_reports_comment_id_idx
    on public.novel_comment_reports (comment_id);

alter table public.novel_comment_reports enable row level security;

drop policy if exists "novel_comment_reports_select_own" on public.novel_comment_reports;
create policy "novel_comment_reports_select_own"
    on public.novel_comment_reports
    for select
    to authenticated
    using ((select auth.uid()) is not null and user_id = (select auth.uid()));

drop policy if exists "novel_comment_reports_insert_own" on public.novel_comment_reports;
create policy "novel_comment_reports_insert_own"
    on public.novel_comment_reports
    for insert
    to authenticated
    with check ((select auth.uid()) is not null and user_id = (select auth.uid()));

grant select, insert on public.novel_comment_reports to authenticated;
