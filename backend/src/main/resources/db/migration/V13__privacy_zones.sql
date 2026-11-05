-- The places a user does not want to show on a map or a card (TDD-0016): circles, at most ten per account.
-- The centre of a zone is usually a home: Restricted data (docs/privacy/data-classification.md), never logged,
-- exported with the account and deleted with it (UserData). Replaced as a whole by PUT /v1/privacy-zones.
CREATE TABLE privacy_zones (
    owner_id   uuid NOT NULL REFERENCES users (id),
    position   smallint NOT NULL CHECK (position BETWEEN 0 AND 9),
    latitude   double precision NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude  double precision NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    radius_m   integer NOT NULL CHECK (radius_m BETWEEN 50 AND 2000),
    label      text CHECK (char_length(label) <= 40),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, position)
);
