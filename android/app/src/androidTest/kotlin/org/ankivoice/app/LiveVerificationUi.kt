package org.ankivoice.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Operator controls compiled only into the test APK; all audio still uses :speech. */
internal class LiveVerificationUi(private val instrumentation: Instrumentation) {
    private val activity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun render(title: String, body: String, controls: (LinearLayout) -> Unit = {}) {
        instrumentation.runOnMainSync {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 64, 40, 40)
                setBackgroundColor(Color.WHITE)
            }
            fun label(value: String, size: Float) = TextView(activity).apply {
                text = value
                textSize = size
                setTextColor(Color.BLACK)
                setPadding(0, 20, 0, 24)
            }
            layout.addView(label("AnkiVoice · live verification", 16f))
            layout.addView(label(title, 28f))
            layout.addView(label(body, 21f))
            controls(layout)
            activity.setContentView(ScrollView(activity).apply { addView(layout) })
        }
        instrumentation.sendStatus(1, Bundle().apply { putString("livePhase", title) })
    }

    fun show(title: String, body: String) = render(title, body)

    /** Null means no action; a timeout must never stand in for a confirmation. */
    fun choose(title: String, body: String, vararg labels: String, timeoutMs: Long = 300_000): String? {
        val result = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        render(title, body) { layout ->
            labels.forEach { label ->
                layout.addView(Button(activity).apply {
                    text = label
                    textSize = 19f
                    setOnClickListener {
                        if (result.compareAndSet(null, label)) {
                            for (i in 0 until layout.childCount) layout.getChildAt(i).isEnabled = false
                            latch.countDown()
                        }
                    }
                })
            }
        }
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) result.get() else null
    }

    /** Two independent, initially unchecked statements; only the operator may check them. */
    fun attest(result: String, expected: String): JSONObject? {
        val response = AtomicReference<JSONObject?>()
        val latch = CountDownLatch(1)
        render("Speech result", "$result\n\nConfirm only what actually happened:") { layout ->
            val heard = CheckBox(activity).apply {
                text = "I heard the prompt"
                setTextColor(Color.BLACK)
                textSize = 19f
            }
            val spoke = CheckBox(activity).apply {
                text = "I said: $expected"
                setTextColor(Color.BLACK)
                textSize = 19f
            }
            layout.addView(heard)
            layout.addView(spoke)
            layout.addView(Button(activity).apply {
                text = "Save result"
                setOnClickListener {
                    isEnabled = false
                    response.set(JSONObject().put("promptAudible", heard.isChecked)
                        .put("spokeExpectedPhrase", spoke.isChecked).put("source", "operator-touch"))
                    latch.countDown()
                }
            })
        }
        return if (latch.await(300_000, TimeUnit.MILLISECONDS)) response.get() else null
    }

    /** One statement, initially unchecked, for a capture with no prompt; only the operator may check it. */
    fun attestSpoken(result: String, expected: String): JSONObject? {
        val response = AtomicReference<JSONObject?>()
        val latch = CountDownLatch(1)
        render("Capture result", "$result\n\nConfirm only what actually happened:") { layout ->
            val spoke = CheckBox(activity).apply {
                text = "I said: $expected"
                setTextColor(Color.BLACK)
                textSize = 19f
            }
            layout.addView(spoke)
            layout.addView(Button(activity).apply {
                text = "Save result"
                setOnClickListener {
                    isEnabled = false
                    response.set(JSONObject().put("spokeExpectedPhrase", spoke.isChecked).put("source", "operator-touch"))
                    latch.countDown()
                }
            })
        }
        return if (latch.await(300_000, TimeUnit.MILLISECONDS)) response.get() else null
    }
}
