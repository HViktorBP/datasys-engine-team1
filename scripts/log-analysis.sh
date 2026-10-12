#!/usr/bin/env bash
# Produces reproducible Exercise 5 evidence without modifying existing tables or logs.
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "$0")/.." && pwd)"
launcher="$repo_root/engine"
demo_dir="$(mktemp -d "${TMPDIR:-/tmp}/engine-log-analysis.XXXXXX")"
cd -- "$demo_dir"

cat > trips.csv <<'CSV'
Odense,95,120.75
Aarhus,187,301.0
Copenhagen,12,23.5
CSV
cat > failing-session.sql <<'SQL'
CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
COPY trips FROM 'trips.csv';
SELECT * FROM trips WHERE city = 'Odense';
SELECT * FROM trips WHERE distance > 100;
SELECT * FROM trips WHERE price < 50.0;
SELECT * FROM trips;
SELECT * FROM missing_log_demo;
CREATE TABLE should_not_run (id LONG);
SQL
if "$launcher" -f failing-session.sql > failing-session.csv 2> failing-session.stderr; then
    echo 'Expected statement 7 to fail.' >&2
    exit 1
else
    status=$?
    if [[ "$status" -ne 1 ]]; then
        cat failing-session.stderr >&2
        exit "$status"
    fi
fi
cat > successful-session.sql <<'SQL'
SELECT * FROM trips WHERE city = 'Odense';
SELECT * FROM trips WHERE distance > 100;
SQL
"$launcher" -f successful-session.sql > successful-session.csv 2> successful-session.stderr
session_id="$(awk -F, '$5 == "ERROR" && index($7, "missing_log_demo") { print $2; exit }' logs/engine.log)"
if [[ -z "$session_id" ]]; then
    echo 'The failing statement left no ERROR record.' >&2
    exit 1
fi

cat > ingest.sql <<'SQL'
CREATE TABLE logs (timestamp STRING, sessionId STRING, statementNumber LONG, threadId LONG,
                   logLevel STRING, className STRING, logMessage STRING);
COPY logs FROM 'logs/engine.log';
SQL
"$launcher" -f ingest.sql > ingest.csv 2> ingest.stderr
printf "SELECT * FROM logs WHERE sessionId = '%s';\n" "$session_id" > session.sql
printf 'SELECT * FROM logs WHERE statementNumber = 7;\n' > statement-7.sql
printf "SELECT * FROM logs WHERE logLevel = 'ERROR';\n" > errors.sql
for analysis in session statement-7 errors; do
    "$launcher" -f "$analysis.sql" > "$analysis.csv" 2> "$analysis.stderr"
    test -s "$analysis.csv"
done
printf 'Evidence directory: %s\nSession: %s\n' "$demo_dir" "$session_id"
printf 'Session rows: %s\nStatement 7 rows: %s\nERROR rows: %s\n' \
    "$(wc -l < session.csv)" "$(wc -l < statement-7.csv)" "$(wc -l < errors.csv)"
