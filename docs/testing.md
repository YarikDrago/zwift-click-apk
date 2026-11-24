# Zwift controller hardware test

## Install and start

If upgrading, tap **Stop Advertising** on the connection screen, close Zwift on the computer and install the new APK over the existing app. Keep `Zwift SF2` as the phone's Bluetooth name for the comparison.

1. Build the debug APK as described in the [README](../README.md), or use the existing `app/build/outputs/apk/debug/app-debug.apk`. On an Android phone, enable USB debugging, connect by USB and accept the debugging prompt. Run `adb devices` and check that the phone is shown as `device`. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`. You can also transfer and open the APK with Android's package installer.
2. Turn the phone's Bluetooth on. Open **Zwift Click Emulator — PoC → Connection**, tap **Start Advertising** and grant **Nearby devices** if asked. Leave the app visible. Check for **BLE Peripheral: Supported** and **Advertising: Started**. The log should show GATT service registration with `status=0` followed by Android's advertising success.
3. For the same discovery conditions as the first hardware test, use `Zwift SF2` as the phone's Bluetooth name. If you need to change it, stop the advert, set that name in Android Settings, then return and start again. Confirm the app logs `scan response name=Zwift SF2`. Restore your original Bluetooth name after the test if you changed it.

## Check in Zwift

1. Enable Bluetooth on the MacBook or Windows laptop and let Zwift use it. Launch Zwift and open **Paired Devices**. If Zwift goes straight to Home, open the pairing screen from the home screen. Use the laptop's built-in Bluetooth as the connection path. [Zwift device pairing instructions](https://support.zwift.com/connecting-your-devices-for-cycling-HJIFDrw5S)
2. Pair the Elite Justo 2 in Zwift as **Power Source** and **Resistance** over Bluetooth, which is the setup Elite describes for full Click compatibility. [Elite trainer configuration on Zwift](https://elitesrl.zendesk.com/hc/en-us/articles/360014674293-Trainer-configuration-on-Zwift)
3. Open **Controls**. With the Android app still visible and advertising, allow about 30 seconds for discovery. Record the exact entry name if one appears, or capture the Controls list if it stays empty.
4. Select the entry if offered. Record what Zwift shows and what the phone logs, including any connection, descriptor write, incoming hex payload, indication result, and disconnect. Discovery means the device appeared in the Controls list. Connection means Android reported a successful GATT connection. Recognition after the handshake is a separate observation.
5. When finished, tap **Stop Advertising** on the phone. If testing another host, close Zwift's active connection first, then start a fresh Android advertising session.

## Stage 2 gear test

1. Use APK version 0.2.7. Pair **Zwift Ride 4242** under **Controls** and look for **Gear controls: Ready** on the phone. The connection log should show `RideOn indication completion: status=0` and `Gear neutral completion: status=0`. Tap **Back**, then **Go**. Verify that **Gear controls: Ready** is still visible and both gear buttons are enabled. **+** is green and **−** is red.
2. Start a free ride in Zwift with virtual shifting enabled and the Elite Justo 2 paired as **Resistance**. Choose **Settings → Hardware → Shift Style → Sequential** and note the current gear. [Zwift shift styles](https://support.zwift.com/en_us/shift-style-and-gear-range-rksYezzIC) · [Zwift virtual shifting checks](https://support.zwift.com/en_us/controls-not-working-on-zwift-click-BJKICSXWp)
3. Start away from the gear limits. Tap **+** once and check that the HUD increases by one gear. Tap **−** once and check that it returns to the original gear.
4. Try two quick taps of **+** and check the HUD for two gear changes. Use **Back → Connection** to confirm the log contains the shift events and the session is still active. Stop Advertising, restart it, then reconnect in Zwift and verify a new tap works.

Report the gear before and after each tap. If a button has no effect, send the corresponding `Click … queued`, `Gear press` and `Gear release` log lines.

### Custom button layout

1. Open **Adjust** from Home. Tap **+** and reduce its width and height, confirm with **OK**, then drag it to one side. Repeat for **−**. Tap **Back → Go** and check that both buttons have the same sizes and positions as on **Adjust**.
2. Rotate the phone, return to **Adjust**, and make a different layout. Switch between orientations on **Go** and check that each layout returns. While a Zwift session is active, check that **Gear controls: Ready** remains visible after rotation and try both buttons.
3. Close and reopen the app and check that both orientations retain their layouts. Tap **Layout → Reset** in one orientation and verify that only that layout returns to its default.
4. Set the width and height of both buttons to 100% using **Layout → Resize +** and **Layout → Resize −**. In **Adjust**, tapping their shared area should open **Resize +**. In **Go** with Zwift connected, tapping that area should shift up. Reduce the height of **+** and check that tapping the exposed part of **−** shifts down.

### Tutorial plus prompt

When the tutorial asks for `+`, open **Go** and tap **+** once. The connection log should show `Click PLUS (0x37) queued`, followed by press `37 08 00 10 01` and release `37 08 01 10 01`. If Zwift asks for `−` next, tap **−**. Record whether the tutorial advances after each tap.

## Check the radio with a generic scanner

Use a second phone with a BLE scanner such as **nRF Connect for Mobile**. Start a scan near the Android phone with the app advertising. Identify the entry by `FC82` and manufacturer data for company `0x094A` with payload `09 42 42`. Some scanners include company bytes and show `4A 09 09 42 42` together. Verify that the entry disappears or stops receiving live packets when you tap **Stop Advertising**. If the app logged the name in its scan response, check for `Zwift SF2` with active scanning. [Nordic Semiconductor's nRF Connect for Mobile](https://www.nordicsemi.com/Products/Development-tools/nRF-Connect-for-mobile)

Optionally connect from the scanner to inspect the service. Confirm that `FC82` exposes `02` as notifiable, `03` as writable and `04` as indicatable, using the full UUIDs in the [protocol notes](protocol.md). You can enable indications on `04` and write hex `52 69 64 65 4F 6E` to `03` to check that the app returns the six-byte indication. Disconnect the scanner before trying Zwift. Start a fresh session on the emulator phone if the entry is absent after the scanner disconnects.

| Observation | Where to look |
| --- | --- |
| Android reports GATT or advertising startup failure | Use the phone's log to identify the failed operation and its status or advertising error code. |
| Android reports success, and the scanner receives no matching live packets | Check the scanner with another nearby BLE device, move the phones closer, and repeat Start/Stop. Advertising over the air is still unverified. |
| The scanner sees matching packets, and Zwift's Controls list stays empty | The BLE advert is on air. Compare the named and unnamed runs to test whether Zwift's discovery filter needs the scan-response name. |
| The entry appears, and Android records no successful connection | Report the pairing result and any nonzero GATT connection status. |
| Android reports a connection, and Zwift errors or disconnects | The advert and BLE link worked. The descriptor writes, characteristic UUIDs, incoming bytes and response status help locate the GATT or protocol mismatch. |

## Send back the results

Include the phone model and Android version, desktop OS and Zwift version, trainer connection type, and whether the phone used the named scan response. Report what the scanner saw and what Zwift displayed under **Controls**. If you could select the entry, report what happened next and how long the connection lasted.

Copy the app's on-screen log from **Start Advertising** through the result, or run the following before starting the test and stop the capture with Ctrl+C afterward.

```powershell
adb logcat -v time -s ZwiftClickPoC:I
```

Include the GATT registration and advertising result, every connection and disconnection status, and any characteristic or descriptor operations with their hex payloads. A screenshot of the Controls list and the scanner's advertising details can help confirm what was visible.

For 0.1.1, look for subscription writes of `01 00` on the descriptor of `00000002-…` and `02 00` on the descriptor of `00000004-…`. If Zwift sends a command, capture the first `Characteristic write` line and its full payload. When a write on `00000003-…` starts with `RideOn` and the client has subscribed to indications, the log should also show the send and completion status for the reply on `00000004-…`.
