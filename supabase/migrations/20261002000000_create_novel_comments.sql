-- Additive migration for community novel comments and ratings.
-- Does not alter existing auth, profiles, or library_items schema.

create table if not exists public.novel_comments (
    id uuid primary key default gen_random_uuid(),
    provider_name text not null check (char_length(btrim(provider_name)) > 0),
    novel_url text not null check (char_length(btrim(novel_url)) > 0),
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    rating smallint not null check (rating between 1 and 5),
    comment text not null check (
        char_length(btrim(comment)) >= 1
        and char_length(comment) <= 2000
    ),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint novel_comments_provider_novel_user_key unique (provider_name, novel_url, user_id)
);

create index if not exists novel_comments_lookup_idx
    on public.novel_comments (provider_name, novel_url, created_at desc);

create index if not exists novel_comments_user_id_idx
    on public.novel_comments (user_id);

-- Enforce that user_id always comes from the authenticated session (auth.uid())
-- and manage created_at / updated_at timestamps server-side.
create or replace function public.handle_novel_comment_write()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if auth.uid() is null then
        raise exception 'Authentication required';
    end if;

    new.user_id := auth.uid();
    new.provider_name := btrim(new.provider_name);
    new.novel_url := btrim(new.novel_url);
    new.comment := btrim(new.comment);

    if tg_op = 'INSERT' then
        new.created_at := coalesce(new.created_at, now());
        new.updated_at := now();
    elsif tg_op = 'UPDATE' then
        new.created_at := old.created_at;
        new.updated_at := now();
    end if;

    return new;
end;
$$;

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
    using (true);

drop policy if exists "novel_comments_insert_own" on public.novel_comments;
create policy "novel_comments_insert_own"
    on public.novel_comments
    for insert
    to authenticated
    with check (user_id = auth.uid());

drop policy if exists "novel_comments_update_own" on public.novel_comments;
create policy "novel_comments_update_own"
    on public.novel_comments
    for update
    to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

drop policy if exists "novel_comments_delete_own" on public.novel_comments;
create policy "novel_comments_delete_own"
    on public.novel_comments
    for delete
    to authenticated
    using (user_id = auth.uid());

grant select on public.novel_comments to anon, authenticated;
grant insert, update, delete on public.novel_comments to authenticated;
