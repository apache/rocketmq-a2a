#!/usr/bin/env python3
"""
Build script for creating three independent deployable packages:
1. supervisor-agent (includes common + supervisor_agent)
2. travel-agent (includes common + travel_agent)
3. weather-agent (includes common + weather_agent)

All packages share a single .env file at the root level.
"""

import shutil
import sys
from pathlib import Path

# Project root directory
PROJECT_ROOT = Path(__file__).parent
BUILD_DIR = PROJECT_ROOT / "dist"


def clean_build_dir():
    """Clean the build directory"""
    if BUILD_DIR.exists():
        shutil.rmtree(BUILD_DIR)
    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    print("✓ Cleaned build directory")


def create_package_structure(package_name: str, modules: list):
    """
    Create package structure with selected modules

    Args:
        package_name: Name of the package
        modules: List of module directories to include
    """
    package_dir = BUILD_DIR / package_name
    package_dir.mkdir(parents=True, exist_ok=True)

    # Copy modules
    for module in modules:
        src = PROJECT_ROOT / module
        dst = package_dir / module
        if src.exists():
            shutil.copytree(src, dst, dirs_exist_ok=True)
            print(f"  ✓ Copied {module}")
        else:
            print(f"  ✗ Warning: {module} not found")

    # Copy requirements.txt
    shutil.copy2(PROJECT_ROOT / "requirements.txt", package_dir / "requirements.txt")
    print("  ✓ Copied requirements.txt")

    return package_dir


def create_supervisor_package():
    """Create supervisor-agent package"""
    print("\n📦 Building supervisor-agent package...")
    package_dir = create_package_structure(
        "supervisor-agent",
        ["common", "supervisor_agent"]
    )

    # Create startup script
    startup_script = package_dir / "start.sh"
    startup_script.write_text("""#!/bin/bash
# Supervisor Agent Startup Script

set -e

echo "Starting Supervisor Agent..."

# Install dependencies using uv (faster and better dependency resolution)
if ! command -v uv &> /dev/null; then
    echo "Installing uv..."
    pip install uv
fi

if [ ! -d "venv" ]; then
    echo "Creating virtual environment with uv..."
    uv venv venv
fi

source venv/bin/activate
uv pip install -r requirements.txt

# Load environment variables from shared .env file
if [ -f "../.env" ]; then
    set -a
    source ../.env
    set +a
    echo "✓ Loaded environment variables from ../.env"
fi

# Start the application
python -m supervisor_agent.supervisor_agent_start
""")
    startup_script.chmod(0o755)
    print("  ✓ Created start.sh")

    print(f"✓ supervisor-agent package created at: {package_dir}")
    return package_dir


def create_travel_package():
    """Create travel-agent package"""
    print("\n📦 Building travel-agent package...")
    package_dir = create_package_structure(
        "travel-agent",
        ["common", "travel_agent"]
    )

    # Create startup script
    startup_script = package_dir / "start.sh"
    startup_script.write_text("""#!/bin/bash
# Travel Agent Startup Script

set -e

echo "Starting Travel Agent..."

# Install dependencies using uv (faster and better dependency resolution)
if ! command -v uv &> /dev/null; then
    echo "Installing uv..."
    pip install uv
fi

if [ ! -d "venv" ]; then
    echo "Creating virtual environment with uv..."
    uv venv venv
fi

source venv/bin/activate
uv pip install -r requirements.txt

# Load environment variables from shared .env file
if [ -f "../.env" ]; then
    set -a
    source ../.env
    set +a
    echo "✓ Loaded environment variables from ../.env"
fi

# Start the application
python -m travel_agent.travel_agent_start
""")
    startup_script.chmod(0o755)
    print("  ✓ Created start.sh")

    print(f"✓ travel-agent package created at: {package_dir}")
    return package_dir


def create_weather_package():
    """Create weather-agent package"""
    print("\n📦 Building weather-agent package...")
    package_dir = create_package_structure(
        "weather-agent",
        ["common", "weather_agent"]
    )

    # Create startup script
    startup_script = package_dir / "start.sh"
    startup_script.write_text("""#!/bin/bash
# Weather Agent Startup Script

set -e

echo "Starting Weather Agent..."

# Install dependencies using uv (faster and better dependency resolution)
if ! command -v uv &> /dev/null; then
    echo "Installing uv..."
    pip install uv
fi

if [ ! -d "venv" ]; then
    echo "Creating virtual environment with uv..."
    uv venv venv
fi

source venv/bin/activate
uv pip install -r requirements.txt

# Load environment variables from shared .env file
if [ -f "../.env" ]; then
    set -a
    source ../.env
    set +a
    echo "✓ Loaded environment variables from ../.env"
fi

# Start the application
python -m weather_agent.weather_agent_start
""")
    startup_script.chmod(0o755)
    print("  ✓ Created start.sh")

    print(f"✓ weather-agent package created at: {package_dir}")
    return package_dir


def copy_shared_env():
    """Copy the shared .env file to dist directory"""
    env_file = PROJECT_ROOT / ".env"
    if env_file.exists():
        shutil.copy2(env_file, BUILD_DIR / ".env")
        print("✓ Copied shared .env to dist/")
    else:
        print("⚠ Warning: .env file not found in project root")


def main():
    """Main build process"""
    print("=" * 60)
    print("Building RocketMQ Multi-Agent Packages")
    print("=" * 60)

    # Step 1: Clean build directory
    clean_build_dir()

    # Step 2: Build packages
    try:
        create_supervisor_package()
        create_travel_package()
        create_weather_package()

        # Step 3: Copy shared .env file
        copy_shared_env()

        print("\n" + "=" * 60)
        print("✅ Build completed successfully!")
        print("=" * 60)
        print(f"\nPackages location: {BUILD_DIR}")
        print("\nAvailable packages:")
        print("  1. supervisor-agent/")
        print("  2. travel-agent/")
        print("  3. weather-agent/")
        print("  4. .env (shared configuration)")
        print("\nTo run each service:")
        print(f"  cd {BUILD_DIR}/supervisor-agent && ./start.sh")
        print(f"  cd {BUILD_DIR}/travel-agent && ./start.sh")
        print(f"  cd {BUILD_DIR}/weather-agent && ./start.sh")
        print("\nNote: All services share the same .env file in dist/")
        print("      Edit dist/.env to configure all services")
        print("\nTip: Using 'uv' for faster dependency installation")

    except Exception as e:
        print(f"\n❌ Build failed: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()
