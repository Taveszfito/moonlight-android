# Artemis Android Extended

This fork is based on [Artemis Android](https://github.com/ClassicOldSong/moonlight-android) and includes additional input customization and quality-of-life features.

## Extra features

- **Modern, fully controller-supported quick menu**
  - Use a fullscreen, card-based interface across the quick menu and its submenus.
  - Navigate menu cards, dialogs, presets, switches, and settings with a controller.
  - Grab and adjust sliders using controller buttons, the D-pad, or an analog stick.
  - Reorder entries, hide unused options, browse editable submenus, and restore removed entries later.
- **Settings search** - quickly find settings without browsing every category.
- **Android or Windows volume controls** - choose whether the device volume buttons control Android volume or send Windows volume keys. Windows volume control requires a matching [PowerToys Keyboard Manager](https://learn.microsoft.com/windows/powertoys/keyboard-manager) key remap on the host.
- **Controller gyro aim for every game**
  - Convert controller gyroscope movement into right-stick aiming.
  - Adjust sensitivity and deadzone compensation.
  - Improved precision and flick handling.
  - Automatically use the Android device gyroscope when the controller has no motion sensor.
  - Enable or disable gyro smoothing.
  - Choose between Off, Always on, or active only while selected controller buttons are held.
  - Assign multiple gyro activation buttons.
  - Cycle through the available gyro modes with Share + Triangle/Y while preserving standalone Share input.
  - Show an optional toast with the current gyro mode after switching.
- **Controller KBM Mode**
  - Hide Bluetooth and wired controllers from the host.
  - Convert controller input directly into keyboard and mouse events.
  - Map buttons, sticks, triggers, paddles, touchpad clicks, and custom controller buttons.
  - Assign keyboard keys, mouse buttons, wheel actions, mouse movement, WASD, or arrow-key movement.
  - Use a modern card-based mapping editor and action picker.
  - Select custom mappings from a controller-navigable virtual PC keyboard containing letters, modifiers, function keys, navigation keys, arrows, and numpad keys.
  - Use continuous proportional stick-to-mouse movement with adjustable speed and high-frequency input updates.
  - Configure trigger threshold and Hold, Single press, or Repeat click behavior.
  - Use direct gyro-to-mouse aiming with adjustable sensitivity.
  - Independently invert the X, Y, and Z gyro axes.
  - Enable or disable gyro mouse smoothing.
  - Choose between Off, Always on, or active only while selected mapped buttons are held.
  - Assign multiple gyro activation buttons.
  - Cycle through the available gyro mouse modes with Share + Triangle/Y.
  - Show an optional toast with the current gyro mouse mode after switching.
  - Save, load, delete, and reset controller mapping presets.
  - Export and import shareable controller mapping preset files.
  - Manage, load, import, export, and delete presets directly from the main menu without starting a stream.
- **DualSense USB Bluetooth Bridge (Alpha)**
  - Connect a PS5 DualSense controller through an external USB Bluetooth adapter for full app-side hardware access without Android's normal Bluetooth controller limitations.
  - A standard USB Bluetooth HCI adapter with Bluetooth Classic (BR/EDR) and HID support is required; BLE-only adapters are not suitable. USB-C devices may also require an OTG adapter.
  - **Tested and recommended adapter:** Baseus BA04 USB Bluetooth 5.0/5.1 using the BR8651 chipset. Other generic plug-and-play adapters may work, but compatibility is not guaranteed during the Alpha phase.
  - Compared with standard Android Bluetooth mode, the Bridge provides more detailed vibration, full gyro access, touchpad support, lightbar control, battery information, shortcuts, and Quick Menu navigation.
  - The app is ready to support the complete DualSense feature set, but Apollo currently emulates only a DualShock 4 on Windows. For now, in-game functionality is limited to features available through PS4 controller emulation.
  - Once Apollo supports PS5 controller emulation and feedback, DualSense-only features such as adaptive triggers can work without redesigning the Bridge.
  - The Bridge is a future-ready feature designed to unlock additional DualSense capabilities automatically as host support evolves.
  - This feature is currently in **Alpha** and may still be unstable with some adapters or connections.
