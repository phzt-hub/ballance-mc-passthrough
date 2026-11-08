#!/usr/bin/env bash
# Run a batch snippet on the 128 Windows box over ssh.
#
# The Windows box runs OpenSSH with PowerShell as the login shell, so a cmd
# string passed straight through dies on cmd's `&` ("AmpersandNotAllowed").
# Every step therefore goes into a .cmd file, is scp'd over, and is executed by
# name - PowerShell only ever sees one path.
#
#   ./tools/win128-run.sh <<'EOF'
#   cd /d D:\bmc-build
#   dir /b
#   EOF
#
# Env: WIN128_HOST  win128 (see ~/.ssh/config)
set -uo pipefail
HOST=${WIN128_HOST:-win128}
REMOTE_WIN='C:\Users\Administrator\_step.cmd'
TMP=$(mktemp /tmp/win128-XXXXXX.cmd)
trap 'rm -f "$TMP"' EXIT
cat > "$TMP"
sed -i 's/$//' "$TMP"   # cmd.exe wants CRLF
scp -q -o ConnectTimeout=10 "$TMP" "$HOST:/C:/Users/Administrator/_step.cmd" || exit 1
ssh -o ConnectTimeout=10 "$HOST" "$REMOTE_WIN"
rc=$?
echo "[win128 exit=$rc]"
exit $rc
