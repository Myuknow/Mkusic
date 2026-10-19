package com.sikamikaniko.sonora.playback

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sikamikaniko.sonora.data.PlaybackSnapshot
import com.sikamikaniko.sonora.data.Prefs
import com.sikamikaniko.sonora.data.loadPlaybackSnapshot
import com.sikamikaniko.sonora.data.toMediaItems

/**
 * Starts Sonora the moment a Bluetooth audio device connects — car stereo, headphones —
 * whether or not the app is open. Without this the car's connect just wakes whichever
 * player Android saw last, which is how Spotify ends up winning.
 *
 * Registered in the manifest for ACL_CONNECTED, which is exempt from the implicit-broadcast
 * ban AND buys us an exemption from the Android 12+ "no starting foreground services from
 * the background" rule (any broadcast guarded by BLUETOOTH_CONNECT does), so we are allowed
 * to bring the playback service up from cold. That exemption is short-lived, which is why
 * everything below is on a tight, bounded clock.
 */
class BluetoothAutoplayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.btAutoplay) return
        if (!isAudioConnect(context, intent)) return

        // Resolve the queue before arming the debounce: with nothing to play there is no
        // point burning the 10s window and blocking the next connect broadcast.
        val snap = prefs.loadPlaybackSnapshot() ?: return
        val items = snap.toMediaItems()
        if (items.isEmpty()) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < DEBOUNCE_MS) return
        lastTrigger = now

        val pending = goAsync()
        val ctx = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        // Let the A2DP route become the active output before pressing play, so the first
        // second doesn't come out of the phone speaker.
        main.postDelayed({ startPlayback(ctx, snap, main) { pending.finish() } }, ROUTE_SETTLE_MS)
    }

    private fun isAudioConnect(ctx: Context, intent: Intent): Boolean = when (intent.action) {
        // Not on the implicit-broadcast exemption list, so most builds never deliver this
        // to a manifest receiver. Kept because it costs nothing where it does arrive.
        ACTION_A2DP_STATE ->
            intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED
        BluetoothDevice.ACTION_ACL_CONNECTED -> isAudioDevice(ctx, intent)
        else -> false
    }

    /** Only speakers and headsets — a watch or a tracker connecting must not start music. */
    private fun isAudioDevice(ctx: Context, intent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        val cls = try { device?.bluetoothClass } catch (_: SecurityException) { null }
        if (cls != null) {
            return cls.hasService(BluetoothClass.Service.AUDIO) ||
                cls.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        }
        // Some head units don't hand us a readable class. Rather than fail silently, ask
        // the adapter whether anything is actually connected on the A2DP profile.
        return try {
            val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            mgr?.adapter?.getProfileConnectionState(BluetoothProfile.A2DP) ==
                BluetoothProfile.STATE_CONNECTED
        } catch (_: SecurityException) { false }
    }

    private fun startPlayback(ctx: Context, snap: PlaybackSnapshot, main: Handler, done: () -> Unit) {
        val finish = OneShot(done)
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()

        // Hard stop, well inside the receiver's budget. Cancels the pending connect too:
        // a late callback calling play() after the background-start exemption expired
        // would crash the service with ForegroundServiceStartNotAllowedException.
        main.postDelayed({
            if (!future.isDone) future.cancel(true)
            finish.run()
        }, GIVE_UP_MS)

        future.addListener({
            if (future.isCancelled) { finish.run(); return@addListener }
            val c = try { future.get() } catch (_: Exception) { null }
            if (c == null) { finish.run(); return@addListener }

            // Unbinding is only safe once the service has promoted itself to the
            // foreground, which media3 does when playback actually starts — not when
            // play() is called. On a cold cache over mobile data that can be seconds.
            val release = OneShot {
                try { c.release() } catch (_: Exception) {}
                finish.run()
            }
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) release.run() }
            })
            try {
                // If the service is already loaded (paused in the background) keep its
                // queue and just resume — restoring would throw away where he actually is.
                if (c.mediaItemCount == 0) {
                    val items = snap.toMediaItems()
                    c.setMediaItems(items, snap.index.coerceIn(0, items.lastIndex), snap.positionMs)
                    c.prepare()
                }
                c.play()
            } catch (_: Exception) {
                release.run()
                return@addListener
            }
            if (c.isPlaying) release.run() else main.postDelayed({ release.run() }, MAX_BUFFER_WAIT_MS)
        }, ContextCompat.getMainExecutor(ctx))
    }

    /** finish()/release() both have two callers each, and neither tolerates a second call. */
    private class OneShot(private val body: () -> Unit) {
        private var done = false
        fun run() {
            if (done) return
            done = true
            try { body() } catch (_: Exception) {}
        }
    }

    companion object {
        const val ACTION_A2DP_STATE = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
        private const val ROUTE_SETTLE_MS = 1_000L
        private const val MAX_BUFFER_WAIT_MS = 6_000L
        private const val GIVE_UP_MS = 6_500L
        private const val DEBOUNCE_MS = 10_000L

        @Volatile
        private var lastTrigger = 0L
    }
}
