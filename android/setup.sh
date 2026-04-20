#!/bin/bash
# VoxHarness Android setup script
# Downloads required model files and sets up local.properties

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ASSETS_DIR="$SCRIPT_DIR/app/src/main/assets"

echo "=== VoxHarness Android Setup ==="

# Create assets directory
mkdir -p "$ASSETS_DIR"

# Download Silero VAD ONNX model
VAD_MODEL="$ASSETS_DIR/silero_vad.onnx"
if [ -f "$VAD_MODEL" ]; then
    echo "✓ Silero VAD model already exists"
else
    echo "Downloading Silero VAD ONNX model..."
    curl -L -o "$VAD_MODEL" \
        "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx"
    echo "✓ Silero VAD model downloaded"
fi

# Set up local.properties if it doesn't exist
LOCAL_PROPS="$SCRIPT_DIR/local.properties"
if [ -f "$LOCAL_PROPS" ]; then
    echo "✓ local.properties already exists"
else
    echo "Creating local.properties from template..."
    cp "$SCRIPT_DIR/local.properties.example" "$LOCAL_PROPS"
    echo "⚠ Edit local.properties and add your API keys before building"
fi

# Check for Android SDK
if [ -n "$ANDROID_HOME" ]; then
    echo "✓ Android SDK found at $ANDROID_HOME"
elif [ -d "$HOME/Library/Android/sdk" ]; then
    echo "✓ Android SDK found at ~/Library/Android/sdk"
    echo "sdk.dir=$HOME/Library/Android/sdk" >> "$LOCAL_PROPS"
elif [ -d "$HOME/Android/Sdk" ]; then
    echo "✓ Android SDK found at ~/Android/Sdk"
    echo "sdk.dir=$HOME/Android/Sdk" >> "$LOCAL_PROPS"
else
    echo "⚠ Android SDK not found. Install Android Studio or set ANDROID_HOME"
fi

echo ""
echo "=== Setup complete ==="
echo "Next steps:"
echo "  1. Edit local.properties with your API keys"
echo "  2. Open this directory in Android Studio"
echo "  3. Build and run on your device"
