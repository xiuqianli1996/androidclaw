# AndroidClaw

[中文文档](README.zh-CN.md)

AndroidClaw is an Android-native AI agent focused on **LLM-driven phone operation**. It combines on-device accessibility capabilities with LLM planning and tool execution, so the model can understand user intent and interact with apps on the phone.

## Inspiration

This project is built with ideas inspired by several strong agent/mobile products and open projects, including:

- [OpenClaw](https://github.com)
- [NanoBot](https://github.com)
- [豆包手机助手](https://www.doubao.com)
- [AutoGLM](https://github.com)

The goal is not to clone any single project, but to adapt practical ideas into an Android-focused architecture that is easy to run, extend, and deploy.

## Core Goal

Enable **LLM as a phone operator**:

- Understand user requests in natural language
- Plan actions and call tools
- Read UI content and perform gestures
- Execute app/device operations reliably in a long-running daemon mode

## Current Capabilities

### LLM and Agent

- Configurable model providers and model parameters
- Agent orchestration with conversation memory
- Tool registry and execution framework

### Phone Operation (Key Feature)

- Accessibility-based UI interaction
- Click by text, long-click by text
- Coordinate click / long-click
- Swipe and scroll actions
- Global actions: Back / Home / Recents
- Open app by package name
- Read current screen text and window title

### File and Command Tools

- Read / write / list / delete / create files (app scope)
- Execute shell commands through tool layer

### Daemon and Reliability

- Foreground daemon service
- Boot-completed auto start
- WorkManager health checks and self-healing
- Network-aware reconnection flow

### Messaging / Integration

- Feishu bot integration for remote interaction
- WebSocket-based message handling

### App UX

- First-launch permission guide page
- Required permission gating before entering main page
- Settings pages for model and Feishu configuration

## Tech Stack

- Kotlin + Android SDK (minSdk 24, targetSdk 34)
- Room, WorkManager, Coroutines
- LangChain4j for model/tool integration
- OkHttp, Gson

## Build

```bash
./gradlew assembleDebug
```

APK output:

- `app/build/outputs/apk/debug/app-debug.apk`

## Roadmap Ideas

- Richer phone toolset (OCR, structured UI grounding)
- Better task planning and multi-step recovery
- More channels/connectors for remote commands
- Enhanced permission diagnostics across Android ROMs

## Disclaimer

This project includes powerful automation capabilities. Please comply with local laws, platform rules, and app terms when using it.
