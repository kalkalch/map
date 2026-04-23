#!/bin/bash
# Build Script for MAP Android Proxy with Docker
# ==========================================

set -e  # Exit on error

# Configuration
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CACHE_DIR="${CACHE_DIR:-$PROJECT_DIR/cache}"
BUILD_ARTIFACT_DIR="${CACHE_DIR}/build"
PROJECT_CACHE_DIR="/tmp/gradle-project-cache"
IMAGE_NAME="map-android-proxy-builder"
DOCKERFILE_PATH="$PROJECT_DIR/docker/dockerfile"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

# Check if Docker is installed
check_docker() {
    if ! command -v docker &> /dev/null; then
        log_error "Docker is not installed. Please install Docker first."
        exit 1
    fi
    
    if ! docker info &> /dev/null; then
        log_error "Docker daemon is not running. Please start Docker."
        exit 1
    fi
}

# Build Docker image
build_image() {
    log_info "Building Docker image: $IMAGE_NAME..."
    docker build -t "$IMAGE_NAME" -f "$DOCKERFILE_PATH" "$PROJECT_DIR"
    log_info "✅ Docker image built successfully"
}

# Build APK
do_build() {
    local build_type="${1:-debug}"
    
    check_docker
    
    # Check if image exists, build if not
    if ! docker image inspect "$IMAGE_NAME" &> /dev/null; then
        log_warn "Docker image not found. Building..."
        build_image
    fi
    
    # Create output directory
    mkdir -p "$BUILD_ARTIFACT_DIR"
    
    log_info "Building APK in ${build_type} mode..."
    
    docker run --rm \
        -v "$PROJECT_DIR":/src \
        -v "$CACHE_DIR/gradle":/root/.gradle \
        -w /src \
        "$IMAGE_NAME" \
        --project-cache-dir "$PROJECT_CACHE_DIR" \
        "assemble${build_type^}"
    
    # Copy artifacts
    local apk_dir="$PROJECT_DIR/app/build/outputs/apk/$build_type"
    local apk_file="$apk_dir/app-$build_type.apk"
    local unsigned_apk_file="$apk_dir/app-$build_type-unsigned.apk"
    local output_apk="$BUILD_ARTIFACT_DIR/app-$build_type.apk"

    if [ -f "$apk_file" ]; then
        cp "$apk_file" "$output_apk"
        log_info "✅ APK saved to: $output_apk"
    elif [ -f "$unsigned_apk_file" ]; then
        cp "$unsigned_apk_file" "$output_apk"
        log_info "✅ APK saved to: $output_apk (from unsigned build)"
    else
        log_error "APK not found in: $apk_dir"
        exit 1
    fi
    
    log_info "✅ Build completed!"
}

# Build AAB (Android App Bundle)
do_build_bundle() {
    local build_type="${1:-debug}"
    
    check_docker
    
    if ! docker image inspect "$IMAGE_NAME" &> /dev/null; then
        log_warn "Docker image not found. Building..."
        build_image
    fi
    
    mkdir -p "$BUILD_ARTIFACT_DIR"
    
    log_info "Building AAB in ${build_type} mode..."
    
    docker run --rm \
        -v "$PROJECT_DIR":/src \
        -v "$CACHE_DIR/gradle":/root/.gradle \
        -w /src \
        "$IMAGE_NAME" \
        --project-cache-dir "$PROJECT_CACHE_DIR" \
        "bundle${build_type^}"
    
    log_info "✅ Bundle build completed!"
}

# Clean build artifacts
do_clean() {
    log_info "Cleaning build artifacts..."
    
    rm -rf "$BUILD_ARTIFACT_DIR" 2>/dev/null || true
    rm -rf "$PROJECT_DIR/app/build" 2>/dev/null || true
    rm -rf "$PROJECT_DIR/build" 2>/dev/null || true
    rm -rf "$PROJECT_DIR/.gradle" 2>/dev/null || true
    
    mkdir -p "$BUILD_ARTIFACT_DIR"
    log_info "✅ Clean completed"
}

# Clean everything including Docker image
do_clean_all() {
    do_clean
    
    if docker image inspect "$IMAGE_NAME" &> /dev/null; then
        log_info "Removing Docker image..."
        docker rmi "$IMAGE_NAME" 2>/dev/null || true
    fi
    
    rm -rf "$CACHE_DIR/gradle" 2>/dev/null || true
    log_info "✅ Full clean completed"
}

# Show help
show_help() {
    echo "MAP Android Proxy - Build Script"
    echo ""
    echo "Usage: $0 <command> [options]"
    echo ""
    echo "Commands:"
    echo "  build [debug|release]   Build APK (default: debug)"
    echo "  bundle [debug|release]  Build AAB bundle"
    echo "  image                   Build Docker image"
    echo "  clean                   Clean build artifacts"
    echo "  clean-all               Clean everything including Docker image"
    echo "  help                    Show this help"
    echo ""
    echo "Examples:"
    echo "  $0 build               # Build debug APK"
    echo "  $0 build release       # Build release APK"
    echo "  $0 bundle release      # Build release AAB"
    echo "  $0 image               # Rebuild Docker image"
}

# Main
cd "$PROJECT_DIR"

case "$1" in
    build)
        do_build "${2:-debug}"
        ;;
    bundle)
        do_build_bundle "${2:-debug}"
        ;;
    image)
        check_docker
        build_image
        ;;
    clean)
        do_clean
        ;;
    clean-all)
        do_clean_all
        ;;
    help|--help|-h|"")
        show_help
        ;;
    *)
        log_error "Unknown command: $1"
        show_help
        exit 1
        ;;
esac
