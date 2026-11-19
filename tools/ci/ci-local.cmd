@echo off
rem Windows shim for ci-local.sh. Needs Git Bash (bash.exe on PATH) and Docker.
rem Usage: tools\ci\ci-local.cmd [pipeline-file]
bash "%~dp0ci-local.sh" %*
