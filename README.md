# Artemis Android Extended

This fork is based on [Artemis Android](https://github.com/ClassicOldSong/moonlight-android) and adds advanced controller features, input customization, and quality-of-life improvements.

---

## 🎮 DualSense USB Bluetooth HCI Bridge

Connect a DualSense through a dedicated USB Bluetooth adapter, bypassing Android's standard Bluetooth limitations and unlocking full DualSense functionality wirelessly, like **HD haptics, the built-in speaker, and adaptive triggers**.

> **⚠️ Beta:** The Bridge is currently a Beta feature and compatibility with all Bluetooth adapters is not guaranteed.

### DualSense features

- Native DualSense, DualShock 4, or Xbox 360 host emulation through Apollo Extended
- Adaptive triggers, HD haptics, conventional rumble, lightbar, player LEDs, touchpad and gyro
- Built-in controller-speaker audio over USB and Bluetooth HCI Bridge
- Automatic headset-jack detection and stream-audio routing to a headset connected to the controller
- Automatic return to the built-in speaker after the headset is unplugged
- Controller microphone support over both USB and Bluetooth HCI Bridge
- The DualSense mute button acts as a global client microphone mute control, with in-stream status feedback

## 🎙️ Microphone Forwarding

Forward microphone audio from the Android client to the host as a Steam Streaming Microphone device.

- Choose the **DualSense microphone** or the **Android device microphone** as the source
- Enable or disable forwarding from Settings or the Controller-Friendly Quick Menu
- When a headset is connected to the DualSense, its microphone is used by the controller automatically
- The DualSense mute button mutes forwarding regardless of the selected microphone source

Microphone forwarding requires **Apollo Extended** on the host. DualSense microphone capture requires either a wired USB controller or the DualSense USB Bluetooth HCI Bridge.

## 🎯 Gyro Aim

Use controller gyro for aiming by blending it into **right-stick input**, or convert it directly into **mouse input** in KBM Mode. Gyro behavior and controls are fully customizable.

## ⌨️ Controller KBM Mode

Convert controller input directly into fully customizable **keyboard and mouse controls**, including buttons, sticks, triggers, paddles, touchpad, and gyro.

Custom mappings can be saved, imported, and exported as presets.

## 🕹️ Controller-Friendly Quick Menu

A fullscreen Quick Menu designed for complete controller navigation. The menu layout and activation shortcut are customizable, and all controls can be used without touching the screen.

## 🔎 Settings Search

Quickly find and access settings without browsing through individual categories.

## 🔊 Android or Windows Volume Control

Choose whether the device volume buttons control **Android volume** or **Windows volume** while streaming.

Windows volume control requires a corresponding [PowerToys Keyboard Manager](https://learn.microsoft.com/windows/powertoys/keyboard-manager) mapping.

---

## 🖥️ Apollo Extended Host

[Apollo Extended](https://github.com/Taveszfito/Apollo-Extended) is now available. It provides **Xbox 360, DualShock 4, and native DualSense host emulation** for Artemis Extended.

Together with the DualSense HCI Bridge, it enables native end-to-end DualSense streaming while preserving **HD haptics, adaptive triggers, controller-speaker audio, lighting, touchpad and motion input wirelessly**.

Download the host installer from the [Apollo Extended releases](https://github.com/Taveszfito/Apollo-Extended/releases) page. Standard Sunshine and Apollo hosts remain compatible, but native DualSense emulation and Extended controller selection require Apollo Extended.

---

### Credits

Based on [Artemis Android](https://github.com/ClassicOldSong/moonlight-android).
(BTW, Windows client will come in the future, and its development is currently in progress.
Update: The first Windows build has arrived!) 
