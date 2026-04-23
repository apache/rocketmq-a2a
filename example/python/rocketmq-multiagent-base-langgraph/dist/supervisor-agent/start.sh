#!/bin/bash
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
