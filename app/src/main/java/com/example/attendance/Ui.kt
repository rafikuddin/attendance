package com.example.attendance

import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

fun AppCompatActivity.setupHeader(title: String) {
    findViewById<TextView>(R.id.tvTitle).text = title
    findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
}

fun AppCompatActivity.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
