"""Fraud-specific tunables.

Platform config (database, kafka, redis, otel, server) is provided by
`payment_platform.config.PlatformSettings` in main.py. This module holds ONLY
fraud-domain thresholds, loaded from environment variables (FRAUD_* prefix).
"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class FraudSettings(BaseSettings):
    """Fraud scoring thresholds."""

    model_config = SettingsConfigDict(env_prefix="FRAUD_", case_sensitive=False)

    high_value_threshold: float = 1000.0     # amount above this → REVIEW
    velocity_threshold: int = 3               # max transactions per window → REJECTED
    velocity_window_seconds: int = 60         # velocity sliding window
    velocity_sweep_every: int = 1000          # prune stale customers every N scores


fraud_settings = FraudSettings()
