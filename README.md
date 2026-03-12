# Agentic AI Function Calling

An Android application that demonstrates **on-device AI function calling** using Google's LiteRT-LM with Kotlin Multiplatform and Jetpack Compose.

## Overview

This app showcases how to:
- Run lightweight language models (Gemma 3 1B) directly on Android devices
- Parse AI-generated function calls and dispatch them to native handlers
- Stream real-time token generation from LiteRT-LM
- Integrate with Hugging Face API for model management
- Build reactive UIs with Compose and Kotlin Flow

## Architecture

**Technology Stack:**
- **LiteRT-LM**: On-device ML inference with GPU acceleration (OpenCL)
- **Ktor Client**: Network requests with timeout & logging configuration
- **Koin**: Dependency injection for Android
- **Jetpack Compose**: Modern declarative UI framework
- **Kotlin Flow**: Reactive programming for streaming responses
- **Kotlin Serialization**: JSON serialization/deserialization

**Key Components:**
- `LiteRtEngine`: Wraps LiteRT-LM API for model loading and inference
- `FunctionDispatcher`: Routes parsed function calls to handlers (Calendar, SMS, etc.)
- `FunctionCallParser`: Extracts function calls from model responses
- `ModelRepository`: Manages model downloads from Hugging Face

## Requirements

- **Android SDK**: 29+ (arm64-v8a ABI)
- **Model Format**: `.litertlm` (Gemma 3 1B-IT recommended)
- **GPU Support**: Optional (requires `libOpenCL.so`)
- **RAM**: ~2-3 GB for model loading

## Getting Started

1. Clone the repository
2. Add your Hugging Face API key in `AppModule.kt`:
   ```kotlin
   hfApiKey = "your_hf_token_here"
   ```
3. Download the `.litertlm` model to the app cache directory
4. Build and run on an Android device

## Permissions

- `INTERNET`: Model download and API calls
- Optional: `READ_CALENDAR`, `WRITE_CALENDAR`, `SEND_SMS` (for function dispatchers)



