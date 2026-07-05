<div align="center">
  <h1>⚡ health-tracker: Telemetry & AI Dietician</h1>
  <p><strong>A fully autonomous, self-hosted health telemetry and AI nutrition system.</strong></p>
  <p>Wear OS → Cloudflare Workers → D1 SQLite → SvelteKit</p>
</div>

---

## 📖 Vision & Overview
**health-tracker** is designed to replace passive health ledgers (like MyFitnessPal or basic smartwatch apps) with a proactive, dynamic health ecosystem. 

Currently, the system acts as a **zero-friction telemetry pipeline**. A Wear OS smartwatch passively collects high-resolution health data (Heart Rate, Steps, Floors, Calories) and syncs it to a serverless Cloudflare backend. The data is instantly visualized on a glassmorphism "Command Center" dashboard built with SvelteKit.

**The Roadmap (AI-Native Dietician):**
The next evolution of health-tracker is an AI-powered dietician that uses your live smartwatch telemetry (calories burned, sleep debt) to dynamically adjust your daily macro-nutrient goals. Rather than scanning barcodes, users will log food via natural language (voice/photos), and the AI will calculate macros and suggest personalized meals that fit remaining biological budgets and medical constraints.

---

## 🏗️ Architecture & Inter-connectivity

health-tracker is split into three decoupled components that communicate via REST APIs over Cloudflare's Edge Network.

```mermaid
flowchart TD
    subgraph Wearable
        W[Watch App<br/>(Kotlin/Wear OS)]
        HS[Android Health Services<br/>Passive Client]
        WM[WorkManager<br/>Background Sync]
        HS -->|Telemetry| W
        W -->|Batched Uploads| WM
    end

    subgraph Edge Backend
        CF[Cloudflare Workers API<br/>TypeScript]
        D1[(Cloudflare D1<br/>SQLite Edge DB)]
        CF <-->|Read/Write| D1
    end

    subgraph Client
        SK[SvelteKit Dashboard<br/>Command Center]
        UP[uPlot / Tailwind / DaisyUI]
        SK --> UP
    end

    WM == "POST /v1/ingest (Bearer Token)" ==> CF
    SK == "GET /v1/summary & /v1/samples" ==> CF
```

### 1. Watch App (`/watch-app`)
* **Platform:** Wear OS 3+ (Kotlin)
* **Core Logic:** Uses the Android `PassiveMonitoringClient` to silently gather biometric data in the background without draining the battery. 
* **Metrics:** Heart Rate, Steps, Calories, Distance, Floors Climbed, and Elevation Gain.
* **Syncing:** Uses `WorkManager` (`UploadWorker.kt`) to queue data locally in a Room buffer. It attempts silent background batch uploads every 24 hours (or on-demand via the "Force Sync" UI).
* **UI:** Uses Wear Compose `ScalingLazyColumn` for a futuristic, curved native watch feel.

### 2. Edge Backend (`/backend`)
* **Platform:** Cloudflare Workers (TypeScript)
* **Database:** Cloudflare D1 (Serverless SQLite)
* **Core Logic:** An extremely lightweight, incredibly fast REST API routing engine. 
* **Security:** The ingest endpoint (`/v1/ingest`) is secured by a static `WATCH_TOKEN` to prevent unauthorized database writes.
* **Idempotency:** The database schema heavily uses `UNIQUE` constraints and `INSERT OR IGNORE` to safely handle duplicate network retries from the watch.

### 3. SvelteKit Dashboard (`/dashboard`)
* **Platform:** SvelteKit + Vite
* **Styling:** Tailwind CSS + DaisyUI (Synthwave theme)
* **Charting:** `uPlot` (for high-performance rendering of tens of thousands of heart-rate data points simultaneously).
* **Core Logic:** Fetches aggregated metrics and raw telemetry from the backend to construct a live "Command Center". Features time-range filtering (24H, 7D, 30D), glassmorphism UI, and spline-interpolated glowing charts.

---

## 🚀 The Master Plan: AI-Native Dietician

The core infrastructure is running. The next major phase is the **AI Dietician**, transforming health-tracker from a tracker into an autonomous coach.

### Identified Gaps in Current Market:
1. **Adherence Cliff:** Manual barcode scanning causes app abandonment.
2. **Static Goals:** Standard apps don't adjust your calories if you run 5 miles or sleep poorly.
3. **Grocery Disconnect:** Meal plans don't sync with what's actually in your fridge.

### Our Solution Strategy:
* **Zero-Friction Input:** Adding Cloudflare AI Workers to accept natural language input. Tap the watch and say, *"I had 2 eggs and toast"* -> LLM parses macros -> Saves to D1.
* **Dynamic Recalibration:** If the watch uploads a 600-calorie run, the backend automatically increases your daily carb/calorie allowance.
* **Constraint-Aware Suggestions:** The database will store a strict `user_profile` (e.g., "sensitive gut", "lactose intolerant"). The AI will generate meal suggestions to fill the remaining daily macros while strictly adhering to the health constraints.

---

## 🛠️ Deployment & Getting Started

To run or deploy this repository, refer to the detailed runbooks:
* **Cloud Deploy:** See `DEPLOY.md` for zero-cost deployment instructions to Cloudflare Workers and Cloudflare Pages.
* **Local Dev:**
  * Backend: `cd backend && npx wrangler dev`
  * Dashboard: `cd dashboard && npm run dev`
  * Watch: Open `/watch-app` in Android Studio and run on a physical ADB-connected Galaxy Watch.

---
*Built for the Edge. Zero maintenance. Highly personalized.*
