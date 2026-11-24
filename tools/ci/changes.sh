#!/usr/bin/env bash
# Usage: tools/ci/changes.sh <app|server|docs|formal>
#
# Decides whether a workflow's work is needed, and writes run=true or
# run=false to $GITHUB_OUTPUT. The app, server, docs and formal workflows run
# on every pull request (no path filter, so that their required checks always
# report) and call this as the step after checkout; their work steps run only
# when it says run=true, and the job otherwise succeeds at once.
#
# A pull request checks out its merge commit (checkout with fetch-depth: 2):
# the first parent is the base branch, so the diff against it is what the
# pull request changes. Any other event (a push to main, already filtered by
# its paths, a schedule or a manual run) needs the work. So does a pull
# request whose diff cannot be determined.
set -euo pipefail

area=${1:-}

# Whether the area needs a check when the file $1 changes. The paths match
# the workflow's push trigger, plus the files outside its directory that its
# tests or build read. A change to this script runs every area.
needs() {
  case "$1" in
    tools/ci/changes.sh) return 0 ;;
  esac
  case "$area" in
    app)
      case "$1" in
        app/* | core/* | build.gradle.kts | settings.gradle.kts | gradle.properties | \
        gradle/* | gradlew | gradlew.bat | .github/workflows/app.yml | \
        tools/licenses/* | server/web/test/fixtures/*)
          return 0 ;;
      esac ;;
    server)
      case "$1" in
        server/* | .github/workflows/server.yml | \
        docs/protocol-constants.json | core/src/main/scala/ashidori/core/sync/DeviceStatus.scala | \
        app/src/main/assets/basemap-pin.json)
          return 0 ;;
      esac ;;
    docs)
      case "$1" in
        docs/diagrams/* | tools/docs/render-diagrams.sh | .github/workflows/docs.yml)
          return 0 ;;
      esac ;;
    formal)
      case "$1" in
        formal/* | docs/sync-protocol.md | docs/design/detail-log-format.md | \
        app/src/main/scala/net/sksat/ashidori/GnssRecorder.scala | server/src/* | server/migrations/* | \
        app/src/main/scala/net/sksat/ashidori/sync/* | core/src/main/scala/ashidori/core/sync/* | \
        core/src/main/scala/ashidori/core/SendGate.scala | app/src/main/scala/net/sksat/ashidori/Db.scala | \
        app/src/main/scala/net/sksat/ashidori/ArchiveStore.scala | \
        app/src/main/scala/net/sksat/ashidori/ui/SyncSettingsScreen.scala | .github/workflows/formal.yml)
          return 0 ;;
      esac ;;
  esac
  return 1
}

case "$area" in
  app | server | docs | formal) ;;
  *) echo "usage: $0 <app|server|docs|formal>" >&2; exit 2 ;;
esac

run=true
changed=
if [ "${GITHUB_EVENT_NAME:-}" != pull_request ]; then
  why="event ${GITHUB_EVENT_NAME:-unknown}: the work runs"
elif ! git rev-parse -q --verify HEAD^2 > /dev/null; then
  why="no merge commit to compare with: the work runs"
elif ! changed=$(git diff --no-renames --name-only HEAD^1 HEAD); then
  # --no-renames lists both paths of a moved file.
  why="the diff failed: the work runs"
  changed=
else
  run=false
  why="no changed file needs the $area checks: skipped"
  while IFS= read -r f; do
    if [ -n "$f" ] && needs "$f"; then
      run=true
      why="$f needs the $area checks"
      break
    fi
  done <<< "$changed"
fi

echo "run=$run ($why)"
echo "run=$run" >> "${GITHUB_OUTPUT:-/dev/null}"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "**$area**: run=$run ($why)"
    if [ -n "$changed" ]; then
      echo
      echo '<details><summary>changed files</summary>'
      echo
      echo '```'
      echo "$changed"
      echo '```'
      echo '</details>'
    fi
  } >> "$GITHUB_STEP_SUMMARY"
fi
