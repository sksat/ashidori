#!/bin/bash
# Measurements behind docs/design/detail-log-format.md, on a copy of a real
# database. Prints aggregate numbers only.
#
#   tools/detail-log-bench/run.sh <copy of ashidori.db> <scratch dir>
#
# The scratch dir receives the user's data (dumps, tables, Parquet files):
# put it outside the repository and delete it afterwards. Nothing here is
# written back to the database.
#
# Needs: python3 (the venv below pins the versions the design was measured
# with), sqlite3, Java 17, and the core classes (./gradlew :core:classes) for
# the JVM part. All times printed are wall-clock on this machine.
set -euo pipefail
DB=$(realpath "$1"); S=$(realpath -m "$2")
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
# The scripts empty their output directories, all under the scratch dir. The
# scratch dir must be new (created here, so nothing in it can be a link), must
# not hold the database or lie in the repository; every output path is
# resolved and checked against the database, and the Python helpers get the
# database as a protected path and check again before deleting.
[ -e "$S" ] && { echo "refusing: $S exists; give a new directory" >&2; exit 2; }
case "$DB" in "$S"|"$S"/*) echo "refusing: the database $DB is inside the scratch dir $S" >&2; exit 2 ;; esac
case "$S" in "$ROOT"|"$ROOT"/*) echo "refusing: the scratch dir $S is inside the repository" >&2; exit 2 ;; esac
mkdir -p "$(dirname "$S")"; mkdir "$S"
for o in t w s pq jvm raw.txt imu_t.bin imu_s.bin imu_v.bin proto.bin venv; do
  r=$(realpath -m "$S/$o")
  case "$DB" in "$r"|"$r"/*) echo "refusing: output $r is or contains the database" >&2; exit 2 ;; esac
  case "$r" in "$S"/*) ;; *) echo "refusing: output $r resolves outside $S" >&2; exit 2 ;; esac
done
export DETAIL_BENCH_PROTECT="$DB"
PINS="pyarrow==26.0.0 zstandard==0.25.0 numpy==2.5.4 vortex-data==0.89.0 pylance==13.0.0 msgpack==1.2.3 fastavro==1.13.1 mcap==1.6.0 duckdb==1.5.6"
python3 -m venv "$S/venv"
# shellcheck disable=SC2086 # PINS holds several requirements
"$S/venv/bin/pip" install -q $PINS
# Verify the installed versions (a reused venv may hold others).
# shellcheck disable=SC2086
"$S/venv/bin/python" -I - $PINS <<'PYEOF'
import sys
from importlib.metadata import version
bad = [p for p in sys.argv[1:] if version(p.split("==")[0]) != p.split("==")[1]]
sys.exit(f"version mismatch: {bad}" if bad else 0)
PYEOF
PY="$S/venv/bin/python -I"

$PY "$HERE/extract.py" "$DB" "$S/t"
$PY "$HERE/profile.py" "$S/t"
$PY "$HERE/measure.py" "$S/t" "$S/w"
$PY "$HERE/survey.py" "$S/t" "$S/s"

# JVM: today's path (core writer, reader) and the journal prototype.
G=${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1
jar() { find "$G/$1" -name "$2" ! -name '*sources*' | sort | tail -1; }
# The Scala version of the build (gradle/libs.versions.toml); fail if it is not cached.
SCALA=$(sed -n 's/^scala *= *"\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml")
need() { [ -n "$1" ] && [ -f "$1" ] || { echo "missing jar: $2 (run ./gradlew :core:classes first)" >&2; exit 2; }; }
LIB="$(jar org.scala-lang/scala-library "scala-library-$SCALA.jar")"; need "$LIB" "scala-library $SCALA"
C1="$(jar org.scala-lang/scala3-compiler_3 "scala3-compiler_3-$SCALA.jar")"; need "$C1" "scala3-compiler $SCALA"
C2="$(jar org.scala-lang/tasty-core_3 "tasty-core_3-$SCALA.jar")"; need "$C2" "tasty-core $SCALA"
C3="$(jar org.scala-lang/scala3-interfaces "scala3-interfaces-$SCALA.jar")"; need "$C3" "scala3-interfaces $SCALA"
COMP="$C1:$C2:$C3:$(jar org.scala-lang.modules/scala-asm '*.jar'):$(jar org.scala-sbt/compiler-interface '*.jar'):$(jar org.scala-sbt/util-interface '*.jar')"
java -version 2>&1 | head -1 | grep -Eq '"(1[7-9]|[2-9][0-9])' || { echo "Java 17 or later is required" >&2; exit 2; }
CORE="$ROOT/core/build/classes/scala/main"
mkdir -p "$S/jvm"
java -cp "$COMP:$LIB" dotty.tools.dotc.Main -classpath "$LIB:$CORE" -d "$S/jvm" "$HERE"/jvm/*.scala
run() { java -Xmx1g -cp "$S/jvm:$LIB:$CORE" "$@"; }
sqlite3 -separator '|' "file:$DB?mode=ro" "SELECT time, line FROM raw_lines ORDER BY rowid" > "$S/raw.txt"
run Bench "$S/raw.txt" "$S/pq"
for f in "$S"/pq/day/gnss_raw-0.parquet "$S"/pq/day/imu-0.parquet; do java -Xmx4g -cp "$S/jvm:$LIB:$CORE" ReadBench "$f"; done
$PY "$HERE/imu_bin.py" "$S/t" "$S"
run Proto "$S" 2000 -1
run Proto "$S" 2000 1
run Proto "$S" 2000 -1 fsync
