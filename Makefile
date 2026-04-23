# MAP Android Proxy - Docker Build System
# ==========================================

# Configuration
PROJECT_DIR := $(shell pwd)
CACHE_DIR := ./cache
BUILD_DIR := $(CACHE_DIR)/build
GRADLE_CACHE := $(CACHE_DIR)/gradle
PROJECT_CACHE_DIR := /tmp/gradle-project-cache

# Printed at the end of successful build/test targets (host local time)
BUILD_FINISHED = @echo "🕐 Сборка завершена: $$(date '+%Y-%m-%d %H:%M:%S %z')"
# Avoid printing the timestamp 4× when `make all` runs build-debug/release/bundle-* as prerequisites
ifneq ($(filter all,$(MAKECMDGOALS)),all)
BUILD_FINISHED_OR_EMPTY = $(BUILD_FINISHED)
else
BUILD_FINISHED_OR_EMPTY =
endif

# Docker settings
IMAGE_NAME := map-android-proxy-builder
DOCKERFILE := ./docker/dockerfile

# ==============================================
# Default target
# ==============================================

.DEFAULT_GOAL := help

# ==============================================
# Docker Image
# ==============================================

.PHONY: docker-image
docker-image:
	@echo "🐳 Building Docker image..."
	@docker build --platform linux/amd64 -t $(IMAGE_NAME) -f $(DOCKERFILE) .
	@echo "✅ Docker image '$(IMAGE_NAME)' built successfully"
	$(BUILD_FINISHED)

# Check if image exists, build if not
.PHONY: ensure-image
ensure-image:
	@docker image inspect $(IMAGE_NAME) >/dev/null 2>&1 || $(MAKE) docker-image

# ==============================================
# Build Targets
# ==============================================

.PHONY: build build-debug build-release test
build: build-debug

test: ensure-image
	@echo "🧪 Running unit tests (Debug)..."
	@mkdir -p $(GRADLE_CACHE)
	@docker run --rm --platform linux/amd64 \
		-v $(PROJECT_DIR):/src \
		-v $(PROJECT_DIR)/$(GRADLE_CACHE):/root/.gradle \
		-w /src \
		$(IMAGE_NAME) \
		--project-cache-dir $(PROJECT_CACHE_DIR) \
		testDebugUnitTest
	$(BUILD_FINISHED)

build-debug: ensure-image
	@echo "🔨 Building DEBUG APK..."
	@mkdir -p $(BUILD_DIR) $(GRADLE_CACHE)
	@docker run --rm --platform linux/amd64 \
		-v $(PROJECT_DIR):/src \
		-v $(PROJECT_DIR)/$(GRADLE_CACHE):/root/.gradle \
		-w /src \
		$(IMAGE_NAME) \
		--project-cache-dir $(PROJECT_CACHE_DIR) \
		assembleDebug
	@if [ -f app/build/outputs/apk/debug/app-debug.apk ]; then \
		cp app/build/outputs/apk/debug/app-debug.apk $(BUILD_DIR)/; \
		echo "✅ APK saved: $(BUILD_DIR)/app-debug.apk"; \
	fi
	$(BUILD_FINISHED_OR_EMPTY)

build-release: ensure-image
	@echo "🔨 Building RELEASE APK..."
	@mkdir -p $(BUILD_DIR) $(GRADLE_CACHE)
	@docker run --rm --platform linux/amd64 \
		-v $(PROJECT_DIR):/src \
		-v $(PROJECT_DIR)/$(GRADLE_CACHE):/root/.gradle \
		-w /src \
		$(IMAGE_NAME) \
		--project-cache-dir $(PROJECT_CACHE_DIR) \
		assembleRelease
	@if [ -f app/build/outputs/apk/release/app-release.apk ]; then \
		cp app/build/outputs/apk/release/app-release.apk $(BUILD_DIR)/; \
		echo "✅ APK saved: $(BUILD_DIR)/app-release.apk"; \
	elif [ -f app/build/outputs/apk/release/app-release-unsigned.apk ]; then \
		cp app/build/outputs/apk/release/app-release-unsigned.apk $(BUILD_DIR)/app-release.apk; \
		echo "✅ APK saved: $(BUILD_DIR)/app-release.apk (from unsigned build)"; \
	else \
		echo "❌ Release APK not found in app/build/outputs/apk/release"; \
		exit 1; \
	fi
	$(BUILD_FINISHED_OR_EMPTY)

