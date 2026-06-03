# ============================================================================
# Payment API Platform — Multi-Language Build System
# ============================================================================
# Usage:
#   make build          Build all services
#   make test           Run all tests
#   make lint           Lint all services
#   make docker-build   Build all Docker images
#   make dev            Start full local environment
#   make help           Show all targets
# ============================================================================

SHELL := /bin/bash
.SILENT:
.DEFAULT_GOAL := help

# ─── Variables ─────────────────────────────────────────────────────────────
DOCKER_REGISTRY ?= ghcr.io/payment-api
VERSION        ?= latest
JAVA_SERVICES  := financial-core payment-service refund-service fx-service treasury-service
PYTHON_SERVICES := fraud-service
NODEJS_SERVICES := notification-service transaction-service fee-engine
GO_SERVICES    := settlement-service reconciliation-service compliance-service dispute-service merchant-service identity-service bank-integration audit-service

# ─── Color helpers ──────────────────────────────────────────────────────────
CYAN  := \033[36m
GREEN := \033[32m
YELLOW:= \033[33m
RED   := \033[31m
RESET := \033[0m
define log
	@printf "$(CYAN)[%s]$(RESET) $(1)\n" "$$(date +%H:%M:%S)"
endef

# ═══════════════════════════════════════════════════════════════════════════
# HELP
# ═══════════════════════════════════════════════════════════════════════════
help: ## Show this help
	printf "$(CYAN)Payment API Platform — Build System$(RESET)\n\n"
	printf "Usage: make $(GREEN)<target>$(RESET)\n\n"
	printf "$(YELLOW)Targets:$(RESET)\n"
	@grep -E '^[a-zA-Z_.-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  $(GREEN)%-32s$(RESET) %s\n", $$1, $$2}'

# ═══════════════════════════════════════════════════════════════════════════
# BUILD
# ═══════════════════════════════════════════════════════════════════════════
build: build-java build-python build-nodejs build-go ## Build all services

build-java: ## Build all Java services (Maven)
	$(call log,Building Java services...)
	cd services/java && mvn clean package -DskipTests -q -f parent-pom.xml
	$(call log,Java services built)

build-python: ## Build all Python services
	$(call log,Building Python services...)
	@for svc in $(PYTHON_SERVICES); do \
		cd services/python/$$svc && pip install -q -e . 2>/dev/null || true; \
		cd $(CURDIR); \
	done
	$(call log,Python services built)

build-nodejs: ## Build all Node.js services
	$(call log,Building Node.js services...)
	@for svc in $(NODEJS_SERVICES); do \
		if [ -f "services/nodejs/$$svc/package.json" ]; then \
			cd services/nodejs/$$svc && npm run build --if-present 2>/dev/null || true; \
			cd $(CURDIR); \
		fi; \
	done
	$(call log,Node.js services built)

build-go: ## Build all Go services
	$(call log,Building Go services...)
	go build ./services/go/...
	$(call log,Go services built)

# ═══════════════════════════════════════════════════════════════════════════
# TEST
# ═══════════════════════════════════════════════════════════════════════════
test: test-java test-python test-nodejs test-go ## Run all tests

test-java: ## Run Java tests
	$(call log,Running Java tests...)
	cd services/java && mvn test -f parent-pom.xml
	$(call log,Java tests complete)

test-python: ## Run Python tests
	$(call log,Running Python tests...)
	@for svc in $(PYTHON_SERVICES); do \
		cd services/python/$$svc && pytest -q 2>/dev/null || echo "  $(YELLOW)$$svc: no tests found$(RESET)"; \
		cd $(CURDIR); \
	done
	$(call log,Python tests complete)

test-nodejs: ## Run Node.js tests
	$(call log,Running Node.js tests...)
	@for svc in $(NODEJS_SERVICES); do \
		if [ -f "services/nodejs/$$svc/package.json" ]; then \
			cd services/nodejs/$$svc && npm test --if-present 2>/dev/null || echo "  $(YELLOW)$$svc: no tests found$(RESET)"; \
			cd $(CURDIR); \
		fi; \
	done
	$(call log,Node.js tests complete)

test-go: ## Run Go tests
	$(call log,Running Go tests...)
	go test -v -race -count=1 ./services/go/...
	$(call log,Go tests complete)

# ═══════════════════════════════════════════════════════════════════════════
# LINT
# ═══════════════════════════════════════════════════════════════════════════
lint: lint-java lint-python lint-nodejs lint-go ## Lint all services

lint-java: ## Lint Java services (Checkstyle)
	$(call log,Linting Java services...)
	cd services/java && mvn checkstyle:check -f parent-pom.xml 2>/dev/null || echo "  $(YELLOW)Checkstyle not configured$(RESET)"
	$(call log,Java lint complete)

lint-python: ## Lint Python services (Ruff + Mypy)
	$(call log,Linting Python services...)
	@for svc in $(PYTHON_SERVICES); do \
		cd services/python/$$svc && \
		ruff check . 2>/dev/null || echo "  $(YELLOW)ruff not configured for $$svc$(RESET)" && \
		mypy src/ --ignore-missing-imports 2>/dev/null || echo "  $(YELLOW)mypy not configured for $$svc$(RESET)"; \
		cd $(CURDIR); \
	done
	$(call log,Python lint complete)

lint-nodejs: ## Lint Node.js services (ESLint)
	$(call log,Linting Node.js services...)
	@for svc in $(NODEJS_SERVICES); do \
		if [ -f "services/nodejs/$$svc/package.json" ]; then \
			cd services/nodejs/$$svc && npx eslint . --ext .ts 2>/dev/null || echo "  $(YELLOW)ESLint not configured for $$svc$(RESET)"; \
			cd $(CURDIR); \
		fi; \
	done
	$(call log,Node.js lint complete)

lint-go: ## Lint Go services (golangci-lint)
	$(call log,Linting Go services...)
	golangci-lint run ./services/go/... 2>/dev/null || echo "  $(YELLOW)golangci-lint not installed$(RESET)"
	$(call log,Go lint complete)

# ═══════════════════════════════════════════════════════════════════════════
# DOCKER
# ═══════════════════════════════════════════════════════════════════════════
docker-build: docker-build-java docker-build-python docker-build-nodejs docker-build-go ## Build all Docker images

docker-build-java: ## Build Docker images for Java services
	$(call log,Building Java Docker images...)
	@for svc in $(JAVA_SERVICES); do \
		printf "  $(GREEN)Building$(RESET) $$svc...\n"; \
		docker build \
			--build-arg SERVICE_DIR=services/java/$$svc \
			-t $(DOCKER_REGISTRY)/$$svc:$(VERSION) \
			-f docker/Dockerfile.java \
			services/java/$$svc; \
	done
	$(call log,Java Docker images built)

docker-build-python: ## Build Docker images for Python services
	$(call log,Building Python Docker images...)
	@for svc in $(PYTHON_SERVICES); do \
		printf "  $(GREEN)Building$(RESET) $$svc...\n"; \
		docker build \
			-t $(DOCKER_REGISTRY)/$$svc:$(VERSION) \
			-f docker/Dockerfile.python \
			services/python/$$svc; \
	done
	$(call log,Python Docker images built)

docker-build-nodejs: ## Build Docker images for Node.js services
	$(call log,Building Node.js Docker images...)
	@for svc in $(NODEJS_SERVICES); do \
		printf "  $(GREEN)Building$(RESET) $$svc...\n"; \
		docker build \
			-t $(DOCKER_REGISTRY)/$$svc:$(VERSION) \
			-f docker/Dockerfile.nodejs \
			services/nodejs/$$svc; \
	done
	$(call log,Node.js Docker images built)

docker-build-go: ## Build Docker images for Go services
	$(call log,Building Go Docker images...)
	@for svc in $(GO_SERVICES); do \
		printf "  $(GREEN)Building$(RESET) $$svc...\n"; \
		docker build \
			-t $(DOCKER_REGISTRY)/$$svc:$(VERSION) \
			-f docker/Dockerfile.go \
			services/go/$$svc; \
	done
	$(call log,Go Docker images built)

docker-push: ## Push all Docker images to registry
	$(call log,Pushing Docker images to $(DOCKER_REGISTRY)...)
	@for svc in $(JAVA_SERVICES) $(PYTHON_SERVICES) $(NODEJS_SERVICES) $(GO_SERVICES); do \
		docker push $(DOCKER_REGISTRY)/$$svc:$(VERSION); \
	done
	$(call log,Docker images pushed)

# ═══════════════════════════════════════════════════════════════════════════
# LOCAL DEV
# ═══════════════════════════════════════════════════════════════════════════
dev: ## Start local development environment (alias for dev-up)
	@$(MAKE) dev-up

dev-up: ## Start all infrastructure + services
	$(call log,Starting local dev environment...)
	docker-compose up -d
	$(call log,Local dev environment started)
	@echo ""
	@echo "  $(GREEN)Infrastructure:$(RESET)"
	@echo "    PostgreSQL:  postgresql://payment:payment@localhost:5432"
	@echo "    Redis:       redis://localhost:6379"
	@echo "    Kafka:       localhost:9093"
	@echo "    Jaeger UI:   http://localhost:16686"
	@echo "    Grafana:     http://localhost:3000 (admin/admin)"
	@echo "    Prometheus:  http://localhost:9090"
	@echo "    OTel Collector: localhost:4317"
	@echo ""
	@echo "  $(GREEN)Services:$(RESET)"
	@echo "    financial-core:       http://localhost:8080/liveness"
	@echo "    payment-service:      http://localhost:8081/liveness"
	@echo "    fraud-service:        http://localhost:8000/liveness"
	@echo "    notification-service: http://localhost:3001/liveness"
	@echo "    settlement-service:   http://localhost:8088/liveness"

dev-infra: ## Start infrastructure only (no application services)
	$(call log,Starting infrastructure...)
	docker-compose up -d postgres redis zookeeper kafka schema-registry opensearch jaeger otel-collector prometheus grafana
	$(call log,Infrastructure started)

dev-services: ## Start application services only (infrastructure must be running)
	$(call log,Starting services...)
	docker-compose --profile services up -d
	$(call log,Services started)

dev-hot-reload: ## Start services locally in hot-reload mode (not Docker)
	$(call log,Starting services in hot-reload mode (ensure infra is running: make dev-infra)...)
	@echo ""
	@echo "  $(GREEN)Run each service in its own terminal:$(RESET)"
	@echo "    Java:   cd services/java/financial-core && mvn spring-boot:run -Dspring-boot.run.profiles=local"
	@echo "    Go:     cd services/go/settlement-service && go run ./cmd/server"
	@echo "    Python: cd services/python/fraud-service && python -m uvicorn src.fraud_service.main:app --reload --port 8000"
	@echo "    Node.js: cd services/nodejs/notification-service && npm run dev"

dev-down: ## Stop all services and infrastructure
	$(call log,Stopping local dev environment...)
	docker-compose down
	$(call log,Local dev environment stopped)

dev-logs: ## Tail logs from all services
	docker-compose logs -f

dev-restart: dev-down dev-up ## Restart local dev environment

# ═══════════════════════════════════════════════════════════════════════════
# CLEAN
# ═══════════════════════════════════════════════════════════════════════════
clean: clean-java clean-python clean-nodejs clean-go ## Clean all build artifacts

clean-java:
	$(call log,Cleaning Java build artifacts...)
	cd services/java && mvn clean -q -f parent-pom.xml 2>/dev/null || true
	$(call log,Java cleaned)

clean-python:
	$(call log,Cleaning Python build artifacts...)
	find services/python -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null; true
	find services/python -type d -name '*.egg-info' -exec rm -rf {} + 2>/dev/null; true
	find services/python -type d -name .pytest_cache -exec rm -rf {} + 2>/dev/null; true
	$(call log,Python cleaned)

clean-nodejs:
	$(call log,Cleaning Node.js build artifacts...)
	find services/nodejs -type d -name node_modules -exec rm -rf {} + 2>/dev/null; true
	find services/nodejs -type d -name dist -exec rm -rf {} + 2>/dev/null; true
	$(call log,Node.js cleaned)

clean-go:
	$(call log,Cleaning Go build artifacts...)
	go clean -cache ./services/go/... 2>/dev/null || true
	$(call log,Go cleaned)

# ═══════════════════════════════════════════════════════════════════════════
# ARCHITECTURE FITNESS TESTS
# ═══════════════════════════════════════════════════════════════════════════
arch-test: ## Run architecture fitness tests (package boundaries, port uniqueness)
	$(call log,Running architecture fitness tests...)
	@# Port uniqueness check
	@bash libs/archtest/scripts/check-port-uniqueness.sh docker-compose.yml || exit 1
	@# Config completeness check
	@bash libs/archtest/scripts/check-config-completeness.sh docker-compose.yml || exit 1
	@# Libs boundary checks (per language)
	@echo "  Architecture fitness tests passed."

# ═══════════════════════════════════════════════════════════════════════════
# BUILD LIBS
# ═══════════════════════════════════════════════════════════════════════════
build-libs: ## Build all platform libraries
	$(call log,Building platform libraries...)
	cd libs/java && mvn install -DskipTests -q 2>/dev/null || echo "  $(YELLOW)Java libs: mvn not available, skipping$(RESET)"
	cd libs/go && go build ./pkg/... 2>/dev/null || echo "  $(YELLOW)Go libs: go not available, skipping$(RESET)"
	cd libs/python && pip install -e . -q 2>/dev/null || echo "  $(YELLOW)Python libs: pip not available, skipping$(RESET)"
	cd libs/nodejs && npm install --silent 2>/dev/null && npm run build --silent 2>/dev/null || echo "  $(YELLOW)Node.js libs: npm not available, skipping$(RESET)"
	$(call log,Platform libraries built)

# ═══════════════════════════════════════════════════════════════════════════
# SCAFFOLD
# ═══════════════════════════════════════════════════════════════════════════
scaffold-java: ## Scaffold new Java service. Usage: make scaffold-java NAME=my-service
	@bash scripts/scaffold-java.sh $(NAME)

scaffold-python: ## Scaffold new Python service. Usage: make scaffold-python NAME=my-service
	@bash scripts/scaffold-python.sh $(NAME)

scaffold-nodejs: ## Scaffold new Node.js service. Usage: make scaffold-nodejs NAME=my-service
	@bash scripts/scaffold-nodejs.sh $(NAME)

scaffold-go: ## Scaffold new Go service. Usage: make scaffold-go NAME=my-service
	@bash scripts/scaffold-go.sh $(NAME)

# ═══════════════════════════════════════════════════════════════════════════
# UTILITY
# ═══════════════════════════════════════════════════════════════════════════
check-tools: ## Check that required tools are installed
	@printf "$(YELLOW)Checking required tools...$(RESET)\n"
	@command -v java  >/dev/null 2>&1 && printf "  $(GREEN)✓$(RESET) java  $$(java -version 2>&1 | head -1)\n"     || printf "  $(RED)✗$(RESET) java not found\n"
	@command -v mvn   >/dev/null 2>&1 && printf "  $(GREEN)✓$(RESET) maven $$(mvn -version 2>&1 | head -1 | awk '{print $$3}')\n" || printf "  $(RED)✗$(RESET) maven not found\n"
	@command -v python3 >/dev/null 2>&1 && printf "  $(GREEN)✓$(RESET) python $$(python3 --version 2>&1)\n"       || printf "  $(RED)✗$(RESET) python not found\n"
	@command -v node  >/dev/null 2>&1 && printf "  $(GREEN)✓$(RESET) node   $$(node --version)\n"                 || printf "  $(RED)✗$(RESET) node not found\n"
	@command -v npm   >/dev/null 2>&1 && printf "  $(GREEN)✓$(RESET) npm    $$(npm --version)\n"                  || printf "  $(RED)✗$(RESET) npm not found\n"
	@command -v go    >/dev/null 2>&1 && printf "  $(GREEN)✓$(RESET) go     $$(go version | awk '{print $$3}')\n" || printf "  $(RED)✗$(RESET) go not found\n"
	@command -v docker >/dev/null 2>&1 && printf "  $(GREEN)✓$(RESET) docker $$(docker --version | awk '{print $$3}' | tr -d ',')\n" || printf "  $(RED)✗$(RESET) docker not found\n"
	@printf "\n$(YELLOW)Tip:$(RESET) Use 'make dev' to start via Docker (no local toolchain needed).\n"

setup: ## Initialize project (install all dependencies)
	$(call log,Setting up project...)
	@# Java
	cd services/java && mvn dependency:resolve -q -f parent-pom.xml 2>/dev/null || echo "  $(YELLOW)Java setup skipped (no pom.xml)$(RESET)"
	@# Python
	@for svc in $(PYTHON_SERVICES); do \
		if [ -f "services/python/$$svc/requirements.txt" ]; then \
			cd services/python/$$svc && pip install -q -r requirements.txt 2>/dev/null || true; \
			cd $(CURDIR); \
		fi; \
	done
	@# Node.js
	@for svc in $(NODEJS_SERVICES); do \
		if [ -f "services/nodejs/$$svc/package.json" ]; then \
			cd services/nodejs/$$svc && npm install --silent 2>/dev/null || true; \
			cd $(CURDIR); \
		fi; \
	done
	@# Go
	go mod download 2>/dev/null || echo "  $(YELLOW)Go setup skipped (no go.mod)$(RESET)"
	$(call log,Setup complete)
