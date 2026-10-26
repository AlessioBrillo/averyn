# Respond to an incident on a running instance

- **When to use:** the service is down or degraded, an alert fired, someone reports their data is visible to another user, a secret leaked, or the host is suspected of being compromised.
- **Impact / downtime:** depends on the step. Containing a leak (step 3) means a short outage on purpose.
- **Prerequisites:** shell access to the host, the repository at the running version, `dc` as in [backup-database](backup-database.md) (`docker compose -f docker-compose.yml -f docker-compose.tls.yml --env-file .env`), and the name of the person who decides **[controller]** (they decide about telling users and the authority; this runbook does not).

This is written for the managed service ([ADR-0027](../adr/0027-managed-cloud-baseline.md)) and works for a self-hosted one: a self-hoster is their own controller.

## Steps

### 1. Decide what kind of incident it is (five minutes, no changes yet)

| What you see | Kind | Go to |
|---|---|---|
| `/health` or `/ready` not answering, 5xx on every request | Outage | 2 |
| Slow, 429s or 503s for everyone | Overload | 2 |
| A user says they see another user's activities, or the logs show a cross-user read | **Confidentiality** | 3 |
| A secret appeared where it should not (the repository, a log, a screenshot) | **Leaked secret** | 4 |
| Unknown processes, unknown users, files you did not write | **Compromised host** | 5 |

Write down the time you started, who you are, and what you did, as you go. The notes are what the report to the controller is made of.

### 2. Outage or overload

1. `dc ps`: which service is down or restarting? `dc logs --tail 200 <service>`: the last error. The logs carry ids and counts, never coordinates or tokens.
2. Database: `dc exec db pg_isready`. Disk: `df -h` and the volumes (a full disk is the most common cause of both the database and the object store failing).
3. A single failing service: `dc restart <service>` and watch `/ready`. If it fails again, do not loop: read the error.
4. Overload: look at the per-user limit answers (`429`) and the import/export slots (`503` with `Retry-After`) in the logs. One user hammering the API is limited by design; a wide slowdown is the host (CPU, memory, disk).
5. A bad release: the previous version is `git checkout <tag>` and `dc up -d --build`. Database migrations only go forward, so check the release notes before rolling back across a migration ([upgrade-self-hosted](upgrade-self-hosted.md)).
6. If the data is damaged or lost: [restore-database](restore-database.md), then repeat the deletions it lists.

### 3. Confidentiality incident (one user sees another's data)

1. **Contain first.** Stop the service that serves the data: `dc stop backend`. A short outage is better than more exposure. Keep the database and the object store running (the evidence is there).
2. Find the cause without reading anyone's data: the request ids in the access log ( `dc logs backend | grep <request id>` ) say which route answered and for which user id. Every read of an activity checks the owner in the query itself; a route that does not is the bug.
3. Fix it with a test that fails before the fix (the IDOR tests in `backend/src/test` are the pattern). Ship it as a normal release, not a hotfix on the host.
4. Start the service again only when the fix is deployed. Tell the controller **now**, not after the fix: a personal data breach has a notification clock that starts when the controller becomes aware **[controller]**.
5. Work out who was exposed and what: the notes from step 2 and the logs. Do not copy user data into the notes.

### 4. Leaked secret

1. Rotate it: new value in `.env` (generated with `init-env.sh`, never typed), `dc up -d` to apply. Which one it was decides the blast radius: the database password and the object store keys need the matching change in the service; the identity provider's secrets need the provider's own rotation.
2. A leaked **token** of one user: the user signs out everywhere at the identity provider; the access token expires by itself. A leaked **signing key** of the identity provider is the worst case: rotate it and treat every session as stolen.
3. If it was committed: removing it from the repository history is not enough: assume it was read. Rotate first, clean the history after.
4. Check the logs for use of the old value since it leaked.

### 5. Compromised host

1. Do not try to clean it in place. Cut the host off the network at the provider, keep the disk for the investigation.
2. Build a new host from the repository, restore from the **last backup that predates the compromise** ([restore-database](restore-database.md)), rotate **every** secret, and reissue the TLS certificates (they are issued again on first start).
3. Repeat the deletions users asked for since that backup.
4. Tell the controller; the same notification clock as in step 3 applies **[controller]**.

## Verify

- `/health` and `/ready` answer; `dc ps` shows every service healthy.
- Sign in to the web app with a test user: the list opens, a detail page and its map open, an import of a small file works.
- For a confidentiality incident: the test that reproduces it passes in the deployed version.
- The notes are complete: times, what you saw, what you did, who you told.

## After

Within a week: a short written account (what happened, why, what changed so it cannot happen again), added to the test suite or to this runbook. If the incident touched personal data, the controller decides about the DPIA ([dpia.md](../privacy/dpia.md)) and the privacy notice.

## Rollback

Each step above is undoable except rotating a secret (which is the point) and deleting a compromised host. Keep the old host's disk until the investigation is closed.