# ==============================================
# Bundle Targets (AAB)
# ==============================================

.PHONY: bundle bundle-debug bundle-release
bundle: bundle-debug

bundle-debug: ensure-image
	@echo "📦 Building DEBUG AAB..."
	@mkdir -p $(BUILD_DIR) $(GRADLE_CACHE)
	@docker run --rm --platform linux/amd64 \
		-v $(PROJECT_DIR):/src \
		-v $(PROJECT_DIR)/$(GRADLE_CACHE):/root/.gradle \
		-w /src \
		$(IMAGE_NAME) \
		--project-cache-dir $(PROJECT_CACHE_DIR) \
		bundleDebug
	@echo "✅ Debug bundle built"
	$(BUILD_FINISHED_OR_EMPTY)

bundle-release: ensure-image
	@echo "📦 Building RELEASE AAB..."
	@mkdir -p $(BUILD_DIR) $(GRADLE_CACHE)
	@docker run --rm --platform linux/amd64 \
		-v $(PROJECT_DIR):/src \
		-v $(PROJECT_DIR)/$(GRADLE_CACHE):/root/.gradle \
		-w /src \
		$(IMAGE_NAME) \
		--project-cache-dir $(PROJECT_CACHE_DIR) \
		bundleRelease
	@echo "✅ Release bundle built"
	$(BUILD_FINISHED_OR_EMPTY)

# ==============================================
# Build All
# ==============================================

.PHONY: all
all: build-debug build-release bundle-debug bundle-release
	@echo "✅ All builds completed"
	$(BUILD_FINISHED)

# ==============================================
# Clean Targets
# ==============================================

.PHONY: clean clean-build clean-cache clean-all

clean:
	@echo "🧹 Cleaning build outputs..."
	@rm -rf app/build build .gradle
	@rm -f $(BUILD_DIR)/*.apk $(BUILD_DIR)/*.aab 2>/dev/null || true
	@echo "✅ Clean completed"

clean-cache:
	@echo "🧹 Cleaning Gradle cache..."
	@rm -rf $(GRADLE_CACHE)
	@echo "✅ Cache cleaned"

clean-all: clean clean-cache
	@echo "🧹 Removing Docker image..."
	@docker rmi $(IMAGE_NAME) 2>/dev/null || true
	@rm -rf $(CACHE_DIR)
	@echo "✅ Full clean completed"

# ==============================================
# Utility
# ==============================================

.PHONY: shell
shell: ensure-image
	@echo "🐚 Opening shell in container..."
	@docker run --rm -it --platform linux/amd64 \
		-v $(PROJECT_DIR):/src \
		-v $(PROJECT_DIR)/$(GRADLE_CACHE):/root/.gradle \
		-w /src \
		--entrypoint /bin/bash \
		$(IMAGE_NAME)

.PHONY: deps
deps: ensure-image
	@echo "📥 Downloading dependencies..."
	@mkdir -p $(GRADLE_CACHE)
	@docker run --rm --platform linux/amd64 \
		-v $(PROJECT_DIR):/src \
		-v $(PROJECT_DIR)/$(GRADLE_CACHE):/root/.gradle \
		-w /src \
		$(IMAGE_NAME) \
		--project-cache-dir $(PROJECT_CACHE_DIR) \
		dependencies
	@echo "✅ Dependencies downloaded"
	$(BUILD_FINISHED)

# ==============================================
# Help
# ==============================================

.PHONY: help
help:
	@echo ""
	@echo "  MAP Android Proxy - Docker Build System"
	@echo "  ========================================"
	@echo ""
	@echo "  🔨 BUILD"
	@echo "     make build           Build debug APK"
	@echo "     make build-debug     Build debug APK"
	@echo "     make build-release   Build release APK"
	@echo ""
	@echo "  📦 BUNDLE (AAB)"
	@echo "     make bundle          Build debug AAB"
	@echo "     make bundle-release  Build release AAB"
	@echo ""
	@echo "  🐳 DOCKER"
	@echo "     make docker-image    Build Docker image"
	@echo "     make shell           Open shell in container"
	@echo ""
	@echo "  🧹 CLEAN"
	@echo "     make clean           Clean build outputs"
	@echo "     make clean-cache     Clean Gradle cache"
	@echo "     make clean-all       Clean everything + Docker image"
	@echo ""
	@echo "  📂 Output: $(BUILD_DIR)/"
	@echo ""
