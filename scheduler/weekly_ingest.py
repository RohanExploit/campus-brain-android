import os
import sys
import logging
from pathlib import Path
from apscheduler.schedulers.blocking import BlockingScheduler

sys.path.append(str(Path(__file__).resolve().parent.parent))

from pipeline import run_pipeline
from auth.allowlist import AllowlistManager
from utils.logging_config import setup_logging

setup_logging()

def run_incremental_ingest_for_all():
    logging.info("Starting scheduled incremental ingestion for all tenants...")
    mgr = AllowlistManager()

    # Iterate through all configured tenants
    for tenant_id in mgr.allowlist.keys():
        logging.info(f"Running ingestion for {tenant_id}...")
        try:
            run_pipeline(tenant_id)
        except Exception as e:
            logging.error(f"Error during ingestion for {tenant_id}: {e}")

    logging.info("Scheduled ingestion complete.")

if __name__ == "__main__":
    scheduler = BlockingScheduler()

    # Run once a week (e.g., Sunday at 2 AM)
    scheduler.add_job(run_incremental_ingest_for_all, 'cron', day_of_week='sun', hour=2, minute=0)

    logging.info("Scheduler started. Waiting for next cron execution...")

    # Optional: run once on startup for debugging/testing
    if os.environ.get("RUN_INGEST_ON_STARTUP") == "1":
        run_incremental_ingest_for_all()

    try:
        scheduler.start()
    except (KeyboardInterrupt, SystemExit):
        logging.info("Scheduler stopped.")
