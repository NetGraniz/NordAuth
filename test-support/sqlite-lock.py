"""Hold an exclusive lock on a synthetic test database until the harness releases it."""
import pathlib
import sqlite3
import sys

database = pathlib.Path(sys.argv[1]).resolve()
test_root = pathlib.Path(sys.argv[2]).resolve()
if not database.is_relative_to(test_root) or str(database).startswith("\\\\"):
    raise RuntimeError("Refusing to lock a database outside the local test fixture")
with sqlite3.connect(database, timeout=2) as connection:
    connection.execute("BEGIN EXCLUSIVE")
    print("LOCK_READY", flush=True)
    sys.stdin.readline()
    connection.commit()
