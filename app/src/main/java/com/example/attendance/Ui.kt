package com.example.attendance

import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

fun AppCompatActivity.setupHeader(title: String, onRefresh: (() -> Unit)? = null) {
    findViewById<TextView>(R.id.tvTitle).text = title
    findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
    val refresh = findViewById<TextView>(R.id.btnRefresh)
    if (onRefresh != null) {
        refresh.visibility = android.view.View.VISIBLE
        refresh.setOnClickListener { onRefresh() }
    } else {
        refresh.visibility = android.view.View.GONE
    }
}

fun AppCompatActivity.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
