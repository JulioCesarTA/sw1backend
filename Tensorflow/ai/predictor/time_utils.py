from datetime import datetime


def naive_dt(dt) -> datetime:
    if isinstance(dt, str):
        try:
            dt = datetime.fromisoformat(dt.replace("Z", ""))
        except Exception:
            return datetime.now()
    if hasattr(dt, "tzinfo") and dt.tzinfo is not None:
        dt = dt.replace(tzinfo=None)
    return dt if isinstance(dt, datetime) else datetime.now()
