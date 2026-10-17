# Remote-hands channel: any change to this file runs it on the VPS (as the deploy user, in /opt/saadat)
# and the output is committed back to deploy/ops/last-output.log
# Idle. Last use 2026-10-17 13:42 UTC: removed the owner's own test data, save point
# /opt/saadat/backups/before-owner-test-cleanup-20261017-134209.dump (kept RN-15004 and 1 credit).
# NOTE: commands that call `docker compose exec -T` must redirect stdin (< /dev/null or a heredoc), because this
# script itself is fed to bash on stdin and exec -T would swallow the rest of it.
true
