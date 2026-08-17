package com.mobilehost.app

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ConsoleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_console)

        val tvConsole = findViewById<TextView>(R.id.tvConsole)
        val scroll = findViewById<ScrollView>(R.id.scrollConsole)

        AppState.consoleText.observe(this) { text ->
            tvConsole.text = if (text.isNullOrEmpty()) "(sem saída ainda)" else text
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
