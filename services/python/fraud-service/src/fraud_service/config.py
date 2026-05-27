"""Application configuration loaded from environment variables."""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Fraud service configuration."""

    # Service
    service_name: str = "fraud-service"
    debug: bool = False
    cors_origins: list[str] = ["*"]

    # Database
    database_url: str = "postgresql+asyncpg://payment:payment@localhost:5432/fraud_db"

    # Redis
    redis_url: str = "redis://localhost:6379/0"

    # Kafka
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_consumer_group: str = "fraud-service"

    # Observability
    otel_exporter_otlp_endpoint: str = "http://localhost:4317"

    # Fraud-specific
    fraud_score_threshold: float = 0.7
    velocity_window_seconds: int = 300  # 5 minutes
    max_transactions_per_window: int = 10

    model_config = {"env_prefix": "", "case_sensitive": False}


settings = Settings()
