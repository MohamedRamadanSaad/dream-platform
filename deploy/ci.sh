#!/usr/bin/env bash
# ci.sh wait <workflow-file> [sha]   -> waits for the run of <sha|HEAD> and prints conclusion
# ci.sh log <run_id>                 -> prints the failing job's log tail (errors first)
set -euo pipefail
REPO=MohamedRamadanSaad/dream-platform
API=https://api.github.com/repos/$REPO
H=(-H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json")
cmd=${1:-wait}
case $cmd in
  wait)
    wf=${2:-backend.yml}; sha=${3:-$(git rev-parse HEAD)}
    for i in $(seq 1 120); do
      run=$(curl -s "${H[@]}" "$API/actions/workflows/$wf/runs?head_sha=$sha&per_page=1")
      id=$(echo "$run" | python3 -c 'import sys,json;r=json.load(sys.stdin)["workflow_runs"];print(r[0]["id"] if r else "")')
      st=$(echo "$run" | python3 -c 'import sys,json;r=json.load(sys.stdin)["workflow_runs"];print((r[0]["status"]+":"+str(r[0]["conclusion"])) if r else "none")')
      echo "[$i] run=$id $st"
      case $st in completed:*) echo "RUN_ID=$id CONCLUSION=${st#completed:}"; exit 0;; esac
      sleep 20
    done; echo timeout; exit 1;;
  log)
    id=$2
    jobs=$(curl -s "${H[@]}" "$API/actions/runs/$id/jobs")
    jid=$(echo "$jobs" | python3 -c 'import sys,json;j=json.load(sys.stdin)["jobs"];f=[x for x in j if x["conclusion"]!="success"];print((f or j)[0]["id"])')
    curl -sL "${H[@]}" "$API/actions/jobs/$jid/logs" -o /tmp/ci-log.txt || true
    wc -l /tmp/ci-log.txt
    grep -nE "error:|FAILED|Exception|BUILD (FAILED|SUCCESSFUL)|Tests run|tests completed|> Task .*FAILED" /tmp/ci-log.txt | head -80;;
esac
