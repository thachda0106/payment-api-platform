"""
Typed, validated, modular configuration from environment variables.

Mandatory (always validated):
    server (port, host), logging (level, format), otel (exporter_endpoint, service_name, service_version)

Optional (validated only when configured):
    database (url, max_pool_size, min_idle), kafka (bootstrap_servers, consumer_group), redis (url)

All config is loaded from environment variables: SERVER_PORT, DATABASE_URL, KAFKA_BOOTSTRAP_SERVERS,
REDIS_URL, OTEL_EXPORTER_OTLP_ENDPOINT, OTEL_SERVICE_NAME, LOG_LEVEL, LOG_FORMAT.
"""

from pydantic_settings import BaseSettings, SettingsConfigDict
from typing import Optional


class ServerSettings(BaseSettings):
    port: int = 8080
    host: str = "0.0.0.0"


class LoggingSettings(BaseSettings):
    level: str = "info"   # debug, info, warn, error
    format: str = "json"  # json, text


class OtelSettings(BaseSettings):
    exporter_endpoint: str = ""
    service_name: str = "unknown"
    service_version: str = "0.1.0"


class DatabaseSettings(BaseSettings):
    url: str = ""
    max_pool_size: int = 10
    min_idle: int = 2


class KafkaSettings(BaseSettings):
    bootstrap_servers: str = ""
    consumer_group: str = "default"


class RedisSettings(BaseSettings):
    url: str = ""


class PlatformSettings(BaseSettings):
    """Root configuration. Optional modules are None if not configured."""
    model_config = SettingsConfigDict(env_prefix="", case_sensitive=False)

    server: ServerSettings = ServerSettings()
    logging: LoggingSettings = LoggingSettings()
    otel: OtelSettings = OtelSettings()
    database: Optional[DatabaseSettings] = None
    kafka: Optional[KafkaSettings] = None
    redis: Optional[RedisSettings] = None

    @classmethod
    def load(cls) -> "PlatformSettings":
        """Load config from env vars. Optional modules auto-detected."""
        settings = cls()

        # Auto-detect optional modules from env vars
        import os
        if os.getenv("DATABASE_URL"):
            settings.database = DatabaseSettings()
        if os.getenv("KAFKA_BOOTSTRAP_SERVERS"):
            settings.kafka = KafkaSettings()
        if os.getenv("REDIS_URL"):
            settings.redis = RedisSettings()

        return settings

    def validate_mandatory(self) -> None:
        """Fail fast if mandatory config is missing."""
        if not self.otel.exporter_endpoint:
            raise ValueError(
                "OTEL_EXPORTER_OTLP_ENDPOINT is required. "
                "Set the environment variable to point to the OTel collector."
            )
        if not self.otel.service_name or self.otel.service_name == "unknown":
            raise ValueError(
                "OTEL_SERVICE_NAME is required. "
                "Set the environment variable with the service name."
            )
