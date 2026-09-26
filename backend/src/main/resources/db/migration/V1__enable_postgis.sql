-- Fails fast if the database image does not ship PostGIS (ADR-0006).
-- Domain tables are added by the migration that introduces the first feature using them.
CREATE EXTENSION IF NOT EXISTS postgis;
