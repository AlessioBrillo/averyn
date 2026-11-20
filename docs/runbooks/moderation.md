# Runbook: reports and moderation (no admin console)

There is no admin interface ([ADR-0035](../adr/0035-no-admin-interface-before-moderation.md)). The operator reads reports and acts on them with SQL, from a shell on the database host. Never write a user's content (bio, note, coordinates) in a ticket, a chat or a log.

## Look at the open reports

```sql
SELECT id, created_at, reason, target_user_id, target_activity_id, left(note, 200) AS note
FROM reports WHERE resolved_at IS NULL ORDER BY created_at;
```

Answer a report within the time the terms promise. A report of a minor, of a threat or of a home address is handled the same day.

## Act

- **Hide a profile's free text:** `UPDATE user_profiles SET bio = NULL, avatar_key = NULL WHERE owner_id = '<id>';` (delete the avatar object with the storage tool; the key is in the row).
- **Make an activity private:** `UPDATE activities SET visibility = 'private', share_token = NULL WHERE id = '<id>';`
- **Block a user from the platform:** delete the account the way the user would (`DELETE /v1/me` path, see [incident](incident.md)), never by deleting rows by hand: raw files and tombstones must go with it.
- **Close the report:** `UPDATE reports SET resolved_at = now() WHERE id = '<id>';`

## Retention

A report is kept 6 months after it is created, without the reporter once the reporter's account is deleted ([ADR-0036](../adr/0036-social-in-the-launch.md)). Run monthly:

```sql
DELETE FROM reports WHERE created_at < now() - interval '6 months';
```

## Not here yet

Roles, an audit log of who did what, and a screen: designed with the groups slice (S4/S5 in ADR-0036). Until then every action above is done by the one operator and written in the operator's own log without personal content.
