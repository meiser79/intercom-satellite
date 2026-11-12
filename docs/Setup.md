# Intercom Satellite – Setup

1. Install a signed APK on your phone. The build from this repository is unsigned and must be signed before installation; sign every update with the same key so settings are kept.

2. On each kiosk, enable **Remote Administration**, **Find other kiosks** and **Intercom** in Kiosk Satellite, and copy the intercom key. In the app, open **Settings** (top bar) and enter exactly the same key.

3. The phone and the kiosks need a direct connection on the same network, over Wi-Fi or Ethernet. Guest networks with client isolation block calls. If a kiosk is not found automatically, choose **Add kiosk** and enter its address, for example `192.168.1.50` or `kitchen.local`. The default port is 2324; add a different port after a colon. For an encrypted connection, start with `https://`.

4. Choose **Call** on a kiosk card. Hold the talk button to speak (push-to-talk), or tap the push-to-talk button in the row below to talk hands-free. The same row turns the camera on and off, switches cameras and turns the loudspeaker on and off. **Settings** picks whether calls start with push-to-talk and on the loudspeaker. Kiosk calls to your phone appear in the regular phone app once you have enabled the Intercom call account. The app offers it when the intercom first runs; later it is under **Settings → Phone account**.

5. **Start announcement** offers all available kiosks, a one-time selection, or a saved room group. Create groups of 1–32 previously connected kiosks under **Room groups** in the top bar of the home screen. The announcement shows each room's status: connected, unreachable or declined. Unreachable group members are skipped, and an empty selection never calls everyone. The microphone starts muted. A declined or failed kiosk does not end the other connections.

6. For a home-screen widget, first connect a kiosk in the app. Then add the **Kiosk widget** and pick its kiosk. **Call** or **Video** opens the app, rechecks the kiosk and calls it; the video button shows only for kiosks that support video. A badge on the room icon shows whether the kiosk is online, on Do not disturb or offline, in the app's kiosk list as well; kiosks are checked every 20 seconds, so a change can take that long to show. The **Announcement** widget starts an announcement to all kiosks or a room group.

7. Optional settings: reception after restart, quiet hours with weekdays and exceptions, following Android's Do not disturb, a ringtone, light/dark/system theme. The app follows the wallpaper-based Material You colors of your device. Open the call history from the top bar of the home screen. Change a kiosk's name, symbol, ringtone and favorite status through the options button on its card (**Edit**). Ringtones only apply while calls ring in the app, not in the phone app.

8. In **Settings**, switch **Receive announcements** off to refuse announcements. Individual calls can still ring; quiet hours and **Do not disturb** apply in addition.

9. **Do not disturb for …** on the home screen offers 30 minutes, two hours, until 07:00 tomorrow, or no end. The end time survives a restart. Turning it off ends the timer immediately. When it expires, your scheduled quiet hours stay active; the status notification updates within five seconds while reception is running.

## Reception while the phone is locked

When the intercom first runs, the app asks to allow unrestricted battery use, and, while calls ring in the app, to show them full screen on the lock screen. Whatever you skip or later take back shows up in **Settings** as **Background reception** or **Calls on the lock screen**. Some manufacturers still limit background activity. Active reception uses more battery. **Turn off availability** stops it; the app then does not start automatically after a restart.

During an incoming call, the app is shown above the lock screen. At all other times it stays behind the lock screen.

## Security

TLS must be set the same way on the phone and the kiosk. With TLS, confirm each kiosk's certificate by its SHA-256 fingerprint. The app never falls back to an unencrypted connection.

Calls are not recorded and no cloud is used. Minimum supported version: Android 12.
