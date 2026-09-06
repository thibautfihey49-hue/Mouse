package com.remotemouse

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        findViewById<Button>(R.id.btnServer).setOnClickListener {
            startActivity(Intent(this, ServerActivity::class.java))
        }
        findViewById<Button>(R.id.btnClient).setOnClickListener {
            startActivity(Intent(this, ClientActivity::class.java))
        }
    }
}
