#!/usr/bin/env python3
"""Backfills synthetic release_history rows for one workload, spanning many months,
to exercise the Release History Dashboard's chart and filters with a realistic-looking
trend instead of the handful of same-day rows seed.py produces.

Writes straight into the release_history table (docker exec into the postgres
container, the same way seed.py --reset does) rather than going through the API —
each synthetic "release" only ever exists as history rows sharing a release_id, with
no matching release row, which release_history's schema is explicitly designed to
tolerate (it has no FK to release, precisely so history survives release deletion).
That also means this doesn't need --token.

Deliberately kept separate from seed.py: this produces purely cosmetic historical
volume for chart demos, not the minimal fresh-environment dataset seed.py is for, and
unlike seed.py it's safe to re-run (with --replace) against already-seeded data.

--start-per-week/--end-per-week (default 1 -> 4) describe the release rate you'll
actually see arrive at the *last* stage — production, typically — ramping up over
the window. Each release starts at the first stage the target workload is linked to
and advances one stage at a time; --promotion-rates gives the independent per-release
chance of each step happening (default 1/5 stage1->stage2, 1/3 stage2->stage3, 1/2
stage3->stage4; steps beyond the given list always happen), rolled per release
rather than applied as an exact fraction, so it funnels down realistically instead of
a suspiciously exact ratio. Since only 1/30 of releases survive that funnel by
default, the script scales up how many releases it actually starts at the first
stage (1/(product of the rates) times more) so the *last* stage still lands on the
requested 1 -> 4/week — the printed summary shows both numbers. Months listed in
--exclude-months (default: August, December) additionally block any release from
reaching a stage named "production" that month, regardless of what the promotion
roll said.

Every row this script writes carries a fixed, recognizable created_by marker (see
SYNTHETIC_CREATED_BY below) so --replace can find and remove exactly its own rows
without touching real history from seed.py or the API.
"""

import argparse
import calendar
import random
import shutil
import subprocess
import sys
import uuid
from datetime import datetime, timedelta, timezone

DB_CONTAINER = "postgres"
DB_USER = "dockers"
DB_NAME = "cdrm"

SYNTHETIC_CREATED_BY = uuid.UUID("00000000-0000-0000-0000-00005eed0000")

STAGE_ROW_SPACING = timedelta(hours=1)  # gap between one stage's row and the next, within a release

