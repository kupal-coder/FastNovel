-- Community comments are separate from provider-sourced UserReview entries.
-- Deploy this migration to the configured Supabase project before enabling comments.

CREATE TABLE IF NOT EXISTS public.novel_comments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_name text NOT NULL CHECK (btrim(provider_name) <> ''),
    novel_url text NOT NULL CHECK (btrim(novel_url) <> ''),
    user_id uuid NOT NULL DEFAULT auth.uid(),
    rating smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment text NOT NULL
        CHECK (char_length(comment) BETWEEN 1 AND 2000 AND comment ~ '[^[:space:]]'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT novel_comments_provider_novel_user_unique
        UNIQUE (provider_name, novel_url, user_id)
);

ALTER TABLE public.novel_comments ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS novel_comments_public_read ON public.novel_comments;
CREATE POLICY novel_comments_public_read
    ON public.novel_comments
    FOR SELECT
    TO anon, authenticated
    USING (true);

DROP POLICY IF EXISTS novel_comments_insert_own ON public.novel_comments;
CREATE POLICY novel_comments_insert_own
    ON public.novel_comments
    FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS novel_comments_update_own ON public.novel_comments;
CREATE POLICY novel_comments_update_own
    ON public.novel_comments
    FOR UPDATE
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS novel_comments_delete_own ON public.novel_comments;
CREATE POLICY novel_comments_delete_own
    ON public.novel_comments
    FOR DELETE
    TO authenticated
    USING (auth.uid() = user_id);

GRANT SELECT ON public.novel_comments TO anon, authenticated;
GRANT INSERT, UPDATE, DELETE ON public.novel_comments TO authenticated;

-- The authenticated identity always comes from the JWT, never from a request body. Preserve the
-- original author/creation time on edits and own updated_at on the server.
CREATE OR REPLACE FUNCTION public.novel_comments_set_row_identity()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.user_id := auth.uid();
        NEW.created_at := now();
    ELSE
        NEW.user_id := OLD.user_id;
        NEW.created_at := OLD.created_at;
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS novel_comments_set_row_identity ON public.novel_comments;
CREATE TRIGGER novel_comments_set_row_identity
    BEFORE INSERT OR UPDATE ON public.novel_comments
    FOR EACH ROW
    EXECUTE FUNCTION public.novel_comments_set_row_identity();

-- Public profile fields/RLS are intentionally not changed. Until a public author identity is
-- verified, the client displays its localized generic reader label and a default avatar.
