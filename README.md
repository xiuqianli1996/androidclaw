# AndroidClaw

[中文文档](README.zh-CN.md)

> This repository is built with a **full vibe coding workflow**: product ideation, architecture decisions, feature implementation, debugging, and documentation were iterated rapidly through AI-assisted coding collaboration on real devices.

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

## Architecture

AndroidClaw is organized around a few core layers:

- **Agent Core**: iterative planning/execution loop, memory, tool calling, sub-agent and skill integration
- **Tool Layer**: built-in phone/file/command/http tools, MCP tool bridge, and skill tools
- **UI Understanding Layer**: accessibility snapshot-first observation (`phone_get_ui_snapshot_compact`) with screenshot/text fallback
- **Channel Layer**: unified channel engine for inbound/outbound message routing (local app channel + Feishu channel)
- **Persistence & Reliability**: Room-based conversation/message storage, daemon service, health checks, runtime logs

## Current Capabilities

### LLM and Agent

- Configurable model providers and model parameters
- Agent orchestration with conversation memory
- Tool registry and execution framework
- Structured runtime trace and execution metadata (elapsed time, iterations, token usage)

### Phone Operation (Key Feature)

- Accessibility-based UI interaction
- Click by text, long-click by text
- Coordinate click / long-click
- Snapshot node based operation: `phone_click_node`, `phone_input_node`
- Screenshot coordinate mapping click: `phone_click_coordinates_from_screenshot`
- Swipe and scroll actions
- Global actions: Back / Home / Recents
- Open app by package name
- Read current screen text and window title
- Screenshot capture/debug tools (data URL + file output)

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
- Channel abstraction for shared inbound/outbound processing

### App UX

- First-launch permission guide page
- Required permission gating before entering main page
- Unified settings page with 3 sections: Model / Feishu / Agent
- Execution overlay (thinking/tool progress), pause/resume controls, and process trace messages
- Image preview with zoom/pan and persistent local image storage
- Built-in runtime log viewer with long-press delete

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

## Technical Notes

- [Technical share (CN)](tech.md)

## Roadmap Ideas

- Richer phone toolset (OCR, structured UI grounding)
- Better task planning and multi-step recovery
- More channels/connectors for remote commands
- Enhanced permission diagnostics across Android ROMs

## Disclaimer

This project includes powerful automation capabilities. Please comply with local laws, platform rules, and app terms when using it.
