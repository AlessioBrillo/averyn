# Runbooks

Step-by-step operational procedures, written the first time an operation is performed. One file per procedure, named `<verb>-<object>.md` (e.g. `restore-database.md`).

## Template

```markdown
# <Procedure>
- **When to use:**
- **Impact / downtime:**
- **Prerequisites:**

## Steps
1.
2.

## Verify
How to confirm it worked.

## Rollback
```

## Procedures

- [backup-database](backup-database.md): back up a self-hosted instance
- [restore-database](restore-database.md): restore one from a backup
- [upgrade-self-hosted](upgrade-self-hosted.md): move to a newer version

## Planned (tracked as issues)

`rotate-secrets`, `investigate-failed-sync`.
