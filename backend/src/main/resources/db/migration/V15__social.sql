-- Social foundation (TDD-0018, ADR-0036): the public side of a profile, follows, blocks, reports and the share token.
-- The public fields live next to the settings of V12: one row per user, created by the first save of either.
-- A handle is stored in lower case (the API normalises it), so the plain unique index is the case-insensitive one.
-- Everything here is deleted with the account (UserData.deleteAccount); a report keeps no reporter afterwards.
ALTER TABLE user_profiles
    ADD COLUMN handle             text CHECK (handle ~ '^[a-z0-9_.]{3,30}$'),
    ADD COLUMN handle_changed_at  timestamptz,
    ADD COLUMN display_name       text CHECK (char_length(display_name) BETWEEN 1 AND 40),
    ADD COLUMN bio                text CHECK (char_length(bio) <= 160),
    ADD COLUMN sport              text CHECK (char_length(sport) <= 30),
    ADD COLUMN avatar_key         text,
    ADD COLUMN profile_private    boolean NOT NULL DEFAULT false,
    ADD COLUMN default_visibility text NOT NULL DEFAULT 'private'
        CHECK (default_visibility IN ('private', 'followers', 'public')),
    ADD COLUMN age_confirmed_at   timestamptz;

CREATE UNIQUE INDEX user_profiles_handle_uidx ON user_profiles (handle);

-- A follow is a request until the followed accepts it.
CREATE TABLE follows (
    follower_id uuid NOT NULL REFERENCES users (id),
    followed_id uuid NOT NULL REFERENCES users (id),
    state       text NOT NULL CHECK (state IN ('pending', 'active')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_id, followed_id),
    CHECK (follower_id <> followed_id)
);
CREATE INDEX follows_followed_idx ON follows (followed_id, state);

CREATE TABLE blocks (
    blocker_id uuid NOT NULL REFERENCES users (id),
    blocked_id uuid NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);
CREATE INDEX blocks_blocked_idx ON blocks (blocked_id);

-- A report of a profile or of an activity (docs/runbooks/moderation.md). The reporter is removed when the reporter's
-- account is deleted; the row itself stays 6 months (ADR-0036). The activity id has no foreign key: the activity may be
-- deleted by its owner, and the report stays as the record of what was reported.
CREATE TABLE reports (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id        uuid REFERENCES users (id),
    target_user_id     uuid NOT NULL REFERENCES users (id),
    target_activity_id uuid,
    reason             text NOT NULL CHECK (reason IN
        ('spam', 'abuse', 'impersonation', 'privacy', 'minor', 'illegal', 'other')),
    note               text CHECK (char_length(note) <= 500),
    created_at         timestamptz NOT NULL DEFAULT now(),
    resolved_at        timestamptz
);
-- One open report per reporter and target: a second one is the same report.
CREATE UNIQUE INDEX reports_open_uidx ON reports (
    reporter_id, target_user_id, coalesce(target_activity_id, '00000000-0000-0000-0000-000000000000'::uuid)
) WHERE resolved_at IS NULL;
CREATE INDEX reports_open_idx ON reports (created_at) WHERE resolved_at IS NULL;

-- The unguessable token of a `link` activity; null for every other visibility.
ALTER TABLE activities ADD COLUMN share_token text;
CREATE UNIQUE INDEX activities_share_token_uidx ON activities (share_token) WHERE share_token IS NOT NULL;