# Chance a release advances from stage[i] to stage[i+1], independently rolled per
# release. A transition beyond the end of this list always happens (rate 1.0).
DEFAULT_PROMOTION_RATES = [1 / 5, 1 / 3, 1 / 2]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--workload", default="platform-api", help="Workload name to generate history for")
    parser.add_argument("--months", type=int, default=15, help="How many months back the generated window spans")
    parser.add_argument(
        "--start-per-week", type=float, default=1, help="Releases/week reaching the last stage, at the start of the window"
    )
    parser.add_argument(
        "--end-per-week", type=float, default=4, help="Releases/week reaching the last stage, at the end of the window"
    )
    parser.add_argument(
        "--exclude-months",
        default="8,12",
        help="Comma-separated month numbers (1-12) that get no releases into the 'production' stage",
    )
    parser.add_argument(
        "--promotion-rates",
        default=None,
        help="Comma-separated per-release chance [0-1] of advancing stage[i]->stage[i+1] "
        "(default: 0.2,0.3333,0.5 — i.e. 1/5, 1/3, 1/2). Steps beyond the list always happen.",
    )
    parser.add_argument("--seed", type=int, default=None, help="Random seed, for reproducible output across runs")
    parser.add_argument(
        "--replace",
        action="store_true",
        help="Delete this script's previously generated rows for the workload first (matched by created_by)",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print what would be inserted without writing anything")
    return parser.parse_args()


def run_psql(sql: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["docker", "exec", "-i", DB_CONTAINER, "psql", "-v", "ON_ERROR_STOP=1", "-U", DB_USER, "-d", DB_NAME],
        input=sql, text=True, capture_output=True,
    )


def query_rows(sql: str) -> list[list[str]]:
    # -t/-A/-F (tuples-only, unaligned, pipe-separated) as command-line flags rather
    # than \pset meta-commands in the input — \pset echoes a confirmation line to
    # stdout for each setting, which would otherwise end up mixed into the results.
    result = subprocess.run(
        ["docker", "exec", "-i", DB_CONTAINER, "psql", "-t", "-A", "-F", "|", "-U", DB_USER, "-d", DB_NAME],
        input=sql, text=True, capture_output=True,
    )
    if result.returncode != 0:
        print(f"Query failed: {result.stderr}", file=sys.stderr)
        sys.exit(1)
    return [line.split("|") for line in result.stdout.splitlines() if line]


def query_row(sql: str) -> list[str] | None:
    rows = query_rows(sql)
    return rows[0] if rows else None


def sql_str(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def sql_ts(dt: datetime) -> str:
    return sql_str(dt.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S+00"))


def subtract_months(dt: datetime, months: int) -> datetime:
    month_index = dt.month - 1 - months
    year = dt.year + month_index // 12
    month = month_index % 12 + 1
    day = min(dt.day, calendar.monthrange(year, month)[1])
    return dt.replace(year=year, month=month, day=day)


def releases_for_week(week_index: int, total_weeks: int, start_per_week: float, end_per_week: float) -> int:
    if total_weeks <= 1:
        return round(end_per_week)
    fraction = week_index / (total_weeks - 1)
    return round(start_per_week + (end_per_week - start_per_week) * fraction)


def cumulative_promotion_probability(stages: list[tuple[str, str]], promotion_rates: list[float]) -> float:
    """Chance a release, once started, survives all the way to the last stage —
    the product of every transition's rate (1.0 for any transition beyond the end
    of promotion_rates)."""
    probability = 1.0
    for i in range(1, len(stages)):
        probability *= promotion_rates[i - 1] if i - 1 < len(promotion_rates) else 1.0
    return probability


def stages_reached(
    stages: list[tuple[str, str]], promotion_rates: list[float], month: int, exclude_months: set[int]
) -> list[tuple[str, str]]:
    """Every release starts at stages[0]; each subsequent stage is included only if
    the corresponding promotion roll succeeds (rate for transition i defaults to 1.0
    once promotion_rates is exhausted), and never if it's "production" in an excluded
    month."""
    reached = stages[:1]
    for i in range(1, len(stages)):
        rate = promotion_rates[i - 1] if i - 1 < len(promotion_rates) else 1.0
        if random.random() >= rate:
            break
        stage_id, stage_name = stages[i]
        if stage_name == "production" and month in exclude_months:
            break
        reached.append(stages[i])
    return reached


def main() -> None:
    args = parse_args()
    exclude_months = {int(m) for m in args.exclude_months.split(",") if m.strip()}
    promotion_rates = (
        [float(r) for r in args.promotion_rates.split(",") if r.strip()]
        if args.promotion_rates is not None
        else DEFAULT_PROMOTION_RATES
    )
    if args.seed is not None:
        random.seed(args.seed)

    if shutil.which("docker") is None:
        print("Error: 'docker' is required but not installed.", file=sys.stderr)
        sys.exit(1)

    workload_row = query_row(
        f"SELECT w.id, w.name, p.id, p.name FROM workload w JOIN product p ON p.id = w.product_id "
        f"WHERE w.name = {sql_str(args.workload)};"
    )
    if workload_row is None:
        print(f"Error: no workload named '{args.workload}' found.", file=sys.stderr)
        sys.exit(1)
    workload_id, workload_name, product_id, product_name = workload_row

    stage_rows = query_rows(
        f"SELECT s.id, s.name FROM workload_stage ws JOIN stage s ON s.id = ws.stage_id "
        f"WHERE ws.workload_id = {sql_str(workload_id)} ORDER BY s.stage_order;"
    )
    if not stage_rows:
        print(f"Error: workload '{args.workload}' is not linked to any stages.", file=sys.stderr)
        sys.exit(1)
    stages = [(stage_id, stage_name) for stage_id, stage_name in stage_rows]
    print(f"Workload: {workload_name} ({workload_id}), product: {product_name}")
    print(f"Stages, in order: {', '.join(name for _, name in stages)}")
    for i in range(1, len(stages)):
        rate = promotion_rates[i - 1] if i - 1 < len(promotion_rates) else 1.0
        print(f"  {stages[i - 1][1]} -> {stages[i][1]}: {rate:.0%}")

    survival = cumulative_promotion_probability(stages, promotion_rates)
    if survival <= 0:
        print("Error: cumulative promotion probability is zero — the last stage would never be reached.", file=sys.stderr)
        sys.exit(1)
    dev_start_per_week = args.start_per_week / survival
    dev_end_per_week = args.end_per_week / survival
    print(
        f"Target rate at '{stages[-1][1]}': {args.start_per_week:g} -> {args.end_per_week:g}/week "
        f"=> starting {dev_start_per_week:.1f} -> {dev_end_per_week:.1f}/week at '{stages[0][1]}' "
        f"to compensate for the {survival:.1%} funnel."
    )

    if args.replace:
        print("Deleting previously generated rows for this workload...")
        result = run_psql(
            f"DELETE FROM release_history WHERE workload_id = {sql_str(workload_id)} "
            f"AND created_by = {sql_str(str(SYNTHETIC_CREATED_BY))};"
        )
        if result.returncode != 0:
            print(f"Error deleting previous rows: {result.stderr}", file=sys.stderr)
            sys.exit(1)
        print(f"  {result.stdout.strip()}")

    end = datetime.now(timezone.utc).replace(hour=9, minute=0, second=0, microsecond=0)
    start = subtract_months(end, args.months)
    total_weeks = max((end - start).days // 7, 1)

    rows: list[str] = []
    image_version = 100
    total_releases = 0

    for week_index in range(total_weeks):
        week_start = start + timedelta(weeks=week_index)
        count = releases_for_week(week_index, total_weeks, dev_start_per_week, dev_end_per_week)
        for i in range(count):
            release_start = week_start + timedelta(days=(i * 7) // count)
            month = release_start.month
            release_stages = stages_reached(stages, promotion_rates, month, exclude_months)

            release_id = uuid.uuid4()
            image = f"nginx:1.{image_version}-alpine"
            image_version += 1
            total_releases += 1

            for stage_index, (stage_id, stage_name) in enumerate(release_stages):
                ts = release_start + stage_index * STAGE_ROW_SPACING
                action = "CREATED" if stage_index == 0 else "PROMOTED"
                rows.append(
                    "(" + ", ".join([
                        sql_str(str(uuid.uuid4())),
                        sql_str(str(release_id)),
                        sql_str(workload_id),
                        sql_str(product_id),
                        sql_str(product_name),
                        sql_str(workload_name),
                        sql_str(image),
                        sql_str(stage_id),
                        sql_str(stage_name),
                        sql_str(action),
                        sql_ts(ts),
                        "NULL",
                        sql_ts(ts),
                        sql_str(str(SYNTHETIC_CREATED_BY)),
                    ]) + ")"
                )

    print(f"Generated {total_releases} releases across {total_weeks} weeks ({len(rows)} release_history rows).")

    if args.dry_run:
        print("--dry-run given, nothing written.")
        return

    insert_columns = (
        "id, release_id, workload_id, product_id, product_name, workload_name, image, "
        "stage_id, stage_name, action, deployed_at, deploy_error, created_at, created_by"
    )
    statement = f"INSERT INTO release_history ({insert_columns}) VALUES\n" + ",\n".join(rows) + ";\n"
    result = run_psql(statement)
    if result.returncode != 0:
        print(f"Error inserting rows: {result.stderr}", file=sys.stderr)
        sys.exit(1)
    print("Done.")


if __name__ == "__main__":
    main()
