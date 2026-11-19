# Local CI gate

`ci-local.sh` runs the repository's Woodpecker pipeline (`.woodpecker/ci.yml`)
locally, without a server. It copies the current tree to a temporary directory
and runs `woodpecker-cli exec` there, so the checkout is never modified.

## Requirements

- Docker running.
- `woodpecker-cli` on `PATH`.
- `git` and `tar` (Git Bash on Windows, or WSL).

## Usage

```sh
tools/ci/ci-local.sh                 # the default pipeline
tools/ci/ci-local.sh .woodpecker/ci.yml
```

On Windows:

```bat
tools\ci\ci-local.cmd
```

From WSL, run `sh tools/ci/ci-local.sh` from the repository root. If the
Android Gradle Plugin tasks fail because a Windows path is bind-mounted into a
Linux container, run the script from a clone in the Linux filesystem instead:

```sh
wsl -d Ubuntu-24.04
cd ~/src/averyn
tools/ci/ci-local.sh
```

Exit code `0` means the gate is green. The script uses a fixed temporary
directory (`/tmp/ci-local-<repo>`) so the named cache volumes stay warm. Set
`CI_LOCAL_WORKDIR` to override it and `CI_LOCAL_TIMEOUT` to change the 3-hour
default.

## Troubleshooting

**`Could not connect to Ryuk at host.docker.internal:<port>`** in `:backend:test`.
The step reaches the host engine through the mounted socket, so Testcontainers
must also reach the ports the engine publishes. On Docker Desktop that address
is the engine VM and already resolves: do **not** overwrite it with the bridge
gateway (the old setup did, and every mapped port was refused). The pipeline
only adds the gateway alias when the name does not resolve, which is the case on
a plain Linux runner. The same rule applies to local Testcontainers runs: on
Docker Desktop, leave `host.docker.internal` to Docker's DNS.

The pipeline is the gate. GitHub Actions is not: the workflows there are
disabled and kept only for history.
