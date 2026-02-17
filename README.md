# Lilac Voice

**On-Device Real-Time Speech Recognition · Translation · Simultaneous Interpretation**

Lilac Voice lets you attend a lecture, conversation, tour or event in a foreign language and hear it live in your own instantly and privately, so you never miss the moment or need to worry about Internet connection or expensive roaming data usage. 

[![Watch Demo](https://img.youtube.com/vi/ekDY8SiJfUM/maxresdefault.jpg)](https://www.youtube.com/shorts/ekDY8SiJfUM)

[Download APK](https://github.com/mengyue-z/-Nexa-Bounty-Lilac-Voice-Offline-Interpreter/releases/tag/v1.0.0)



## Overview

Lilac Voice is an Android On-Device AI app that enables:

- 🎤 Offline real-time speech recognition
- 🌍 On-device translation
- 🔊 Simultaneous spoken interpretation

Runs fully on-device on Qualcomm Snapdragon 8 ELite chipset using NexaSDK.




## Why On-Device Matters

- Privacy – private conversation never leaves the device  
- Low latency – real-time streaming transcription  
- No API cost
- No network dependency – no need to worry about connectivity or expensive data usage when traveling abroad.



## Core Features

- Real-time streaming ASR  
- Multi-language translation
- Simultaneous TTS output  
- Dual display (original + translated text)  
- Automatic model download & caching  
- NPU acceleration
 


## Tech Stack

- Kotlin + Java
- Android (SDK 35)
- NexaSDK for local model inferencing
- Parakeet TDT 0.6B v3 (ASR, NPU accelerated)
- Google ML Kit (on-device translation)
- Android TTS (simultaneous spoken output)



## Requirements

#### Use the App (Install APK)

- Qualcomm Snapdragon 8 Elite Gen 4 Android device (Hexagon NPU required)
- Android 8.1+ (API 27+)

#### Develop / Build from Source

- JDK 11+  
- Android Studio (latest stable recommended)  
- Android SDK 35  
- Qualcomm Snapdragon 8 Elite Gen 4 Android device



## Build & Run

1. Clone the repository

2. Open the project in Android Studio

3. Ensure Android SDK 35 is installed

4. Connect Snapdragon 8 Elite Gen 4 device (USB debugging enabled)

5. Press **Run**

6. On first launch:
   - Grant microphone permission
   - Download model (Internet connection required)
   - Start recording
