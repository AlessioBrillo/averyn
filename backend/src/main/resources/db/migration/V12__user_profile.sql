-- The settings every client of an account shares (TDD-0012): the unit system and the maximum heart rate.
-- One row per user, created by the first save; an account without a row has the defaults. Deleted with the
-- account (UserData.deleteAccount) and part of the export.
CREATE TABLE user_profiles (
    owner_id    uuid PRIMARY KEY REFERENCES users (id),
    units       text NOT NULL CHECK (units IN ('metric', 'imperial')),
    max_hr_bpm  smallint CHECK (max_hr_bpm BETWEEN 100 AND 230),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
