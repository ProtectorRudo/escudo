package com.escudo.demo

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(TextView(this).apply {
            text = "🧪\nEscudo Demo Target"
            textSize = 28f
            gravity = Gravity.CENTER
        })
        layout.addView(TextView(this).apply {
            text = "Si podés leer esto, Escudo liberó correctamente la app de prueba. Al volver o apagar la pantalla debe quedar protegida otra vez."
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        })
        setContentView(layout)
    }
}
