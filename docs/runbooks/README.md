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

## Planned (tracked as issues)

`backup-database`, `restore-database`, `rotate-secrets`, `upgrade-self-hosted`, `investigate-failed-sync`.
